package com.tencent.wxcloudrun.config;

import lombok.Data;

import java.util.HashMap;

/**
 * 统一响应体：{ code, message, data, traceId, serverTime }
 * traceId / serverTime 由响应包装器统一填充
 */
@Data
public final class ApiResponse {

  private int code;
  private String message;
  private Object data;
  private String traceId;
  private long serverTime;

  private ApiResponse(int code, String message, Object data) {
    this.code = code;
    this.message = message;
    this.data = data;
  }

  public static ApiResponse ok() {
    return new ApiResponse(ErrorCode.SUCCESS.getCode(), "ok", new HashMap<>());
  }

  public static ApiResponse ok(Object data) {
    return new ApiResponse(ErrorCode.SUCCESS.getCode(), "ok", data);
  }

  public static ApiResponse error(ErrorCode errorCode) {
    return new ApiResponse(errorCode.getCode(), errorCode.getMessage(), new HashMap<>());
  }

  public static ApiResponse error(ErrorCode errorCode, String message) {
    return new ApiResponse(errorCode.getCode(), message, new HashMap<>());
  }
}
