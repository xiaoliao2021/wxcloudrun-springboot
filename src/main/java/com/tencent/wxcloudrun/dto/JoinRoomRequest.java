package com.tencent.wxcloudrun.dto;

import lombok.Data;

/**
 * 加入房间：POST /v1/rooms/join
 */
@Data
public class JoinRoomRequest {

  /** 6 位房号 */
  private String code;
}
