package com.tencent.wxcloudrun.dto;

import lombok.Data;

/**
 * 创建房间：POST /v1/rooms
 */
@Data
public class CreateRoomRequest {

  private RoomConfig config;
}
