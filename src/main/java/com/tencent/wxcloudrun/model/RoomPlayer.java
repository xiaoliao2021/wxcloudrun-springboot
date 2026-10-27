package com.tencent.wxcloudrun.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

@Data
public class RoomPlayer implements Serializable {

  private Long id;
  private Long roomId;
  private Long userId;
  private Integer seat;
  private Integer isOwner;
  private Integer isReady;
  private Integer isOnline;
  private Integer totalScore;
  private LocalDateTime joinAt;
  private LocalDateTime leaveAt;
}
