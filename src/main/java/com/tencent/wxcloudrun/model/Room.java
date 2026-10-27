package com.tencent.wxcloudrun.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class Room implements Serializable {

  private Long id;
  private String roomNo;
  private Long ownerUserId;
  private Integer source;
  /** 0 等待中(lobby) 1 已开局(playing) 2 已结束 3 已解散 */
  private Integer status;
  private Integer maxPlayer;
  private Integer totalRound;
  private Integer currentRound;
  private Integer baseScore;
  /** 房间规则 JSON */
  private String options;
  /** 房间级版本号，每次状态变化 +1（WS 推送用它做乱序/丢帧判断） */
  private Long revision;
  private LocalDateTime expireAt;
  private LocalDateTime createdAt;
  private LocalDateTime updatedAt;
}
