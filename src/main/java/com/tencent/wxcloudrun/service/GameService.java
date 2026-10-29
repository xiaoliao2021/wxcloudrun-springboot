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

  /**
   * 主动离开对局（彻底出局）：把该玩家标记为「已离开 + 托管 + 出局」，由服务端代打。
   * 若该玩家当前正轮到，则立即代打并推动后续。对局仍继续进行（不会解散）。
   *
   * 出局后该玩家不能再回来：`/v1/games/current` 不再返回这局，play/pass 也会被拒。
   * 「返回对局」只用于被动中断（杀进程 / 切后台 / 浮窗）时接回自己那局。
   */
  void leave(Long userId, String gameId);

  /**
   * 按房间把玩家从进行中的对局里踢出局（退出房间时兜底，避免该玩家的回合没人打而卡住）
   */
  void quitByRoom(Long userId, Long roomId);

  /**
   * 我在这个房间「正在进行的那一局」里的座位情况，给房间页「观战」按钮判断要不要问一句「回到对局」。
   *
   * 返回 `{gameId, seat, quit, autoHost, left, finished, canRejoin}`；
   * 该房间没有进行中的对局、或者我压根不在座位上，返回 null。
   *
   * `canRejoin` 为 true 的条件：退过局（quit）+ 牌还没出完。查询入口是 /v1/games/current，
   * 它故意跳过 quit 的座位，所以这里单独开一个「含已退局座位」的查询。
   */
  Map<String, Object> mySeatInRoom(Long userId, Long roomId);

  /**
   * 回归对局：把 leave() 打的三个标记（left / autoHost / quit）清掉，收回本人操作。
   *
   * 只允许回到「还在进行、且座位仍然保留」的那一局 —— 退局期间由托管代打，
   * 手牌是真实的剩余牌，回来接着打。对局已经结束会抛 5101。
   */
  void rejoin(Long userId, String gameId);
}
