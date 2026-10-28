package com.tencent.wxcloudrun.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wxcloudrun.config.ErrorCode;
import com.tencent.wxcloudrun.dao.RoomMapper;
import com.tencent.wxcloudrun.dao.RoomPlayerMapper;
import com.tencent.wxcloudrun.dao.UserMapper;
import com.tencent.wxcloudrun.dto.RoomBriefVo;
import com.tencent.wxcloudrun.dto.RoomConfig;
import com.tencent.wxcloudrun.dto.RoomPlayerVo;
import com.tencent.wxcloudrun.dto.RoomVo;
import com.tencent.wxcloudrun.exception.BizException;
import com.tencent.wxcloudrun.model.Room;
import com.tencent.wxcloudrun.model.RoomPlayer;
import com.tencent.wxcloudrun.model.User;
import com.tencent.wxcloudrun.service.RoomService;
import com.tencent.wxcloudrun.ws.RoomChangedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@Service
public class RoomServiceImpl implements RoomService {

  private static final int STATUS_LOBBY = 0;
  private static final int STATUS_PLAYING = 1;
  private static final int STATUS_DISMISSED = 3;

  private static final int MAX_PLAYER_LIMIT = 5;
  private static final int MIN_PLAYER = 2;
  /** 缺省人数：与前端 DEFAULT_CONFIG.playerCount 保持一致 */
  private static final int DEFAULT_PLAYER = 4;

  private final Logger logger = LoggerFactory.getLogger(RoomServiceImpl.class);

  final RoomMapper roomMapper;
  final RoomPlayerMapper roomPlayerMapper;
  final UserMapper userMapper;
  final ObjectMapper objectMapper;
  final ApplicationEventPublisher eventPublisher;

  private final Random random = new Random();

  public RoomServiceImpl(@Autowired RoomMapper roomMapper,
                         @Autowired RoomPlayerMapper roomPlayerMapper,
                         @Autowired UserMapper userMapper,
                         @Autowired ObjectMapper objectMapper,
                         @Autowired ApplicationEventPublisher eventPublisher) {
    this.roomMapper = roomMapper;
    this.roomPlayerMapper = roomPlayerMapper;
    this.userMapper = userMapper;
    this.objectMapper = objectMapper;
    this.eventPublisher = eventPublisher;
  }

  @Override
  @Transactional
  public RoomVo create(Long userId, RoomConfig config) {
    ensureNotInRoom(userId);
    RoomConfig finalConfig = normalize(config);

    Room room = new Room();
    room.setRoomNo(generateRoomNo());
    room.setOwnerUserId(userId);
    room.setSource(1);
    room.setStatus(STATUS_LOBBY);
    room.setMaxPlayer(finalConfig.getPlayerCount());
    room.setTotalRound(1);
    room.setCurrentRound(0);
    room.setBaseScore(1);
    room.setOptions(toJson(finalConfig));
    room.setExpireAt(LocalDateTime.now().plusHours(6));
    roomMapper.insert(room);

    RoomPlayer player = new RoomPlayer();
    player.setRoomId(room.getId());
    player.setUserId(userId);
    player.setSeat(0);
    player.setIsOwner(1);
    player.setIsReady(0);
    player.setIsOnline(1);
    player.setTotalScore(0);
    roomPlayerMapper.insert(player);

    logger.info("room created, roomId={}, code={}, owner={}", room.getId(), room.getRoomNo(), userId);
    publish(room.getId(), "joined", userId, false);
    // 回读一次，保证 updatedAt 与其它接口一样取自数据库，口径一致
    return toVo(roomMapper.selectById(room.getId()));
  }

