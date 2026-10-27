package com.tencent.wxcloudrun.dto;

import lombok.Data;

/**
 * 只带 roomId 的请求：leave / detail
 */
@Data
public class RoomIdRequest {

  private String roomId;
}
