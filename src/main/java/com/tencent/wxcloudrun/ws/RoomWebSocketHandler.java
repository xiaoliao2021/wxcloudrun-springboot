package com.tencent.wxcloudrun.ws;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wxcloudrun.config.ErrorCode;
import com.tencent.wxcloudrun.dao.RoomPlayerMapper;
import com.tencent.wxcloudrun.dao.UserMapper;
import com.tencent.wxcloudrun.dto.RoomVo;
import com.tencent.wxcloudrun.model.RoomPlayer;
import com.tencent.wxcloudrun.model.User;
import com.tencent.wxcloudrun.model.UserSession;
import com.tencent.wxcloudrun.service.AuthService;
import com.tencent.wxcloudrun.service.GameService;
import com.tencent.wxcloudrun.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.handler.TextWebSocketHandler;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.nio.charset.StandardCharsets;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Random;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 大厅 / 房间的长连接入口，协议见前端 docs/api-realtime.md。
 *
 * 分工：写操作仍然走 HTTP，这里只负责"状态变了，通知所有人"。
 * 连接对象只存 userId / connId / 订阅的 roomId，房间真实状态每次推送时现查。
 */
@Component
public class RoomWebSocketHandler extends TextWebSocketHandler {

  public static final String ATTR_SEQ = "wsSeq";
  public static final String ATTR_AUTHED = "wsAuthed";
  public static final String ATTR_USER_ID = "wsUserId";
  public static final String ATTR_CONN_ID = "wsConnId";
  public static final String ATTR_TOKEN = "wsToken";
  public static final String ATTR_ACCESS_EXPIRE = "wsAccessExpireAt";
  public static final String ATTR_LAST_ACTIVE = "wsLastActive";
  public static final String ATTR_ROOM_ID = "wsRoomId";
  public static final String ATTR_GRACE_UNTIL = "wsGraceUntil";
  public static final String ATTR_AUTH_TASK = "wsAuthTask";

  /** 连上后必须在这个时间内发出 auth */
  private static final int AUTH_WINDOW_MS = 5_000;
  /** 下发给客户端的心跳间隔（秒） */
  private static final int HEARTBEAT_SEC = 25;
  /** 收不到任何帧就断开 */
  private static final long IDLE_TIMEOUT_MS = 60_000L;
  /** auth.expired 之后的宽限期 */
  private static final long GRACE_MS = 10_000L;
  /** 单帧上限 64KB */
  private static final int MAX_FRAME_BYTES = 64 * 1024;

  private static final int CLOSE_AUTH_FAIL = 4001;
  private static final int CLOSE_HEARTBEAT_TIMEOUT = 4002;
  private static final int CLOSE_KICKED = 4003;

  private final Logger logger = LoggerFactory.getLogger(RoomWebSocketHandler.class);

  final AuthService authService;
  final UserMapper userMapper;
  final RoomPlayerMapper roomPlayerMapper;
  final RoomService roomService;
  final GameService gameService;
  final WsRealtimeBroker broker;
  final ObjectMapper objectMapper;

  private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(2, r -> {
    Thread t = new Thread(r, "ws-scheduler");
    t.setDaemon(true);
    return t;
  });

  private final Random random = new Random();

  public RoomWebSocketHandler(@Autowired AuthService authService,
                              @Autowired UserMapper userMapper,
                              @Autowired RoomPlayerMapper roomPlayerMapper,
                              @Autowired RoomService roomService,
                              @Autowired GameService gameService,
                              @Autowired WsRealtimeBroker broker,
                              @Autowired ObjectMapper objectMapper) {
    this.authService = authService;
    this.userMapper = userMapper;
    this.roomPlayerMapper = roomPlayerMapper;
    this.roomService = roomService;
    this.gameService = gameService;
    this.broker = broker;
    this.objectMapper = objectMapper;
  }

  @PostConstruct
  public void startSweeper() {
    scheduler.scheduleAtFixedRate(this::sweep, 20L, 20L, TimeUnit.SECONDS);
  }

  @PreDestroy
  public void shutdown() {
    scheduler.shutdownNow();
  }

  @Override
  public void afterConnectionEstablished(WebSocketSession session) throws Exception {
    Map<String, Object> attrs = session.getAttributes();
    attrs.put(ATTR_SEQ, new AtomicLong(0));
    attrs.put(ATTR_LAST_ACTIVE, new AtomicLong(System.currentTimeMillis()));
    attrs.put(ATTR_AUTHED, Boolean.FALSE);
    attrs.put(ATTR_CONN_ID, "c_" + randomHex(6));
    broker.register(session);

    ScheduledFuture<?> task = scheduler.schedule(() -> {
      if (!isAuthed(session)) {
        logger.info("ws auth timeout, session={}", session.getId());
        close(session, CLOSE_AUTH_FAIL, "认证超时");
      }
    }, AUTH_WINDOW_MS, TimeUnit.MILLISECONDS);
    attrs.put(ATTR_AUTH_TASK, task);
    logger.info("ws connected, session={}, connId={}", session.getId(), attrs.get(ATTR_CONN_ID));
  }

