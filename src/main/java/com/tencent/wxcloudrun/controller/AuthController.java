package com.tencent.wxcloudrun.controller;

import com.tencent.wxcloudrun.config.ApiResponse;
import com.tencent.wxcloudrun.config.AuthInterceptor;
import com.tencent.wxcloudrun.config.ErrorCode;
import com.tencent.wxcloudrun.dao.UserMapper;
import com.tencent.wxcloudrun.dto.LoginRequest;
import com.tencent.wxcloudrun.dto.ProfileRequest;
import com.tencent.wxcloudrun.dto.RefreshRequest;
import com.tencent.wxcloudrun.dto.TokenVo;
import com.tencent.wxcloudrun.dto.UserVo;
import com.tencent.wxcloudrun.exception.BizException;
import com.tencent.wxcloudrun.model.User;
import com.tencent.wxcloudrun.model.UserSession;
import com.tencent.wxcloudrun.service.AuthService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.servlet.http.HttpServletRequest;
import java.util.HashMap;
import java.util.Map;

/**
 * 登录认证接口，对应前端 api-login.md
 */
@RestController
@RequestMapping("/v1/auth")
public class AuthController {

  final AuthService authService;
  final UserMapper userMapper;
  final Logger logger;

  public AuthController(@Autowired AuthService authService, @Autowired UserMapper userMapper) {
    this.authService = authService;
    this.userMapper = userMapper;
    this.logger = LoggerFactory.getLogger(AuthController.class);
  }

  /**
   * 登录：code 换 token
   */
  @PostMapping("/login")
  public ApiResponse login(@RequestBody(required = false) LoginRequest request,
                           HttpServletRequest httpRequest) {
    if (request == null) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), ErrorCode.PARAM_ERROR.getMessage());
    }
    TokenVo tokenVo = authService.login(request, clientIp(httpRequest), httpRequest.getHeader("X-Client-Version"));
    Map<String, Object> data = new HashMap<>(8);
    data.put("accessToken", tokenVo.getAccessToken());
    data.put("refreshToken", tokenVo.getRefreshToken());
    data.put("expiresIn", tokenVo.getExpiresIn());
    data.put("refreshExpiresIn", tokenVo.getRefreshExpiresIn());
    data.put("user", tokenVo.getUser());
    data.put("serverTime", tokenVo.getServerTime());
    return ApiResponse.ok(data);
  }

  /**
   * 刷新登录态
   */
  @PostMapping("/refresh")
  public ApiResponse refresh(@RequestBody(required = false) RefreshRequest request,
                             HttpServletRequest httpRequest) {
    if (request == null || request.getRefreshToken() == null || request.getRefreshToken().trim().isEmpty()) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "缺少 refreshToken");
    }
    TokenVo tokenVo = authService.refresh(request.getRefreshToken(), clientIp(httpRequest));
    Map<String, Object> data = new HashMap<>(8);
    data.put("accessToken", tokenVo.getAccessToken());
    data.put("refreshToken", tokenVo.getRefreshToken());
    data.put("expiresIn", tokenVo.getExpiresIn());
    data.put("refreshExpiresIn", tokenVo.getRefreshExpiresIn());
    data.put("serverTime", tokenVo.getServerTime());
    return ApiResponse.ok(data);
  }

  /**
   * 登出
   */
  @PostMapping("/logout")
  public ApiResponse logout(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                            @RequestAttribute(AuthInterceptor.ATTR_ACCESS_TOKEN) String accessToken) {
    authService.logout(userId, accessToken);
    Map<String, Object> data = new HashMap<>(2);
    data.put("success", true);
    return ApiResponse.ok(data);
  }

  /**
   * 校验登录态（探活）
   */
  @GetMapping("/session")
  public ApiResponse session(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                             @RequestAttribute(AuthInterceptor.ATTR_SESSION) UserSession session) {
    User user = userMapper.selectById(userId);
    Map<String, Object> data = new HashMap<>(4);
    data.put("userId", "u_" + userId);
    data.put("expiresIn", authService.remainingSeconds(session));
    data.put("user", UserVo.from(user, false));
    return ApiResponse.ok(data);
  }

  /**
   * 上报头像昵称（授权后解密补全）
   */
  @PostMapping("/profile")
  public ApiResponse profile(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId,
                             @RequestBody(required = false) ProfileRequest request) {
    UserVo vo = authService.updateProfile(userId, request);
    return ApiResponse.ok(vo);
  }

  /**
   * 注销账号
   */
  @PostMapping("/destroy")
  public ApiResponse destroy(@RequestAttribute(AuthInterceptor.ATTR_USER_ID) Long userId) {
    authService.destroy(userId);
    Map<String, Object> data = new HashMap<>(2);
    data.put("success", true);
    return ApiResponse.ok(data);
  }

  private String clientIp(HttpServletRequest request) {
    String ip = request.getHeader("X-Forwarded-For");
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
      ip = request.getHeader("X-Real-IP");
    }
    if (ip == null || ip.isEmpty() || "unknown".equalsIgnoreCase(ip)) {
      ip = request.getRemoteAddr();
    }
    if (ip != null && ip.contains(",")) {
      ip = ip.substring(0, ip.indexOf(',')).trim();
    }
    return ip == null ? "" : ip;
  }
}
