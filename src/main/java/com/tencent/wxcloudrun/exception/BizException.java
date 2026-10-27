package com.tencent.wxcloudrun.exception;

/**
 * 业务异常，会被全局异常处理器转成 ApiResponse
 */
public class BizException extends RuntimeException {

  private final int code;

  public BizException(String message) {
    super(message);
    this.code = -1;
  }

  public BizException(int code, String message) {
    super(message);
    this.code = code;
  }

  public int getCode() {
    return code;
  }
}
