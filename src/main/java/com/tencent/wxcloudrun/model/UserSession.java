package com.tencent.wxcloudrun.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class UserSession implements Serializable {

  private Long id;
  private Long userId;
  private String accessTokenHash;
  private String refreshTokenHash;
  private LocalDateTime accessExpireAt;
  private LocalDateTime refreshExpireAt;
  private Integer status;
  private String ip;
  private String device;
  private String clientVersion;
  private LocalDateTime lastAccessAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
