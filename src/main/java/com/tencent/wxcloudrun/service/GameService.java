package com.tencent.wxcloudrun.service;

import java.util.List;
import java.util.Map;

/**
 * 对局服务：发牌、出牌、过牌、收轮、补牌、结算。
 *
 * 状态存内存（一个房间同时只有一局），库里只写 game / game_player / game_play 留档。
 */
public interface GameService {

  /**
   * 房间开局：洗牌发牌，广播 game.state(dealt) 并私发各自手牌
   *
   * @return game.state 的 data（便于 HTTP 直接回给房主）
   */
  Map<String, Object> startByRoom(Long roomId);

  /**
   * 出牌
   *
   * @param cards 牌编码，如 ["7S", "7H"]
   */
  void play(Long userId, String gameId, List<String> cards);

  /**
   * 要不起
   */
  void pass(Long userId, String gameId);

  /**
   * 我当前所在对局的公共状态，没有则 null（断线重连 / 冷启动时用）
   */
  Map<String, Object> stateOfUser(Long userId);

  /**
   * 我当前所在对局的手牌，没有对局返回 null
   */
  List<String> handOfUser(Long userId);

  /**
   * 我当前所在对局的手牌帧（game.hand 的 data），重连补发用
   */
  Map<String, Object> handViewOfUser(Long userId);

  /**
   * 房间的对局是否还在进行（房间被解散 / 对局异常时用来兜底）
   */
  boolean hasActiveGame(Long roomId);
}
