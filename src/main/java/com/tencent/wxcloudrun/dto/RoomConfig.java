package com.tencent.wxcloudrun.dto;

import lombok.Data;

import java.util.Arrays;
import java.util.List;

/**
 * 房间规则，与前端 RoomConfig 对齐
 *
 * 默认值与前端 DEFAULT_CONFIG 一致：32AK 顺序 + 无鬼。
 * 两边必须同步改，否则会出现「前端显示一套、服务端发出一套」。
 *
 * 注意：字段**故意不给初始值**，全交给 RoomServiceImpl#normalize 兜底。
 * 给了初始值的话，Jackson 反序列化后 getWithJokers() 永远不为 null，
 * 就分不出「客户端没传」和「客户端显式传了 false」，没法按 order 推断含不含鬼。
 */
@Data
public class RoomConfig {

  private Integer playerCount;
  private Integer deckCount;
  private Boolean withJokers;
  private List<String> order;

  /** 默认顺序：32AK 常规（王最大） */
  public static List<String> defaultOrder() {
    return Arrays.asList("JOKER", "3", "2", "A", "K", "Q", "J", "10", "9", "8", "7", "6", "5", "4");
  }
}
