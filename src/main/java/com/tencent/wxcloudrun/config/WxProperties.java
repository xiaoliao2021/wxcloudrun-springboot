package com.tencent.wxcloudrun.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * 微信小游戏配置（appid / secret 从环境变量注入）
 */
@Data
@Component
@ConfigurationProperties(prefix = "wx")
public class WxProperties {

  /** 小游戏 appid */
  private String appid = "";

  /** 小游戏 appsecret */
  private String secret = "";

  /** accessToken 有效期（秒），默认 2 小时 */
  private long accessExpireSeconds = 7200;

  /** refreshToken 有效期（秒），默认 30 天 */
  private long refreshExpireSeconds = 2592000;
}
