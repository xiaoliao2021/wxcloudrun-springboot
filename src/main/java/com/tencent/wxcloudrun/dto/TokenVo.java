package com.tencent.wxcloudrun.dto;

import lombok.Data;

/**
 * 登录 / 刷新 返回的令牌
 */
@Data
public class TokenVo {

  private String accessToken;
  private String refreshToken;
  private long expiresIn;
  private long refreshExpiresIn;
  /** 登录接口才有 */
  private UserVo user;
  private long serverTime;
}
