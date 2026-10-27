package com.tencent.wxcloudrun.dto;

import lombok.Data;

import java.util.List;

/**
 * 出牌：POST /v1/games/play
 */
@Data
public class PlayRequest {

  private String gameId;
  /** 牌编码，如 ["7S", "7H"] */
  private List<String> cards;
}