  @Override
  @Transactional
  public RoomVo join(Long userId, String code) {
    ensureNotInRoom(userId);
    if (code == null || code.trim().isEmpty()) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "缺少房号");
    }
    Room room = roomMapper.selectByRoomNo(code.trim());
    if (room == null || room.getStatus() == STATUS_DISMISSED) {
      throw new BizException(ErrorCode.ROOM_NOT_FOUND.getCode(), ErrorCode.ROOM_NOT_FOUND.getMessage());
    }
    if (room.getStatus() != STATUS_LOBBY) {
      throw new BizException(ErrorCode.ROOM_PLAYING.getCode(), ErrorCode.ROOM_PLAYING.getMessage());
    }
    int count = roomPlayerMapper.countByRoomId(room.getId());
    int limit = room.getMaxPlayer() == null ? MAX_PLAYER_LIMIT : room.getMaxPlayer();
    if (count >= limit) {
      throw new BizException(ErrorCode.ROOM_FULL.getCode(), ErrorCode.ROOM_FULL.getMessage());
    }

    Integer maxSeat = roomPlayerMapper.maxSeat(room.getId());
    RoomPlayer player = new RoomPlayer();
    player.setRoomId(room.getId());
    player.setUserId(userId);
    player.setSeat(maxSeat == null ? 0 : maxSeat + 1);
    player.setIsOwner(0);
    player.setIsReady(0);
    player.setIsOnline(1);
    player.setTotalScore(0);
    roomPlayerMapper.insert(player);
    roomMapper.touch(room.getId());
    publish(room.getId(), "joined", userId, false);

    return toVo(roomMapper.selectById(room.getId()));
  }

  @Override
  @Transactional
  public RoomVo leave(Long userId, Long roomId) {
    RoomPlayer mine = roomPlayerMapper.selectByRoomAndUser(roomId, userId);
    if (mine == null) {
      throw new BizException(ErrorCode.NOT_IN_ROOM.getCode(), ErrorCode.NOT_IN_ROOM.getMessage());
    }
    Room room = roomMapper.selectById(roomId);
    if (room == null) {
      throw new BizException(ErrorCode.NOT_IN_ROOM.getCode(), ErrorCode.NOT_IN_ROOM.getMessage());
    }

    roomPlayerMapper.deleteByRoomAndUser(roomId, userId);
    int remain = roomPlayerMapper.countByRoomId(roomId);
    if (remain <= 0) {
      roomMapper.updateStatus(roomId, STATUS_DISMISSED);
      publish(roomId, "left", userId, true);
      return null;
    }
    // 房主离开：顺延给座位最靠前的成员
    if (room.getOwnerUserId() != null && room.getOwnerUserId().equals(userId)) {
      List<RoomPlayer> players = roomPlayerMapper.selectByRoomId(roomId);
      RoomPlayer next = players.isEmpty() ? null : players.get(0);
      if (next != null) {
        roomMapper.updateOwner(roomId, next.getUserId());
        roomPlayerMapper.setOwner(roomId, next.getUserId(), 1);
      }
    }
    roomMapper.touch(roomId);
    publish(roomId, "left", userId, false);
    return toVo(roomMapper.selectById(roomId));
  }

  @Override
  @Transactional
  public RoomVo setReady(Long userId, Long roomId, boolean ready) {
    RoomPlayer mine = roomPlayerMapper.selectByRoomAndUser(roomId, userId);
    if (mine == null) {
      throw new BizException(ErrorCode.NOT_IN_ROOM.getCode(), ErrorCode.NOT_IN_ROOM.getMessage());
    }
    roomPlayerMapper.updateReady(roomId, userId, ready ? 1 : 0);
    roomMapper.touch(roomId);
    publish(roomId, "ready", userId, false);
    Room room = roomMapper.selectById(roomId);
    return toVo(room);
  }

  @Override
  @Transactional
  public RoomVo updateConfig(Long userId, Long roomId, RoomConfig config) {
    Room room = roomMapper.selectById(roomId);
    if (room == null || room.getStatus() == null || room.getStatus() == STATUS_DISMISSED) {
      throw new BizException(ErrorCode.ROOM_NOT_FOUND.getCode(), ErrorCode.ROOM_NOT_FOUND.getMessage());
    }
    if (room.getOwnerUserId() == null || !room.getOwnerUserId().equals(userId)) {
      throw new BizException(ErrorCode.NOT_ROOM_OWNER.getCode(), "只有房主可以修改规则");
    }
    if (room.getStatus() != STATUS_LOBBY) {
      throw new BizException(ErrorCode.ROOM_PLAYING.getCode(), ErrorCode.ROOM_PLAYING.getMessage());
    }

    RoomConfig finalConfig = normalize(config);
    // 上限不能调到比现在屋里的人还少，否则一提交房间就处于超员状态
    int current = roomPlayerMapper.countByRoomId(roomId);
    if (finalConfig.getPlayerCount() < current) {
      finalConfig.setPlayerCount(current);
    }

    room.setMaxPlayer(finalConfig.getPlayerCount());
    room.setOptions(toJson(finalConfig));
    // updateConfig 这条 SQL 自己就把 revision +1 了，不用再 touch（updated_at 由 ON UPDATE 维护）
    roomMapper.updateConfig(room);
    publish(roomId, "config", userId, false);
    return toVo(roomMapper.selectById(roomId));
  }

  @Override
  @Transactional
  public RoomVo start(Long userId, Long roomId, RoomConfig config) {
    Room room = roomMapper.selectById(roomId);
    if (room == null || room.getStatus() == null || room.getStatus() == STATUS_DISMISSED) {
      throw new BizException(ErrorCode.ROOM_NOT_FOUND.getCode(), ErrorCode.ROOM_NOT_FOUND.getMessage());
    }
    if (room.getOwnerUserId() == null || !room.getOwnerUserId().equals(userId)) {
      throw new BizException(ErrorCode.NOT_ROOM_OWNER.getCode(), ErrorCode.NOT_ROOM_OWNER.getMessage());
    }
    // 已经开局了就别重复开：重复调用会把 revision 白推一轮，还会把已在牌桌上的人拉回来
    if (room.getStatus() != STATUS_LOBBY) {
      throw new BizException(ErrorCode.ROOM_PLAYING.getCode(), ErrorCode.ROOM_PLAYING.getMessage());
    }

    List<RoomPlayer> players = roomPlayerMapper.selectByRoomId(roomId);
    if (players.size() < MIN_PLAYER) {
      throw new BizException(ErrorCode.ROOM_NEED_MORE_PLAYER.getCode(), ErrorCode.ROOM_NEED_MORE_PLAYER.getMessage());
    }
    // 客户端会拦，但接口得自己拦：不然改个请求就能带着没准备的人开局
    for (RoomPlayer player : players) {
      boolean isOwner = room.getOwnerUserId().equals(player.getUserId());
      if (!isOwner && (player.getIsReady() == null || player.getIsReady() != 1)) {
        throw new BizException(ErrorCode.ROOM_NOT_ALL_READY.getCode(), ErrorCode.ROOM_NOT_ALL_READY.getMessage());
      }
    }

    RoomConfig finalConfig = config == null ? readConfig(room) : normalize(config);
    if (finalConfig.getPlayerCount() < players.size()) {
      finalConfig.setPlayerCount(players.size());
    }
    room.setMaxPlayer(finalConfig.getPlayerCount());
    room.setOptions(toJson(finalConfig));
    room.setStatus(STATUS_PLAYING);
    // 配置 + 状态一条 UPDATE 写完（revision 只 +1），别让一次开局把版本号顶高 3
    roomMapper.updateConfigAndStatus(room);
    publish(roomId, "started", userId, false);
    return toVo(roomMapper.selectById(roomId));
  }

  @Override
  @Transactional
  public RoomVo finishGame(Long roomId) {
    Room room = roomMapper.selectById(roomId);
    if (room == null || room.getStatus() == null || room.getStatus() == STATUS_DISMISSED) {
      return null;
    }
    roomMapper.updateStatus(roomId, STATUS_LOBBY);
    // 下一局要重新准备，否则「全员已准备」这个前置条件会失效
    roomPlayerMapper.resetReady(roomId);
    roomMapper.touch(roomId);
    publish(roomId, "ended", null, false);
    return toVo(roomMapper.selectById(roomId));
  }

  @Override
  public RoomVo detail(Long userId, Long roomId) {
    Room room = roomMapper.selectById(roomId);
    // 已解散的房间不能还回一个「看起来正常」的房间对象，否则客户端轮询会一直停在房间里
    if (room == null || room.getStatus() == null || room.getStatus() == STATUS_DISMISSED) {
      throw new BizException(ErrorCode.ROOM_NOT_FOUND.getCode(), ErrorCode.ROOM_NOT_FOUND.getMessage());
    }
    RoomPlayer mine = roomPlayerMapper.selectByRoomAndUser(roomId, userId);
    if (mine == null) {
      throw new BizException(ErrorCode.NOT_IN_ROOM.getCode(), ErrorCode.NOT_IN_ROOM.getMessage());
    }
    return toVo(room);
  }

  @Override
  public RoomVo mine(Long userId) {
    RoomPlayer player = roomPlayerMapper.selectByUserId(userId);
    if (player == null) {
      return null;
    }
    Room room = roomMapper.selectById(player.getRoomId());
    if (room == null || room.getStatus() == STATUS_DISMISSED) {
      return null;
    }
    return toVo(room);
  }

  @Override
  public List<RoomBriefVo> list(Long userId) {
    RoomPlayer mine = roomPlayerMapper.selectByUserId(userId);
    Long myRoomId = mine == null ? null : mine.getRoomId();
    List<Room> rooms = roomMapper.selectLobbyRooms(10);
    List<RoomBriefVo> result = new ArrayList<>();
    for (Room room : rooms) {
      if (myRoomId != null && myRoomId.equals(room.getId())) {
        continue;
      }
      RoomBriefVo vo = new RoomBriefVo();
      vo.setRoomId("r_" + room.getId());
      vo.setCode(room.getRoomNo());
      vo.setOwnerId("u_" + room.getOwnerUserId());
      vo.setPlayerCount(roomPlayerMapper.countByRoomId(room.getId()));
      result.add(vo);
    }
    return result;
  }

  @Override
  public RoomVo snapshot(Long roomId) {
    Room room = roomMapper.selectById(roomId);
    if (room == null || room.getStatus() == null || room.getStatus() == STATUS_DISMISSED) {
      return null;
    }
    return toVo(room);
  }

  /**
   * 房间变更后发事件，由 WS 层在事务提交后广播（见 RoomEventSubscriber）
   */
  private void publish(Long roomId, String reason, Long actor, boolean dismissed) {
    List<Long> actors = new ArrayList<>(1);
    // actor 为空表示「系统触发」（比如一局自然结束），不需要标出是谁
    if (actor != null) {
      actors.add(actor);
    }
    eventPublisher.publishEvent(new RoomChangedEvent(roomId, reason, actors, dismissed));
  }

  private void ensureNotInRoom(Long userId) {
    RoomPlayer player = roomPlayerMapper.selectByUserId(userId);
    if (player != null) {
      Room room = roomMapper.selectById(player.getRoomId());
      if (room != null && room.getStatus() != STATUS_DISMISSED) {
        throw new BizException(ErrorCode.ALREADY_IN_ROOM.getCode(), ErrorCode.ALREADY_IN_ROOM.getMessage());
      }
    }
  }

  private RoomConfig normalize(RoomConfig config) {
    RoomConfig result = config == null ? new RoomConfig() : config;
    // 没传 -> 用默认 4 人；传了非法值 -> 夹到 [MIN_PLAYER, MAX_PLAYER_LIMIT]
    if (result.getPlayerCount() == null) {
      result.setPlayerCount(DEFAULT_PLAYER);
    }
    if (result.getPlayerCount() < MIN_PLAYER) {
      result.setPlayerCount(MIN_PLAYER);
    }
    if (result.getPlayerCount() > MAX_PLAYER_LIMIT) {
      result.setPlayerCount(MAX_PLAYER_LIMIT);
    }
    if (result.getDeckCount() == null || result.getDeckCount() < 1) {
      result.setDeckCount(1);
    }
    // 顺序是 14 档权重表，不管有没有鬼都含 JOKER 档（withJokers 只决定发不发鬼牌），
    // 所以「含不含鬼」只能在客户端**显式传了 order** 时作为线索，而且要在补默认值之前判。
    List<String> rawOrder = result.getOrder();
    boolean orderProvided = rawOrder != null && !rawOrder.isEmpty();
    if (!orderProvided) {
      result.setOrder(RoomConfig.defaultOrder());
    }
    if (result.getWithJokers() == null) {
      result.setWithJokers(orderProvided && rawOrder.contains("JOKER"));
    }
    return result;
  }

  private RoomVo toVo(Room room) {
    if (room == null) {
      return null;
    }
    RoomVo vo = new RoomVo();
    vo.setRoomId("r_" + room.getId());
    vo.setCode(room.getRoomNo());
    vo.setOwnerId("u_" + room.getOwnerUserId());
    vo.setStatus(room.getStatus() != null && room.getStatus() == STATUS_PLAYING ? "playing" : "lobby");
    vo.setConfig(readConfig(room));
    vo.setPlayers(buildPlayers(room.getId(), room.getOwnerUserId()));
    vo.setUpdatedAt(room.getUpdatedAt() == null
        ? System.currentTimeMillis()
        : room.getUpdatedAt().toInstant(ZoneOffset.ofHours(8)).toEpochMilli());
    vo.setRevision(room.getRevision() == null ? 0L : room.getRevision());
    return vo;
  }

  private List<RoomPlayerVo> buildPlayers(Long roomId, Long ownerUserId) {
    List<RoomPlayer> players = roomPlayerMapper.selectByRoomId(roomId);
    List<RoomPlayerVo> vos = new ArrayList<>();
    for (RoomPlayer player : players) {
      RoomPlayerVo vo = new RoomPlayerVo();
      vo.setUserId("u_" + player.getUserId());
      User user = userMapper.selectById(player.getUserId());
      vo.setNickname(user == null ? "" : (user.getNickname() == null ? "" : user.getNickname()));
      vo.setAvatarUrl(user == null ? "" : (user.getAvatarUrl() == null ? "" : user.getAvatarUrl()));
      boolean owner = ownerUserId != null && ownerUserId.equals(player.getUserId());
      vo.setIsOwner(owner);
      vo.setReady(player.getIsReady() != null && player.getIsReady() == 1);
      vos.add(vo);
    }
    return vos;
  }

  /**
   * 从库里读配置，永远返回一份填满的配置
   * （RoomConfig 的字段没有初始值，不兜底的话上层拆箱会 NPE）
   */
  private RoomConfig readConfig(Room room) {
    if (room.getOptions() == null || room.getOptions().isEmpty()) {
      return normalize(null);
    }
    try {
      return normalize(objectMapper.readValue(room.getOptions(), RoomConfig.class));
    } catch (Exception e) {
      logger.warn("房间 {} 的配置解析失败，按默认配置处理", room.getId(), e);
      return normalize(null);
    }
  }

  private String toJson(RoomConfig config) {
    try {
      return objectMapper.writeValueAsString(config);
    } catch (Exception e) {
      return "";
    }
  }

  private String generateRoomNo() {
    for (int i = 0; i < 30; i++) {
      String no = String.valueOf(100000 + random.nextInt(900000));
      if (roomMapper.selectByRoomNo(no) == null) {
        return no;
      }
    }
    throw new BizException(ErrorCode.SERVER_ERROR.getCode(), "房号生成失败");
  }
}