  @Override
  protected void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
    String payload = message.getPayload();
    if (payload != null && payload.getBytes(StandardCharsets.UTF_8).length > MAX_FRAME_BYTES) {
      logger.warn("ws frame too large, session={}", session.getId());
      close(session, CloseStatus.TOO_BIG_TO_PROCESS.getCode(), "帧超过 64KB");
      return;
    }
    touch(session);

    JsonNode root;
    try {
      root = objectMapper.readTree(payload);
    } catch (Exception e) {
      sendError(session, null, ErrorCode.PARAM_ERROR.getCode(), "帧格式错误");
      return;
    }
    if (root == null || !root.hasNonNull("type")) {
      sendError(session, null, ErrorCode.PARAM_ERROR.getCode(), "缺少 type");
      return;
    }
    String type = root.get("type").asText();
    String reqId = root.hasNonNull("reqId") ? root.get("reqId").asText() : null;

    // 宽限期内只允许 reauth / ping
    if (attrLong(session, ATTR_GRACE_UNTIL, 0L) > 0L
        && !"reauth".equals(type) && !"ping".equals(type)) {
      sendError(session, reqId, ErrorCode.ACCESS_TOKEN_INVALID.getCode(), "accessToken 已过期，请先 reauth");
      return;
    }

    switch (type) {
      case "auth":
      case "reauth":
        handleAuth(session, root, reqId);
        break;
      case "ping":
        handlePing(session, root, reqId);
        break;
      case "room.subscribe":
        handleSubscribe(session, root, reqId);
        break;
      case "room.unsubscribe":
        handleUnsubscribe(session, root, reqId);
        break;
      default:
        sendError(session, reqId, ErrorCode.FRAME_UNKNOWN_TYPE.getCode(),
            ErrorCode.FRAME_UNKNOWN_TYPE.getMessage() + ": " + type);
        break;
    }
  }

  private void handleAuth(WebSocketSession session, JsonNode root, String reqId) {
    JsonNode tokenNode = root.path("data").get("token");
    if (tokenNode == null || tokenNode.asText("").trim().isEmpty()) {
      authFail(session, reqId, ErrorCode.PARAM_ERROR.getCode(), "缺少 token");
      return;
    }
    String token = tokenNode.asText().trim();
    UserSession userSession = authService.getSession(token);
    if (userSession == null) {
      authFail(session, reqId, ErrorCode.ACCESS_TOKEN_INVALID.getCode(),
          ErrorCode.ACCESS_TOKEN_INVALID.getMessage());
      return;
    }
    User user = userMapper.selectById(userSession.getUserId());
    if (user == null || user.getStatus() == null || user.getStatus() != 1) {
      authFail(session, reqId, ErrorCode.KICKED_OFFLINE.getCode(), ErrorCode.KICKED_OFFLINE.getMessage());
      return;
    }

    Map<String, Object> attrs = session.getAttributes();
    attrs.put(ATTR_AUTHED, Boolean.TRUE);
    attrs.put(ATTR_USER_ID, userSession.getUserId());
    attrs.put(ATTR_TOKEN, token);
    attrs.put(ATTR_ACCESS_EXPIRE, toMillis(userSession.getAccessExpireAt()));
    attrs.remove(ATTR_GRACE_UNTIL);
    Object task = attrs.get(ATTR_AUTH_TASK);
    if (task instanceof ScheduledFuture) {
      ((ScheduledFuture<?>) task).cancel(false);
    }

    Map<String, Object> data = new LinkedHashMap<>(4);
    data.put("userId", "u_" + userSession.getUserId());
    data.put("connId", String.valueOf(attrs.get(ATTR_CONN_ID)));
    data.put("heartbeatSec", HEARTBEAT_SEC);
    broker.send(session, "auth.ok", data, reqId);
    logger.info("ws authed, session={}, userId={}", session.getId(), userSession.getUserId());
  }

  private void handlePing(WebSocketSession session, JsonNode root, String reqId) {
    JsonNode tNode = root.path("data").get("t");
    long t = (tNode == null || tNode.isNull()) ? System.currentTimeMillis() : tNode.asLong();
    Map<String, Object> data = new LinkedHashMap<>(2);
    data.put("t", t);
    broker.send(session, "pong", data, reqId);
  }

  private void handleSubscribe(WebSocketSession session, JsonNode root, String reqId) {
    if (!isAuthed(session)) {
      sendError(session, reqId, ErrorCode.ACCESS_TOKEN_INVALID.getCode(), "未认证");
      return;
    }
    Long roomId = parseRoomId(root.path("data").get("roomId"));
    if (roomId == null) {
      sendError(session, reqId, ErrorCode.PARAM_ERROR.getCode(), "缺少 roomId");
      return;
    }
    Long userId = (Long) session.getAttributes().get(ATTR_USER_ID);
    RoomPlayer mine = roomPlayerMapper.selectByRoomAndUser(roomId, userId);
    if (mine == null) {
      sendError(session, reqId, ErrorCode.NOT_IN_ROOM.getCode(), "你不在这个房间里");
      return;
    }
    RoomVo room = roomService.snapshot(roomId);
    if (room == null) {
      sendRoomClosed(session, roomId, ErrorCode.ROOM_NOT_FOUND.getCode(), "房间已解散", reqId);
      return;
    }
    // 一个连接同时只订阅一个房间，换房先退订旧的
    Long previous = (Long) session.getAttributes().get(ATTR_ROOM_ID);
    if (previous != null && !previous.equals(roomId)) {
      broker.unsubscribe(session, previous);
    }
    broker.subscribe(session, roomId);
    session.getAttributes().put(ATTR_ROOM_ID, roomId);

    Map<String, Object> data = new LinkedHashMap<>(4);
    data.put("reason", "init");
    data.put("actors", Collections.emptyList());
    data.put("revision", room.getRevision());
    data.put("room", room);
    broker.send(session, "room.state", data, reqId);

    // 对局正在进行（重连 / 中途订阅）：把对局状态和自己的手牌补一份，
    // 否则客户端只知道房间在 playing，桌面和手牌都是空的
    if ("playing".equals(room.getStatus())) {
      try {
        Map<String, Object> game = gameService.stateOfUser(userId);
        if (game != null) {
          broker.send(session, "game.state", game, null);
          Map<String, Object> hand = gameService.handViewOfUser(userId);
          if (hand != null) {
            broker.send(session, "game.hand", hand, null);
          }
        }
      } catch (Exception e) {
        logger.warn("补发对局状态失败, session={}, userId={}", session.getId(), userId, e);
      }
    }
  }

  private void handleUnsubscribe(WebSocketSession session, JsonNode root, String reqId) {
    if (!isAuthed(session)) {
      sendError(session, reqId, ErrorCode.ACCESS_TOKEN_INVALID.getCode(), "未认证");
      return;
    }
    Long roomId = parseRoomId(root.path("data").get("roomId"));
    if (roomId == null) {
      sendError(session, reqId, ErrorCode.PARAM_ERROR.getCode(), "缺少 roomId");
      return;
    }
    broker.unsubscribe(session, roomId);
    Long current = (Long) session.getAttributes().get(ATTR_ROOM_ID);
    if (current != null && current.equals(roomId)) {
      session.getAttributes().remove(ATTR_ROOM_ID);
    }
    // 协议里没给退订定义应答帧，这里补一个同 type 的确认，方便客户端 resolve Promise
    Map<String, Object> data = new LinkedHashMap<>(2);
    data.put("roomId", "r_" + roomId);
    data.put("subscribed", Boolean.FALSE);
    broker.send(session, "room.unsubscribe", data, reqId);
  }

  /**
   * 定时扫描：空闲超时、token 过期、被互踢
   */
  private void sweep() {
    long now = System.currentTimeMillis();
    for (WebSocketSession session : new ArrayList<>(broker.all())) {
      try {
        if (!session.isOpen()) {
          broker.unregister(session);
          continue;
        }
        if (!isAuthed(session)) {
          continue;
        }
        AtomicLong lastActive = (AtomicLong) session.getAttributes().get(ATTR_LAST_ACTIVE);
        if (lastActive != null && now - lastActive.get() > IDLE_TIMEOUT_MS) {
          logger.info("ws idle timeout, session={}, userId={}", session.getId(),
              session.getAttributes().get(ATTR_USER_ID));
          close(session, CLOSE_HEARTBEAT_TIMEOUT, "心跳超时");
          continue;
        }
        long expireAt = attrLong(session, ATTR_ACCESS_EXPIRE, Long.MAX_VALUE);
        if (now > expireAt) {
          if (attrLong(session, ATTR_GRACE_UNTIL, 0L) <= 0L) {
            Map<String, Object> data = new LinkedHashMap<>(2);
            data.put("code", ErrorCode.ACCESS_TOKEN_INVALID.getCode());
            data.put("message", ErrorCode.ACCESS_TOKEN_INVALID.getMessage());
            broker.send(session, "auth.expired", data, null);
            session.getAttributes().put(ATTR_GRACE_UNTIL, now + GRACE_MS);
            scheduler.schedule(() -> {
              if (session.isOpen() && attrLong(session, ATTR_GRACE_UNTIL, 0L) > 0L) {
                logger.info("ws reauth timeout, session={}", session.getId());
                close(session, CLOSE_AUTH_FAIL, "token 过期未续期");
              }
            }, GRACE_MS, TimeUnit.MILLISECONDS);
            logger.info("ws token expired, session={}, userId={}", session.getId(),
                session.getAttributes().get(ATTR_USER_ID));
          }
          continue;
        }
        // token 没过期但会话已失效 = 在别处登录 / 登出 / 封禁
        String token = (String) session.getAttributes().get(ATTR_TOKEN);
        if (token != null && authService.getSession(token) == null) {
          logger.info("ws kicked, session={}, userId={}", session.getId(),
              session.getAttributes().get(ATTR_USER_ID));
          close(session, CLOSE_KICKED, "账号已在其他设备登录");
        }
      } catch (Exception e) {
        logger.warn("ws sweep error, session={}", session.getId(), e);
      }
    }
  }

  @Override
  public void afterConnectionClosed(WebSocketSession session, CloseStatus status) throws Exception {
    Object task = session.getAttributes().get(ATTR_AUTH_TASK);
    if (task instanceof ScheduledFuture) {
      ((ScheduledFuture<?>) task).cancel(false);
    }
    broker.unregister(session);
    logger.info("ws closed, session={}, code={}, reason={}", session.getId(), status.getCode(),
        status.getReason());
  }

  @Override
  public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception {
    logger.warn("ws transport error, session={}", session.getId(), exception);
    broker.closeQuietly(session, CloseStatus.SERVER_ERROR);
  }

  private void authFail(WebSocketSession session, String reqId, int code, String message) {
    Map<String, Object> data = new LinkedHashMap<>(3);
    data.put("code", code);
    data.put("message", message);
    if (reqId != null) {
      data.put("reqId", reqId);
    }
    broker.send(session, "auth.fail", data, reqId);
    close(session, CLOSE_AUTH_FAIL, "认证失败");
  }

  private void sendError(WebSocketSession session, String reqId, int code, String message) {
    Map<String, Object> data = new LinkedHashMap<>(3);
    data.put("code", code);
    data.put("message", message);
    if (reqId != null) {
      data.put("reqId", reqId);
    }
    broker.send(session, "error", data, reqId);
  }

  private void sendRoomClosed(WebSocketSession session, Long roomId, int code, String message, String reqId) {
    Map<String, Object> data = new LinkedHashMap<>(3);
    data.put("roomId", "r_" + roomId);
    data.put("code", code);
    data.put("message", message);
    broker.send(session, "room.closed", data, reqId);
  }

  private void close(WebSocketSession session, int code, String reason) {
    try {
      if (session.isOpen()) {
        session.close(new CloseStatus(code, reason));
      }
    } catch (Exception ignore) {
      // 关闭失败无需处理
    }
  }

  private void touch(WebSocketSession session) {
    AtomicLong lastActive = (AtomicLong) session.getAttributes().get(ATTR_LAST_ACTIVE);
    if (lastActive != null) {
      lastActive.set(System.currentTimeMillis());
    }
  }

  private boolean isAuthed(WebSocketSession session) {
    return Boolean.TRUE.equals(session.getAttributes().get(ATTR_AUTHED));
  }

  private long attrLong(WebSocketSession session, String key, long defaultValue) {
    Object value = session.getAttributes().get(key);
    return value instanceof Number ? ((Number) value).longValue() : defaultValue;
  }

  private long toMillis(java.time.LocalDateTime dateTime) {
    if (dateTime == null) {
      return Long.MAX_VALUE;
    }
    return dateTime.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli();
  }

  /** "r_101" / "101" -> 101，非法返回 null */
  private Long parseRoomId(JsonNode node) {
    if (node == null || node.isNull()) {
      return null;
    }
    String raw = node.asText("").trim();
    if (raw.isEmpty()) {
      return null;
    }
    if (raw.startsWith("r_")) {
      raw = raw.substring(2);
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private String randomHex(int length) {
    StringBuilder sb = new StringBuilder(length);
    for (int i = 0; i < length; i++) {
      sb.append(Integer.toHexString(random.nextInt(16)));
    }
    return sb.toString();
  }
}
