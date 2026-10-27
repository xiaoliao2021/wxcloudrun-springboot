package com.tencent.wxcloudrun.dto;

import lombok.Data;

/**
 * 房主开局：POST /v1/rooms/start
 */
@Data
public class StartRequest {

  private String roomId;
  private RoomConfig config;
}
