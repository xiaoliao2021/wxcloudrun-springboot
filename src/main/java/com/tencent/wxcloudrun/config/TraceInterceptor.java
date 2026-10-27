package com.tencent.wxcloudrun.config;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.util.UUID;

/**
 * 链路追踪 ID：优先透传客户端的 X-Trace-Id，缺失则生成
 */
@Component
public class TraceInterceptor implements HandlerInterceptor {

  public static final String ATTR_TRACE_ID = "traceId";

  @Override
  public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
    String traceId = request.getHeader("X-Trace-Id");
    if (traceId == null || traceId.trim().isEmpty()) {
      traceId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }
    request.setAttribute(ATTR_TRACE_ID, traceId);
    response.setHeader("X-Trace-Id", traceId);
    return true;
  }
}
