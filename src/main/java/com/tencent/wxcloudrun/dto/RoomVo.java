package com.tencent.wxcloudrun.dto;

import lombok.Data;

import java.util.List;

@Data
public class RoomVo {

  private String roomId;
  private String code;
  private String ownerId;
  /** lobby / playing */
  private String status;
  private RoomConfig config;
  private List<RoomPlayerVo> players;
  private long updatedAt;
  /** 房间级版本号，房间每次状态变化 +1 */
  private long revision;
}
