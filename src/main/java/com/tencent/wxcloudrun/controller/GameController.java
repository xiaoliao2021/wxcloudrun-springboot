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

  private boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }
}
