package com.tencent.wxcloudrun.model;

import lombok.Data;

import java.io.Serializable;

/**
 * 对局里的一个玩家（本局每人一条）
 */
@Data
public class GamePlayer implements Serializable {

  private Long id;
  private Long gameId;
  private Long userId;
  private Integer seat;
  /** 初始手牌，逗号分隔，复盘用 */
  private String initialCards;
  private Integer pickedScore;
  private Integer rankNo;
  private Integer scoreChange;
  private Integer isWin;
}
