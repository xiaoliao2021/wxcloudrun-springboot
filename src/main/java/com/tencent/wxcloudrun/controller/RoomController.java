package com.tencent.wxcloudrun.controller;

import com.tencent.wxcloudrun.config.ApiResponse;
import com.tencent.wxcloudrun.config.AuthInterceptor;
import com.tencent.wxcloudrun.config.ErrorCode;
import com.tencent.wxcloudrun.dto.CreateRoomRequest;
import com.tencent.wxcloudrun.dto.JoinRoomRequest;
import com.tencent.wxcloudrun.dto.ReadyRequest;
import com.tencent.wxcloudrun.dto.RoomBriefVo;
import com.tencent.wxcloudrun.dto.RoomConfigRequest;
import com.tencent.wxcloudrun.dto.RoomIdRequest;
import com.tencent.wxcloudrun.dto.RoomVo;
import com.tencent.wxcloudrun.dto.StartRequest;
import com.tencent.wxcloudrun.exception.BizException;
import com.tencent.wxcloudrun.service.GameService;
import com.tencent.wxcloudrun.service.RoomService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 大厅与房间接口，对应前端 api-room.md
 */
@RestController
@RequestMapping("/v1/rooms")
public class RoomController {

  final RoomService roomService;
  final GameService gameService;

  public RoomController(@Autowired RoomService roomService,
                        @Autowired GameService gameService) {
    this.roomService = roomService;
    this.gameService = gameService;
  }

  /**
   * 创建房间
   */
  @PostMapping
  public ApiResponse create(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                            @RequestBody(required = false) CreateRoomRequest request) {
    RoomVo room = roomService.create(userId, request == null ? null : request.getConfig());
    return ApiResponse.ok(roomData(room));
  }

  /**
   * 用房号加入
   */
  @PostMapping("/join")
  public ApiResponse join(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                          @RequestBody(required = false) JoinRoomRequest request) {
    if (request == null || request.getCode() == null || request.getCode().trim().isEmpty()) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "缺少房号");
    }
    return ApiResponse.ok(roomData(roomService.join(userId, request.getCode().trim())));
  }

  /**
   * 离开房间，最后一人离开返回 room: null
   */
  @PostMapping("/leave")
  public ApiResponse leave(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                           @RequestBody(required = false) RoomIdRequest request) {
    Long roomId = requireRoomId(request == null ? null : request.getRoomId());
    return ApiResponse.ok(roomData(roomService.leave(userId, roomId)));
  }

  /**
   * 准备 / 取消准备
   */
  @PostMapping("/ready")
  public ApiResponse ready(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                           @RequestBody(required = false) ReadyRequest request) {
    if (request == null) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), ErrorCode.PARAM_ERROR.getMessage());
    }
    Long roomId = requireRoomId(request.getRoomId());
    boolean ready = request.getReady() != null && request.getReady();
    return ApiResponse.ok(roomData(roomService.setReady(userId, roomId, ready)));
  }

  /**
   * 房主改规则（不开始对局）
   */
  @PostMapping("/config")
  public ApiResponse updateConfig(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                                  @RequestBody(required = false) RoomConfigRequest request) {
    if (request == null) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), ErrorCode.PARAM_ERROR.getMessage());
    }
    Long roomId = requireRoomId(request.getRoomId());
    return ApiResponse.ok(roomData(roomService.updateConfig(userId, roomId, request.getConfig())));
  }

  /**
   * 房主开局
   *
   * 房间状态置为 playing 之后紧接着发牌：整局对局状态由 GameService 持有，
   * 这里把发牌结果一起回给房主，其他人靠 WS 的 game.state / game.hand 拿到。
   */
  @PostMapping("/start")
  public ApiResponse start(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                           @RequestBody(required = false) StartRequest request) {
    if (request == null) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), ErrorCode.PARAM_ERROR.getMessage());
    }
    Long roomId = requireRoomId(request.getRoomId());
    RoomVo room = roomService.start(userId, roomId, request.getConfig());

    Map<String, Object> data = roomData(room);
    data.put("game", gameService.startByRoom(roomId));
    return ApiResponse.ok(data);
  }

  /**
   * 房间详情（轮询）；roomId 支持 query 参数，也兼容 GET 带 body
   */
  @GetMapping("/detail")
  public ApiResponse detail(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                            @RequestParam(required = false) String roomId,
                            @RequestBody(required = false) RoomIdRequest body) {
    String raw = roomId != null && !roomId.isEmpty() ? roomId : (body == null ? null : body.getRoomId());
    return ApiResponse.ok(roomData(roomService.detail(userId, requireRoomId(raw))));
  }

  /**
   * 我所在的房间
   */
  @GetMapping("/mine")
  public ApiResponse mine(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
    return ApiResponse.ok(roomData(roomService.mine(userId)));
  }

  /**
   * 可加入的房间列表
   */
  @GetMapping("/list")
  public ApiResponse list(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
    List<RoomBriefVo> rooms = roomService.list(userId);
    Map<String, Object> data = new HashMap<>(2);
    data.put("rooms", rooms);
    return ApiResponse.ok(data);
  }

  private Map<String, Object> roomData(RoomVo room) {
    Map<String, Object> data = new HashMap<>(2);
    data.put("room", room);
    return data;
  }

  /**
   * "r_101" -> 101，非法输入按参数错误处理
   */
  private Long requireRoomId(String roomId) {
    if (roomId == null || roomId.trim().isEmpty()) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "缺少 roomId");
    }
    String value = roomId.trim();
    if (value.startsWith("r_")) {
      value = value.substring(2);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "roomId 格式错误");
    }
  }
}
