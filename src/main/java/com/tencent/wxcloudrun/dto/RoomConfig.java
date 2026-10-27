package com.tencent.wxcloudrun.dto;

import lombok.Data;

import java.util.Arrays;
import java.util.List;

/**
 * 房间规则，与前端 RoomConfig 对齐
 */
@Data
public class RoomConfig {

  private Integer playerCount = 4;
  private Integer deckCount = 1;
  private Boolean withJokers = true;
  private List<String> order = defaultOrder();

  public static List<String> defaultOrder() {
    return Arrays.asList("7", "JOKER", "5", "2", "3", "A", "K", "Q", "J", "10", "9", "8", "6", "4");
  }
}
