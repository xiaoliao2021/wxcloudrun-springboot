package com.tencent.wxcloudrun.config;

import org.springframework.core.MethodParameter;
import org.springframework.http.MediaType;
import org.springframework.http.server.ServerHttpRequest;
import org.springframework.http.server.ServerHttpResponse;
import org.springframework.http.server.ServletServerHttpRequest;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.servlet.mvc.method.annotation.ResponseBodyAdvice;

/**
 * 统一给 ApiResponse 补 traceId 与 serverTime
 */
@ControllerAdvice
public class ResponseWrapper implements ResponseBodyAdvice<Object> {

  @Override
  public boolean supports(MethodParameter returnType, Class converterType) {
    return true;
  }

  @Override
  public Object beforeBodyWrite(Object body, MethodParameter returnType, MediaType selectedContentType,
                                Class selectedConverterType, ServerHttpRequest request, ServerHttpResponse response) {
    if (!(body instanceof ApiResponse)) {
      return body;
    }
    ApiResponse resp = (ApiResponse) body;
    Object traceId = null;
    if (request instanceof ServletServerHttpRequest) {
      traceId = ((ServletServerHttpRequest) request).getServletRequest()
          .getAttribute(TraceInterceptor.ATTR_TRACE_ID);
    }
    resp.setTraceId(traceId == null ? "" : String.valueOf(traceId));
    resp.setServerTime(System.currentTimeMillis());
    return resp;
  }
}
