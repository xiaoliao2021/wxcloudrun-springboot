package com.tencent.wxcloudrun.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wxcloudrun.dao.UserMapper;
import com.tencent.wxcloudrun.model.User;
import com.tencent.wxcloudrun.model.UserSession;
import com.tencent.wxcloudrun.service.AuthService;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.HashMap;
import java.util.Map;

/**
 * 校验 accessToken，通过后把 userId / session 放进 request attribute
 */
@Component
public class AuthInterceptor implements HandlerInterceptor {

  public static final String ATTR_USER_ID = "userId";
  public static final String ATTR_SESSION = "userSession";
  public static final String ATTR_ACCESS_TOKEN = "accessToken";

  private final AuthService authService;
  private final UserMapper userMapper;

  public AuthInterceptor(AuthService authService, UserMapper userMapper) {
    this.authService = authService;
    this.userMapper = userMapper;
  }

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
      throws Exception {
    if ("OPTIONS".equalsIgnoreCase(request.getMethod())) {
      return true;
    }
    String token = resolveToken(request);
    UserSession session = authService.getSession(token);
    if (session == null) {
      writeError(request, response, ErrorCode.ACCESS_TOKEN_INVALID);
      return false;
    }
    User user = userMapper.selectById(session.getUserId());
    if (user == null || (user.getStatus() != null && user.getStatus() != 1)) {
      writeError(request, response, ErrorCode.KICKED_OFFLINE);
      return false;
    }
    request.setAttribute(ATTR_USER_ID, session.getUserId());
    request.setAttribute(ATTR_SESSION, session);
    request.setAttribute(ATTR_ACCESS_TOKEN, token);
    return true;
  }

  private String resolveToken(HttpServletRequest request) {
    String token = request.getHeader("Authorization");
    if (token == null || token.isEmpty()) {
      token = request.getHeader("token");
    }
    if (token != null && token.startsWith("Bearer ")) {
      token = token.substring(7);
    }
    return token;
  }

  private void writeError(HttpServletRequest request, HttpServletResponse response, ErrorCode errorCode)
      throws Exception {
    Object traceId = request.getAttribute(TraceInterceptor.ATTR_TRACE_ID);
    Map<String, Object> body = new java.util.LinkedHashMap<>(8);
    body.put("code", errorCode.getCode());
    body.put("message", errorCode.getMessage());
    body.put("data", new HashMap<>());
    body.put("traceId", traceId == null ? "" : String.valueOf(traceId));
    body.put("serverTime", System.currentTimeMillis());
    response.setStatus(HttpServletResponse.SC_OK);
    response.setContentType("application/json;charset=UTF-8");
    response.getWriter().write(new ObjectMapper().writeValueAsString(body));
  }
}
