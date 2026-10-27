package com.tencent.wxcloudrun.dto;

import lombok.Data;

/**
 * 刷新登录态请求：POST /v1/auth/refresh
 */
@Data
public class RefreshRequest {

  private String refreshToken;
}
