package com.tencent.wxcloudrun.ws;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wxcloudrun.dto.RoomVo;
import com.tencent.wxcloudrun.service.RoomService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketSession;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 连接注册表 + 房间广播。
 *
 * 单实例部署时这样够了；将来多实例需要把 broadcast* 换成 Redis Pub/Sub，
 * 连接对象本身仍然是无状态的（只认 userId / 订阅的 roomId）。
 */
@Component
public class WsRealtimeBroker {

  private static final Logger logger = LoggerFactory.getLogger(WsRealtimeBroker.class);

  /** 所有已建立的连接 */
  private final Set<WebSocketSession> sessions = ConcurrentHashMap.newKeySet();
  /** roomId -> 订阅了该房间的连接 */
  private final Map<Long, Set<WebSocketSession>> roomSubs = new ConcurrentHashMap<>();

  final RoomService roomService;
  final ObjectMapper objectMapper;

  public WsRealtimeBroker(@Autowired RoomService roomService, @Autowired ObjectMapper objectMapper) {
    this.roomService = roomService;
    this.objectMapper = objectMapper;
  }

  public void register(WebSocketSession session) {
    sessions.add(session);
  }

  public void unregister(WebSocketSession session) {
    sessions.remove(session);
    unsubscribeAll(session);
  }

  public Collection<WebSocketSession> all() {
    return Collections.unmodifiableCollection(sessions);
  }

  public void subscribe(WebSocketSession session, Long roomId) {
    roomSubs.computeIfAbsent(roomId, k -> ConcurrentHashMap.newKeySet()).add(session);
  }

  public void unsubscribe(WebSocketSession session, Long roomId) {
    Set<WebSocketSession> set = roomSubs.get(roomId);
    if (set != null) {
      set.remove(session);
      if (set.isEmpty()) {
        roomSubs.remove(roomId, set);
      }
    }
  }

  public void unsubscribeAll(WebSocketSession session) {
    for (Map.Entry<Long, Set<WebSocketSession>> entry : roomSubs.entrySet()) {
      Set<WebSocketSession> set = entry.getValue();
      if (set.remove(session) && set.isEmpty()) {
        roomSubs.remove(entry.getKey(), set);
      }
    }
  }

  /**
   * 广播房间全量快照。推的是权威状态，不是增量——丢帧也能自愈
   */
  public void broadcastRoomState(Long roomId, String reason, List<Long> actors) {
    RoomVo room = roomService.snapshot(roomId);
    if (room == null) {
      broadcastRoomClosed(roomId, 4101, "房间已解散");
      return;
    }
    Map<String, Object> data = new LinkedHashMap<>(4);
    data.put("reason", reason);
    data.put("actors", toUserIds(actors));
    data.put("revision", room.getRevision());
    data.put("room", room);

    Set<WebSocketSession> targets = roomSubs.get(roomId);
    if (targets == null || targets.isEmpty()) {
      return;
    }
    for (WebSocketSession session : new ArrayList<>(targets)) {
      send(session, "room.state", data, null);
    }
    logger.info("broadcast room.state, roomId={}, reason={}, revision={}, targets={}",
        roomId, reason, room.getRevision(), targets.size());
  }

  /**
   * 房间没了：解散 / 已开局 / 被移出 / 超时回收
   */
  public void broadcastRoomClosed(Long roomId, int code, String message) {
    Map<String, Object> data = new LinkedHashMap<>(4);
    data.put("roomId", "r_" + roomId);
    data.put("code", code);
    data.put("message", message);

    Set<WebSocketSession> targets = roomSubs.get(roomId);
    if (targets == null || targets.isEmpty()) {
      return;
    }
    for (WebSocketSession session : new ArrayList<>(targets)) {
      send(session, "room.closed", data, null);
    }
    logger.info("broadcast room.closed, roomId={}, code={}, targets={}", roomId, code, targets.size());
  }

  /* ---------------- 对局推送 ---------------- */

  /**
   * 给订阅了该房间的所有连接广播一帧（对局状态用）
   * 和 room.state 一样是「全量」语义，客户端靠 revision 丢弃乱序
   */
  public void broadcastToRoom(Long roomId, String type, Map<String, Object> data) {
    Set<WebSocketSession> targets = roomSubs.get(roomId);
    if (targets == null || targets.isEmpty()) {
      return;
    }
    for (WebSocketSession session : new ArrayList<>(targets)) {
      send(session, type, data, null);
    }
  }

  /**
   * 按用户私发：手牌只给本人，不能进房间广播
   * 同一账号可能在多处登录（互踢之前），全部推一遍
   */
  public void sendToUser(Long userId, String type, Map<String, Object> data) {
    if (userId == null) {
      return;
    }
    for (WebSocketSession session : new ArrayList<>(sessions)) {
      Object id = session.getAttributes().get(RoomWebSocketHandler.ATTR_USER_ID);
      if (id instanceof Number && ((Number) id).longValue() == userId) {
        send(session, type, data, null);
      }
    }
  }

  public void send(WebSocketSession session, String type, Map<String, Object> data, String reqId) {
    if (session == null || !session.isOpen()) {
      return;
    }
    Object seqHolder = session.getAttributes().get(RoomWebSocketHandler.ATTR_SEQ);
    long seq = seqHolder instanceof AtomicLong ? ((AtomicLong) seqHolder).incrementAndGet() : 0L;
    WsFrame frame = new WsFrame(type, seq, data, reqId);
    try {
      String json = objectMapper.writeValueAsString(frame);
      // WebSocketSession 的 sendMessage 不支持并发调用，按 session 串行化
      synchronized (session) {
        session.sendMessage(new TextMessage(json));
      }
    } catch (Exception e) {
      logger.warn("ws send failed, type={}, session={}", type, session.getId(), e);
      closeQuietly(session, CloseStatus.SESSION_NOT_RELIABLE);
    }
  }

  public void closeQuietly(WebSocketSession session, CloseStatus status) {
    try {
      if (session.isOpen()) {
        session.close(status);
      }
    } catch (Exception ignore) {
      // 关闭失败无需处理
    }
  }

  private List<String> toUserIds(List<Long> actors) {
    if (actors == null || actors.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> result = new ArrayList<>(actors.size());
    for (Long id : actors) {
      result.add("u_" + id);
    }
    return result;
  }
}
