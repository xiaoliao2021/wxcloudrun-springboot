package com.tencent.wxcloudrun.dto;

import lombok.Data;

/**
 * 房主改规则：POST /v1/rooms/config
 *
 * 和 start 的区别：这个只改配置、不开始，改完 WS 会推一次 room.state(reason=config)，
 * 房间里其他人立刻能看到新规则。开始仍然走 /v1/rooms/start。
 */
@Data
public class RoomConfigRequest {

  private String roomId;
  private RoomConfig config;
}
