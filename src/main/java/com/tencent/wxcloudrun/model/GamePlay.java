package com.tencent.wxcloudrun.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 一次出牌 / 过牌记录
 */
@Data
public class GamePlay implements Serializable {

  public static final int ACTION_PASS = 0;
  public static final int ACTION_PLAY = 1;

  private Long id;
  private Long gameId;
  private Integer trickNo;
  private Integer seqNo;
  private Long userId;
  private Integer action;
  private Integer cardType;
  private String mainToken;
  private String cards;
  private Integer cardsCount;
}
