package com.tencent.wxcloudrun.controller;

import com.tencent.wxcloudrun.config.ApiResponse;
import com.tencent.wxcloudrun.config.AuthInterceptor;
import com.tencent.wxcloudrun.config.ErrorCode;
import com.tencent.wxcloudrun.dto.GameIdRequest;
import com.tencent.wxcloudrun.dto.PlayRequest;
import com.tencent.wxcloudrun.exception.BizException;
import com.tencent.wxcloudrun.service.GameService;
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
 * 对局接口，对应前端 docs/api-game.md
 *
 * 只有写操作在这里：出牌、要不起。对局状态一律走 WS 推送。
 */
@RestController
@RequestMapping("/v1/games")
public class GameController {

  final GameService gameService;

  public GameController(@Autowired GameService gameService) {
    this.gameService = gameService;
  }

  /**
   * 出牌
   */
  @PostMapping("/play")
  public ApiResponse play(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                          @RequestBody(required = false) PlayRequest request) {
    if (request == null || isBlank(request.getGameId())) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "缺少 gameId");
    }
    gameService.play(userId, request.getGameId(), request.getCards());

    Map<String, Object> data = new HashMap<>(2);
    data.put("accepted", true);
    return ApiResponse.ok(data);
  }

  /**
   * 要不起
   */
  @PostMapping("/pass")
  public ApiResponse pass(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                          @RequestBody(required = false) GameIdRequest request) {
    if (request == null || isBlank(request.getGameId())) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "缺少 gameId");
    }
    gameService.pass(userId, request.getGameId());

    Map<String, Object> data = new HashMap<>(2);
    data.put("accepted", true);
    return ApiResponse.ok(data);
  }

  /**
   * 当前对局（断线重连 / 冷启动）
   * 不在对局中时 game 为 null，不算错误
   */
  @GetMapping("/current")
  public ApiResponse current(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
    Map<String, Object> data = new HashMap<>(4);
    data.put("game", gameService.stateOfUser(userId));
    List<String> hand = gameService.handOfUser(userId);
    data.put("hand", hand);
    return ApiResponse.ok(data);
  }

  /**
   * 我在这个房间「正在进行的那一局」里是什么身份
   *
   * 房间页点「观战」时先问一下：seat 为 null 就是不在这一局（没赶上开局），直接观战；
   * seat.canRejoin 为 true 说明我退过局、现在由托管代打，应该问一句要不要回到对局。
   */
  @GetMapping("/mine")
  public ApiResponse mine(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                          @RequestParam(required = false) String roomId) {
    Map<String, Object> data = new HashMap<>(2);
    data.put("seat", gameService.mySeatInRoom(userId, parseRoomId(roomId)));
    return ApiResponse.ok(data);
  }

  /**
   * 回归对局：把退局时打上的 left / autoHost / quit 清掉，收回本人操作。
   * 只对「还在进行、座位仍在」的对局有效，结束后会返回 5101。
   */
  @PostMapping("/rejoin")
  public ApiResponse rejoin(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                            @RequestBody(required = false) GameIdRequest request) {
    if (request == null || isBlank(request.getGameId())) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "缺少 gameId");
    }
    gameService.rejoin(userId, request.getGameId());

    Map<String, Object> data = new HashMap<>(2);
    data.put("accepted", true);
    return ApiResponse.ok(data);
  }

  /** "r_19" / "19" -> 19；空或非法返回 null（由服务层当作「没有对局」处理） */
  private Long parseRoomId(String roomId) {
    if (isBlank(roomId)) {
      return null;
    }
    String value = roomId.trim();
    if (value.startsWith("r_")) {
      value = value.substring(2);
    }
    try {
      return Long.parseLong(value);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  private boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  /**
   * 离开对局（返回大厅 / 切后台后不再继续）：标记该玩家「已离开 + 托管」，
   * 对局由服务端代打继续，不会因一人离开而解散。
   */
  @PostMapping("/leave")
  public ApiResponse leave(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                           @RequestBody(required = false) GameIdRequest request) {
    if (request == null || isBlank(request.getGameId())) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "缺少 gameId");
    }
    gameService.leave(userId, request.getGameId());

    Map<String, Object> data = new HashMap<>(2);
    data.put("accepted", true);
    return ApiResponse.ok(data);
  }
}
