package com.tencent.wxcloudrun.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class UserLoginLog implements Serializable {

  private Long id;
  private Long userId;
  private String action;
  private Integer result;
  private Integer failCode;
  private String ip;
  private String device;
  private String channel;
  private Integer scene;
  private LocalDateTime createdAt;
}
