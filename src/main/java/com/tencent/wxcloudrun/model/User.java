package com.tencent.wxcloudrun.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class User implements Serializable {

  private Long id;
  private String openid;
  private String unionid;
  private String sessionKey;
  private String nickname;
  private String avatarUrl;
  private Integer gender;
  private Long coin;
  private Integer roomCard;
  private Integer totalGame;
  private Integer winGame;
  private Integer status;
  private LocalDateTime lastLoginAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
