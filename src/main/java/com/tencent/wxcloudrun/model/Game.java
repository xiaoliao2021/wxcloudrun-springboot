package com.tencent.wxcloudrun.model;

import lombok.Data;

import java.io.Serializable;
import java.time.LocalDateTime;

/**
 * 一整局对局（发牌到牌堆摸完）
 */
@Data
public class Game implements Serializable {

  public static final int STATUS_DEALING = 0;
  public static final int STATUS_PLAYING = 1;
  public static final int STATUS_SETTLED = 2;
  public static final int STATUS_ABORTED = 3;

  private Long id;
  private Long roomId;
  private Integer roundNo;
  private Integer status;
  private Integer ruleType;
  private Long dealerUserId;
  private Long winnerUserId;
  private String deckSeed;
  private String deckRemain;
  private LocalDateTime startedAt;
  private LocalDateTime endedAt;
}
