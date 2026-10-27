package com.tencent.wxcloudrun.dto;

import lombok.Data;

/**
 * 准备 / 取消准备：POST /v1/rooms/ready
 */
@Data
public class ReadyRequest {

  private String roomId;
  private Boolean ready;
}
