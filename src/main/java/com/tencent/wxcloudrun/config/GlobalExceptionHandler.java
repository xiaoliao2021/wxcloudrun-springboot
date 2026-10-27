package com.tencent.wxcloudrun.config;

import com.tencent.wxcloudrun.exception.BizException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/**
 * 统一异常处理，业务异常转成 ApiResponse，避免直接抛 500
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

  private final Logger logger = LoggerFactory.getLogger(GlobalExceptionHandler.class);

  @ExceptionHandler(BizException.class)
  public ApiResponse handleBizException(BizException e) {
    logger.warn("biz exception, code={}, msg={}", e.getCode(), e.getMessage());
    ApiResponse resp = ApiResponse.error(ErrorCode.SERVER_ERROR, e.getMessage());
    resp.setCode(e.getCode());
    return resp;
  }

  @ExceptionHandler(Exception.class)
  public ApiResponse handleException(Exception e) {
    logger.error("unexpected error", e);
    return ApiResponse.error(ErrorCode.SERVER_ERROR);
  }
}
