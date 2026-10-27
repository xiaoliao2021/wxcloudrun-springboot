package com.tencent.wxcloudrun.dto;

import lombok.Data;

/**
 * 房间列表条目
 */
@Data
public class RoomBriefVo {

  private String roomId;
  private String code;
  private String ownerId;
  private Integer playerCount;
}
