package com.tencent.wxcloudrun.service.impl;

import com.tencent.wxcloudrun.config.ErrorCode;
import com.tencent.wxcloudrun.dao.GameMapper;
import com.tencent.wxcloudrun.dao.GameRecordMapper;
import com.tencent.wxcloudrun.dto.RoomPlayerVo;
import com.tencent.wxcloudrun.dto.RoomVo;
import com.tencent.wxcloudrun.exception.BizException;
import com.tencent.wxcloudrun.game.GameState;
import com.tencent.wxcloudrun.game.GameView;
import com.tencent.wxcloudrun.model.Game;
import com.tencent.wxcloudrun.model.GamePlay;
import com.tencent.wxcloudrun.model.GamePlayer;
import com.tencent.wxcloudrun.poker.Card;
import com.tencent.wxcloudrun.poker.CardPattern;
import com.tencent.wxcloudrun.poker.RuleBook;
import com.tencent.wxcloudrun.service.GameService;
import com.tencent.wxcloudrun.service.RoomService;
import com.tencent.wxcloudrun.ws.WsRealtimeBroker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import javax.annotation.PostConstruct;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 对局实现。规则见 docs/api-game.md。
 *
 * 并发：一局的状态全在 GameState 里，所有写入口都 synchronized (state)，
 * 两个人同时点「出牌」也只会有一个人在回合内成功。房间级别不需要额外锁。
 */
@Service
public class GameServiceImpl implements GameService {

  /** 每次发牌手牌张数 */
  private static final int HAND_SIZE = 5;

  private final Logger logger = LoggerFactory.getLogger(GameServiceImpl.class);

  final RoomService roomService;
  final WsRealtimeBroker broker;
  final GameMapper gameMapper;
  final GameRecordMapper gameRecordMapper;

  /** gameId -> 状态 */
  private final Map<Long, GameState> games = new ConcurrentHashMap<>();

  public GameServiceImpl(@Autowired RoomService roomService,
                         @Autowired WsRealtimeBroker broker,
                         @Autowired GameMapper gameMapper,
                         @Autowired GameRecordMapper gameRecordMapper) {
    this.roomService = roomService;
    this.broker = broker;
    this.gameMapper = gameMapper;
    this.gameRecordMapper = gameRecordMapper;
  }

  /**
   * 服务重启时内存里的对局已经没了，把库里仍标记为进行中的对局置为异常终止，
   * 否则这些房间会一直停在 playing 状态没人能再开局。
   */
  @PostConstruct
  public void abortStaleGames() {
    try {
      int n = gameMapper.abortPlaying();
      if (n > 0) {
        logger.warn("启动清理：{} 局上次未结束的对局已标记为异常终止", n);
      }
    } catch (Exception e) {
      logger.warn("启动清理未完成（可忽略）: {}", e.getMessage());
    }
  }

  /* ---------------- 开局 ---------------- */

  @Override
  public Map<String, Object> startByRoom(Long roomId) {
    RoomVo room = roomService.snapshot(roomId);
    if (room == null) {
      throw new BizException(ErrorCode.ROOM_NOT_FOUND.getCode(), ErrorCode.ROOM_NOT_FOUND.getMessage());
    }
    List<RoomPlayerVo> vos = room.getPlayers();
    if (vos == null || vos.size() < 2) {
      throw new BizException(ErrorCode.ROOM_NEED_MORE_PLAYER.getCode(),
          ErrorCode.ROOM_NEED_MORE_PLAYER.getMessage());
    }

    // 每次开局都必须是全新的一局：先把这个房间残留的进行中对局清掉
    // （正常走结算的局已经出内存了，这里兜的是异常残留，避免同一房间挂着两局）
    abortActiveByRoom(roomId);

    RuleBook ruleBook = RuleBook.of(room.getConfig());
    long seed = System.nanoTime();
    List<Card> deck = ruleBook.newDeck();
    Card.shuffle(deck, seed);

    Game game = new Game();
    game.setRoomId(roomId);
    game.setRoundNo(1);
    game.setStatus(Game.STATUS_PLAYING);
    game.setRuleType(1);
    game.setDeckSeed(String.valueOf(seed));
    // deck_remain 列只有 512 字符，多副牌的牌堆塞不下；
    // 重启即异常终止，不需要靠它恢复，这里留空避免截断写入
    game.setDeckRemain("");
    game.setDealerUserId(parseId(vos.get(0).getUserId()));
    try {
      gameMapper.insert(game);
    } catch (Exception e) {
      logger.warn("对局落库失败，仅走内存（不影响本局）: {}", e.getMessage());
    }

    GameState state = new GameState(game.getId() == null ? System.nanoTime() : game.getId(),
        roomId, ruleBook);

    for (int i = 0; i < vos.size(); i++) {
      RoomPlayerVo vo = vos.get(i);
      GameState.Seat seat = new GameState.Seat(i, parseId(vo.getUserId()), vo.getNickname());
      state.getSeats().add(seat);
    }

    // 一人一张轮着发，剩下的进牌堆
    dealHands(state, deck, HAND_SIZE);

    // 手牌按当局顺序从大到小排好，客户端直接渲染；同时找出手牌最小者先出
    Card smallest = null;
    int leader = 0;
    for (GameState.Seat seat : state.getSeats()) {
      List<Card> sorted = ruleBook.sortDesc(seat.getHand());
      seat.getHand().clear();
      seat.getHand().addAll(sorted);

      Card min = ruleBook.smallest(sorted);
      if (min != null && (smallest == null || ruleBook.strength(min) < ruleBook.strength(smallest))) {
        smallest = min;
        leader = seat.getSeat();
      }
    }

    state.setLeaderSeat(leader);
    state.setTurnSeat(leader);
    state.setReason("dealt");
    state.bumpRevision();

    games.put(state.getId(), state);

    persistPlayers(state);
    pushAll(state);

    logger.info("game started, gameId={}, roomId={}, players={}, leaderSeat={}, deck={}",
        state.getId(), roomId, state.playerCount(), leader, state.getDeck().size());

    return GameView.state(state);
  }

  /**
   * 开局前清掉该房间残留的进行中对局（异常终止），保证「每次开局都是全新的一局」。
   *
   * 只改内存 + 把库里的行置为异常终止，不动房间状态——
   * 房间状态由 RoomService.start 管，这里回退会把它刚置成的 playing 又改回 lobby。
   */
  private void abortActiveByRoom(long roomId) {
    for (GameState state : games.values()) {
      if (state.isEnded() || state.getRoomId() != roomId) {
        continue;
      }
      synchronized (state) {
        state.setStatus(GameState.STATUS_ENDED);
        state.setReason("aborted");
        state.bumpRevision();
        try {
          gameMapper.updateStatus(state.getId(), Game.STATUS_ABORTED);
        } catch (Exception e) {
          logger.warn("残留对局置为异常终止失败 gameId={}: {}", state.getId(), e.getMessage());
        }
        pushAll(state);
      }
      games.remove(state.getId());
      logger.warn("开局前清理残留对局 gameId={}, roomId={}", state.getId(), roomId);
    }
  }

  /**
   * 一人一张轮着发：第 i 家拿 index 为 i, i+n, i+2n … 的牌，剩下的留在牌堆
   */
  private void dealHands(GameState state, List<Card> deck, int handSize) {
    int total = state.playerCount();
    for (int i = 0; i < total; i++) {
      GameState.Seat seat = state.getSeats().get(i);
      seat.getHand().clear();
      for (int k = i; k < total * handSize && k < deck.size(); k += total) {
        seat.getHand().add(deck.get(k));
      }
    }
    state.getDeck().clear();
    int dealt = Math.min(deck.size(), total * handSize);
    if (dealt < deck.size()) {
      state.getDeck().addAll(deck.subList(dealt, deck.size()));
    }
  }

  /* ---------------- 出牌 ---------------- */

  @Override
  public void play(Long userId, String gameId, List<String> cards) {
    GameState state = requireGame(gameId);
    synchronized (state) {
      GameState.Seat me = requireTurn(state, userId);
      if (state.getPassed().contains(me.getSeat())) {
        throw new BizException(ErrorCode.GAME_ALREADY_PASSED.getCode(),
            ErrorCode.GAME_ALREADY_PASSED.getMessage());
      }

      List<Card> parsed = parseCards(cards);
      CardPattern pattern = CardPattern.of(parsed);
      if (pattern == null) {
        throw new BizException(ErrorCode.GAME_PATTERN_INVALID.getCode(),
            ErrorCode.GAME_PATTERN_INVALID.getMessage() + "：只能出同点数的 1~4 张");
      }

      // 每张都要在手上，且不能超出持有数量（多副牌时同编码可能有两张）
      List<Card> hand = me.getHand();
      List<Card> remaining = new ArrayList<>(hand);
      for (Card card : parsed) {
        if (!remaining.remove(card)) {
          throw new BizException(ErrorCode.GAME_CARDS_NOT_IN_HAND.getCode(),
              ErrorCode.GAME_CARDS_NOT_IN_HAND.getMessage() + "：" + card.getCode());
        }
      }

      if (state.getTopPlay() != null && !state.getRuleBook().beats(pattern, state.getTopPlay())) {
        throw new BizException(ErrorCode.GAME_CANNOT_BEAT.getCode(),
            ErrorCode.GAME_CANNOT_BEAT.getMessage() + "（需要同牌型同张数且更大）");
      }

      applyPlay(state, me, pattern, parsed);
      afterAction(state);
      drive(state);
      pushAll(state);
    }
  }

  @Override
  public void pass(Long userId, String gameId) {
    GameState state = requireGame(gameId);
    synchronized (state) {
      GameState.Seat me = requireTurn(state, userId);

      if (state.getTopPlay() == null) {
        throw new BizException(ErrorCode.GAME_CANNOT_PASS.getCode(),
            ErrorCode.GAME_CANNOT_PASS.getMessage());
      }
      if (state.getPassed().contains(me.getSeat())) {
        throw new BizException(ErrorCode.GAME_ALREADY_PASSED.getCode(),
            ErrorCode.GAME_ALREADY_PASSED.getMessage());
      }

      applyPass(state, me);
      afterAction(state);
      drive(state);
      pushAll(state);
    }
  }

  /* ---------------- 回合推进 ---------------- */

  /**
   * 出牌的「落状态」部分（不含校验 / 回合推进），play 与代打机器人共用
   */
  private void applyPlay(GameState state, GameState.Seat seat, CardPattern pattern, List<Card> cards) {
    List<Card> hand = seat.getHand();
    // 逐张移除（多副牌同编码可能有多张，只移出被打出的这几张，不能 removeAll 一次删光）
    List<Card> remaining = new ArrayList<>(hand);
    for (Card card : cards) {
      remaining.remove(card);
    }
    hand.clear();
    hand.addAll(remaining);
    if (hand.isEmpty()) {
      seat.setFinished(true);
    }

    // 追加而不是覆盖：同一家一轮里可以出好几手，每一手都要留在桌面上
    state.getTable().add(
        new GameState.PlayEntry(seat.getSeat(), pattern.getCards(), pattern.getType(), false));
    state.getPassed().clear();
    state.setTopPlay(pattern);
    state.setTopSeat(seat.getSeat());
    state.addTrickScore(pattern.score());

    recordPlay(state, seat, GamePlay.ACTION_PLAY, pattern);
    state.setReason("play");
    state.bumpRevision();
  }

  /**
   * 要不起的「落状态」部分（不含校验 / 回合推进），pass 与代打机器人共用
   */
  private void applyPass(GameState state, GameState.Seat seat) {
    state.getPassed().add(seat.getSeat());
    state.getTable().add(
        new GameState.PlayEntry(seat.getSeat(), Collections.emptyList(), 0, true));

    recordPlay(state, seat, GamePlay.ACTION_PASS, null);
    state.setReason("pass");
    state.bumpRevision();
  }

  /**
   * 主动离开对局（彻底出局）：标记该玩家「已离开 + 托管 + 出局」，若正好轮到他则立即代打。
   * 对局继续（不会解散），其他在场玩家照常进行。
   *
   * 出局后 `findActiveByUser` 跳过该座位，`/v1/games/current` 不再返回这局，play/pass 也会被
   * requireTurn 拒掉 —— 所以「回小程序检测对局」那条路回不来。只有房间页的「观战」入口会
   * 先问一句要不要回归（见 rejoin）。
   */
  @Override
  public void leave(Long userId, String gameId) {
    Long id = parseGameId(gameId);
    if (id == null) {
      return;
    }
    GameState state = games.get(id);
    if (state == null || state.isEnded()) {
      return;
    }
    synchronized (state) {
      GameState.Seat seat = state.seatOf(userId == null ? -1L : userId);
      if (seat == null || seat.isQuit()) {
        return;
      }
      seat.setLeft(true);
      seat.setAutoHost(true);
      seat.setQuit(true);
      drive(state);
      pushAll(state);
    }
  }

  /**
   * 按房间把玩家从进行中的对局里踢出局。
   * 退出房间时调用：房间成员表已经把他删了，但内存里的对局座位还在，
   * 不标托管的话轮到他时没人出牌，整局会卡死。
   */
  @Override
  public void quitByRoom(Long userId, Long roomId) {
    if (userId == null || roomId == null) {
      return;
    }
    for (GameState state : games.values()) {
      if (state.isEnded() || state.getRoomId() != roomId) {
        continue;
      }
      synchronized (state) {
        if (state.seatOf(userId) == null) {
          break;
        }
      }
      leave(userId, "g_" + state.getId());
      return;
    }
  }

  /**
   * 我在这个房间进行中对局里的座位情况。
   * 和 findActiveByUser 的区别只有一个：**不跳过 quit 的座位** ——
   * 房间页要在「我已经退局、当前托管代打」时问一句「要不要回到对局」。
   */
  @Override
  public Map<String, Object> mySeatInRoom(Long userId, Long roomId) {
    if (userId == null || roomId == null) {
      return null;
    }
    for (GameState state : games.values()) {
      if (state.isEnded() || state.getRoomId() != roomId) {
        continue;
      }
      GameState.Seat seat = state.seatOf(userId);
      if (seat == null) {
        continue;
      }
      Map<String, Object> m = new LinkedHashMap<>(8);
      m.put("gameId", "g_" + state.getId());
      m.put("roomId", "r_" + state.getRoomId());
      m.put("seat", seat.getSeat());
      m.put("quit", seat.isQuit());
      m.put("autoHost", seat.isAutoHost());
      m.put("left", seat.isLeft());
      m.put("finished", seat.isFinished());
      // 牌已经出完就没必要问「回来接着打」了，让他安心观战等结算
      m.put("canRejoin", seat.isQuit() && !seat.isFinished());
      return m;
    }
    return null;
  }

  /**
   * 回归对局：清掉 leave() 打上的三个标记，把控制权还给本人。
   *
   * 退局期间是托管在代打，手牌就是真实的剩余牌，所以回来接着打不需要重发。
   * 推一次全量，其他人那边的「托管中」标记也会同步消失。
   */
  @Override
  public void rejoin(Long userId, String gameId) {
    Long id = parseGameId(gameId);
    if (id == null) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "缺少 gameId");
    }
    GameState state = games.get(id);
    if (state == null || state.isEnded()) {
      throw new BizException(ErrorCode.GAME_NOT_FOUND.getCode(), "对局已结束，回不到这一局了");
    }
    synchronized (state) {
      GameState.Seat seat = state.seatOf(userId == null ? -1L : userId);
      if (seat == null) {
        throw new BizException(ErrorCode.GAME_NOT_FOUND.getCode(), "你不在这一局里");
      }
      if (seat.isFinished()) {
        throw new BizException(ErrorCode.GAME_NOT_FOUND.getCode(), "你的牌已经出完了，等这局结算吧");
      }
      seat.setLeft(false);
      seat.setAutoHost(false);
      seat.setQuit(false);
      // 换一个 reason，客户端就不会把它当成 dealt 去重放开场动画
      state.setReason("rejoin");
      state.bumpRevision();
      pushAll(state);
    }
  }

  /**
   * 轮到「已离开 + 托管」的玩家时，由服务端代打，循环推进直到轮到在场玩家或本局结束。
   *
   * 在 play / pass / leave 的 synchronized 块内调用，直接改状态、不重复加锁。
   * 用带上限的循环而非递归，避免长串「全员离开」连锁把栈打爆。
   */
  private void drive(GameState state) {
    int guard = 0;
    while (!state.isEnded()) {
      GameState.Seat seat = state.seatAt(state.getTurnSeat());
      if (seat == null || seat.isFinished() || !seat.isLeft() || !seat.isAutoHost()) {
        break;
      }
      BotMove move = decideBot(state, seat);
      if (move.pass) {
        applyPass(state, seat);
      } else {
        CardPattern pattern = CardPattern.of(move.cards);
        if (pattern == null) {
          // 理论上不会发生（decideBot 只产出合法牌），兜底过牌
          applyPass(state, seat);
        } else {
          applyPlay(state, seat, pattern, move.cards);
        }
      }
      afterAction(state);
      if (++guard > 300) {
        logger.warn("drive 触发上限，gameId={}（疑似全员离开的极端局面）", state.getId());
        break;
      }
    }
  }

  /**
   * 代打决策：首出打最小单张；跟牌找能压过当前最大牌的「最弱一手」，压不过则过牌。
   * 顺子 / 同花 / 同花顺较复杂，机器人不主动凑（压不过就过），其余牌型按点数/张数匹配。
   */
  private BotMove decideBot(GameState state, GameState.Seat seat) {
    RuleBook rb = state.getRuleBook();
    List<Card> hand = seat.getHand();
    CardPattern top = state.getTopPlay();

    // 首出：必须出牌，打最小单张
    if (top == null) {
      Card smallest = rb.smallest(hand);
      if (smallest == null) {
        return new BotMove(true, null);
      }
      return new BotMove(false, Collections.singletonList(smallest));
    }

    // 跟牌：只在与当前牌同张数的候选里找能压过的
    int need = top.getCards().size();
    Map<String, List<Card>> groups = new LinkedHashMap<>();
    for (Card c : hand) {
      groups.computeIfAbsent(c.token(), (k) -> new ArrayList<>()).add(c);
    }

    List<Card> best = null;
    int bestStrength = Integer.MAX_VALUE;

    if (need == 1) {
      for (List<Card> g : groups.values()) {
        List<Card> one = Collections.singletonList(g.get(0));
        if (botBeats(rb, one, top) && botStrength(rb, one) < bestStrength) {
          best = one;
          bestStrength = botStrength(rb, one);
        }
      }
    } else if (need == 2) {
      for (List<Card> g : groups.values()) {
        if (g.size() >= 2) {
          List<Card> two = Arrays.asList(g.get(0), g.get(1));
          if (botBeats(rb, two, top) && botStrength(rb, two) < bestStrength) {
            best = two;
            bestStrength = botStrength(rb, two);
          }
        }
      }
    } else if (need == 3) {
      if (top.getType() == CardPattern.FIVE_TEN_K || top.getType() == CardPattern.FLUSH_FIVE_TEN_K) {
        // 当前是 510K 家族：试着凑 5/10/K，凑不出就只能过
        Card five = null;
        Card ten = null;
        Card king = null;
        for (Card c : hand) {
          if ("5".equals(c.token()) && five == null) {
            five = c;
          } else if ("10".equals(c.token()) && ten == null) {
            ten = c;
          } else if ("K".equals(c.token()) && king == null) {
            king = c;
          }
        }
        if (five != null && ten != null && king != null) {
          List<Card> three = Arrays.asList(five, ten, king);
          if (botBeats(rb, three, top)) {
            return new BotMove(false, three);
          }
        }
        return new BotMove(true, null);
      }
      // 三条
      for (List<Card> g : groups.values()) {
        if (g.size() >= 3) {
          List<Card> three = Arrays.asList(g.get(0), g.get(1), g.get(2));
          if (botBeats(rb, three, top) && botStrength(rb, three) < bestStrength) {
            best = three;
            bestStrength = botStrength(rb, three);
          }
        }
      }
    } else if (need == 4) {
      for (List<Card> g : groups.values()) {
        if (g.size() >= 4) {
          List<Card> four = Arrays.asList(g.get(0), g.get(1), g.get(2), g.get(3));
          if (botBeats(rb, four, top) && botStrength(rb, four) < bestStrength) {
            best = four;
            bestStrength = botStrength(rb, four);
          }
        }
      }
    }

    if (best == null) {
      return new BotMove(true, null);
    }
    return new BotMove(false, best);
  }

  /** 这手牌能否压过 top（用于代打候选筛选） */
  private boolean botBeats(RuleBook rb, List<Card> cards, CardPattern top) {
    CardPattern p = CardPattern.of(cards);
    return p != null && rb.beats(p, top);
  }

  /** 一手牌的综合强度（越小越弱），用于代打优先出最弱的牌 */
  private int botStrength(RuleBook rb, List<Card> cards) {
    int s = 0;
    for (Card c : cards) {
      s += rb.strength(c);
    }
    return s;
  }

  /** 代打的一步：pass=true 表示过牌，否则打出 cards */
  private static final class BotMove {
    final boolean pass;
    final List<Card> cards;

    BotMove(boolean pass, List<Card> cards) {
      this.pass = pass;
      this.cards = cards;
    }
  }

  /**
   * 出牌 / 过牌之后的公共后续：判定收轮、推进回合、判定结束
   */
  private void afterAction(GameState state) {
    if (state.getDeck().isEmpty() && anyoneFinished(state)) {
      settle(state);
      return;
    }

    if (state.allOthersPassed()) {
      closeTrick(state);
      return;
    }

    int next = nextActiveSeat(state, state.getTurnSeat());
    if (next < 0) {
      closeTrick(state);
      return;
    }
    state.setTurnSeat(next);
  }

  /**
   * 收轮：本轮最大者收走桌面所有分，全员补牌，赢家先出下一轮
   */
  private void closeTrick(GameState state) {
    int winnerSeat = state.getTopSeat();
    if (winnerSeat < 0) {
      // 理论上不会发生（首出才能过牌已被拦），兜底按当前 leader 处理
      winnerSeat = state.getLeaderSeat();
    }
    GameState.Seat winner = state.seatAt(winnerSeat);
    int gained = state.getTrickScore();
    if (winner != null) {
      winner.addPickedScore(gained);
    }
    state.setTrickWinnerSeat(winnerSeat);

    state.getTable().clear();
    state.getPassed().clear();
    state.setTopPlay(null);
    state.setTopSeat(-1);
    state.resetTrickScore();
    state.nextTrick();
    state.setReason("trick.end");

    // 补牌：从赢家开始按座位顺序补到 5 张
    refill(state, winnerSeat);

    if (state.getDeck().isEmpty() && anyoneFinished(state)) {
      settle(state);
      return;
    }

    int leader = winnerSeat;
    if (winner != null && winner.isFinished()) {
      int next = nextActiveSeat(state, winnerSeat);
      leader = next < 0 ? winnerSeat : next;
    }
    state.setLeaderSeat(leader);
    state.setTurnSeat(leader);

    logger.info("trick closed, gameId={}, winnerSeat={}, gained={}, nextLeader={}, deck={}",
        state.getId(), winnerSeat, gained, leader, state.getDeck().size());
  }

  /**
   * 补牌：从赢家开始一张一轮地发，一直发到所有人都满 5 张或牌堆见底。
   * 之所以不「一人补满再下一个」，是因为牌堆尾巴常常不够，
   * 一人补满会让先补的人白拿好几张，按轮发才公平。
   */
  private void refill(GameState state, int startSeat) {
    int total = state.playerCount();
    boolean drew = true;
    while (drew) {
      drew = false;
      for (int i = 0; i < total; i++) {
        GameState.Seat seat = state.seatAt((startSeat + i) % total);
        if (seat == null || seat.getHand().size() >= HAND_SIZE || state.getDeck().isEmpty()) {
          continue;
        }
        seat.getHand().add(state.getDeck().remove(0));
        drew = true;
      }
    }
    // 补完统一按当局顺序排好，客户端直接渲染
    for (GameState.Seat seat : state.getSeats()) {
      List<Card> sorted = state.getRuleBook().sortDesc(seat.getHand());
      seat.getHand().clear();
      seat.getHand().addAll(sorted);
    }
    // 收轮前出完、补牌后又有了牌的，finished 要放开
    for (GameState.Seat seat : state.getSeats()) {
      seat.setFinished(seat.getHand().isEmpty());
    }
  }

  /** 从 from 之后找下一个能出牌的人；都不行返回 -1 */
  private int nextActiveSeat(GameState state, int from) {
    int total = state.playerCount();
    for (int i = 1; i <= total; i++) {
      int candidate = (from + i) % total;
      GameState.Seat seat = state.seatAt(candidate);
      if (seat == null || seat.isFinished()) {
        continue;
      }
      if (state.getPassed().contains(candidate)) {
        continue;
      }
      if (candidate == state.getTopSeat()) {
        continue;
      }
      if (seat.getHand().isEmpty()) {
        continue;
      }
      return candidate;
    }
    return -1;
  }

  /* ---------------- 结算 ---------------- */

  private void settle(GameState state) {
    // 最后一手是「出完牌」直接触发结算的，没人走收轮流程：
    // 桌面上这一轮已经打出的分归本轮最大者，否则这部分分会凭空丢掉（总分对不上）。
    if (state.getTopSeat() >= 0 && state.getTrickScore() > 0) {
      GameState.Seat trickTop = state.seatAt(state.getTopSeat());
      if (trickTop != null) {
        trickTop.addPickedScore(state.getTrickScore());
      }
    }
    state.resetTrickScore();

    state.setStatus(GameState.STATUS_ENDED);
    state.setReason("end");

    List<GameState.Seat> sorted = new ArrayList<>(state.getSeats());
    sorted.sort(Comparator.comparingInt(GameState.Seat::getPickedScore).reversed());

    int top = sorted.isEmpty() ? 0 : sorted.get(0).getPickedScore();
    // 每人应摊的平均分。除不尽时把余数摊给名次靠前的几家，
    // 否则 100/3 这种会取整成 33，三个人加起来凭空多出 1 分
    int total = state.getRuleBook().totalScore();
    int count = Math.max(1, sorted.size());
    int base = total / count;
    int remainder = total % count;

    int rank = 0;
    for (GameState.Seat seat : sorted) {
      int deduction = base + (rank < remainder ? 1 : 0);
      rank += 1;
      seat.setRankNo(rank);
      seat.setScoreChange(seat.getPickedScore() - deduction);
      // 并列最高分都算赢（同分不同座位不会互相压制）
      seat.setWin(seat.getPickedScore() == top);
    }

    GameState.Seat winner = sorted.isEmpty() ? null : sorted.get(0);

    state.bumpRevision();

    // 落库（失败不影响本局结果，内存里已经结算完了）
    try {
      Game game = new Game();
      game.setId(state.getId());
      game.setWinnerUserId(winner == null ? 0L : winner.getUserId());
      game.setDeckRemain("");
      gameMapper.settle(game);

      for (GameState.Seat seat : state.getSeats()) {
        GamePlayer p = new GamePlayer();
        p.setGameId(state.getId());
        p.setUserId(seat.getUserId());
        p.setPickedScore(seat.getPickedScore());
        p.setRankNo(seat.getRankNo());
        p.setScoreChange(seat.getScoreChange());
        p.setIsWin(seat.isWin() ? 1 : 0);
        gameRecordMapper.updatePlayerResult(p);
      }
    } catch (Exception e) {
      logger.warn("结算落库失败 gameId={}: {}", state.getId(), e.getMessage());
    }

    pushAll(state);

    // 房间回到等待状态，准备下一局
    try {
      roomService.finishGame(state.getRoomId());
    } catch (Exception e) {
      logger.warn("房间状态回退失败 roomId={}: {}", state.getRoomId(), e.getMessage());
    }

    logger.info("game settled, gameId={}, winnerSeat={}, score={}", state.getId(),
        winner == null ? -1 : winner.getSeat(),
        winner == null ? 0 : winner.getPickedScore());

    games.remove(state.getId());
  }

  /* ---------------- 查询 ---------------- */

  @Override
  public Map<String, Object> stateOfUser(Long userId) {
    GameState state = findActiveByUser(userId);
    return state == null ? null : GameView.state(state);
  }

  @Override
  public List<String> handOfUser(Long userId) {
    GameState state = findActiveByUser(userId);
    if (state == null) {
      return null;
    }
    synchronized (state) {
      GameState.Seat seat = state.seatOf(userId);
      if (seat == null) {
        return null;
      }
      List<String> codes = new ArrayList<>(seat.getHand().size());
      for (Card card : seat.getHand()) {
        codes.add(card.getCode());
      }
      return codes;
    }
  }

  @Override
  public Map<String, Object> handViewOfUser(Long userId) {
    GameState state = findActiveByUser(userId);
    if (state == null) {
      return null;
    }
    synchronized (state) {
      GameState.Seat seat = state.seatOf(userId);
      return seat == null ? null : GameView.hand(state, seat);
    }
  }

  @Override
  public boolean hasActiveGame(Long roomId) {
    for (GameState state : games.values()) {
      if (state.getRoomId() == roomId && !state.isEnded()) {
        return true;
      }
    }
    return false;
  }

  /**
   * 找该用户所在的进行中对局。
   * 已出局（quit）的不算——主动离开过的玩家不能再通过 /v1/games/current 摸回这一局。
   */
  private GameState findActiveByUser(Long userId) {
    for (GameState state : games.values()) {
      if (state.isEnded()) {
        continue;
      }
      GameState.Seat seat = state.seatOf(userId == null ? -1L : userId);
      if (seat != null && !seat.isQuit()) {
        return state;
      }
    }
    return null;
  }

  /* ---------------- 内部工具 ---------------- */

  private GameState requireGame(String gameId) {
    Long id = parseGameId(gameId);
    GameState state = id == null ? null : games.get(id);
    if (state == null || state.isEnded()) {
      throw new BizException(ErrorCode.GAME_NOT_FOUND.getCode(), ErrorCode.GAME_NOT_FOUND.getMessage());
    }
    return state;
  }

  private GameState.Seat requireTurn(GameState state, Long userId) {
    GameState.Seat me = state.seatOf(userId == null ? -1L : userId);
    if (me == null) {
      throw new BizException(ErrorCode.GAME_NOT_FOUND.getCode(), ErrorCode.GAME_NOT_FOUND.getMessage());
    }
    // 已出局：主动离开过这一局，不能再操作（他的牌由服务端代打）
    if (me.isQuit()) {
      throw new BizException(ErrorCode.GAME_NOT_FOUND.getCode(), "你已退出该对局");
    }
    if (me.isFinished()) {
      throw new BizException(ErrorCode.GAME_NOT_YOUR_TURN.getCode(), "你已出完手牌");
    }
    if (state.getTurnSeat() != me.getSeat()) {
      throw new BizException(ErrorCode.GAME_NOT_YOUR_TURN.getCode(),
          ErrorCode.GAME_NOT_YOUR_TURN.getMessage());
    }
    return me;
  }

  private List<Card> parseCards(List<String> codes) {
    if (codes == null || codes.isEmpty()) {
      throw new BizException(ErrorCode.GAME_PATTERN_INVALID.getCode(), "没选牌");
    }
    List<Card> cards = new ArrayList<>(codes.size());
    for (String code : codes) {
      Card card = Card.of(code);
      if (card == null) {
        throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "牌编码非法：" + code);
      }
      cards.add(card);
    }
    return cards;
  }

  private boolean anyoneFinished(GameState state) {
    for (GameState.Seat seat : state.getSeats()) {
      if (seat.isFinished()) {
        return true;
      }
    }
    return false;
  }

  private void recordPlay(GameState state, GameState.Seat seat, int action, CardPattern pattern) {
    try {
      GamePlay play = new GamePlay();
      play.setGameId(state.getId());
      play.setTrickNo(state.getTrickNo());
      play.setSeqNo(state.getRevision());
      play.setUserId(seat.getUserId());
      play.setAction(action);
      if (pattern != null) {
        play.setCardType(pattern.getType());
        play.setMainToken(pattern.getMainToken());
        play.setCards(pattern.csv());
        play.setCardsCount(pattern.getCards().size());
      } else {
        play.setCardType(0);
        play.setMainToken("");
        play.setCards("");
        play.setCardsCount(0);
      }
      gameRecordMapper.insertPlay(play);
    } catch (Exception e) {
      logger.debug("出牌记录落库失败（不影响对局）: {}", e.getMessage());
    }
  }

  private void persistPlayers(GameState state) {
    for (GameState.Seat seat : state.getSeats()) {
      try {
        GamePlayer p = new GamePlayer();
        p.setGameId(state.getId());
        p.setUserId(seat.getUserId());
        p.setSeat(seat.getSeat());
        p.setInitialCards(Card.join(seat.getHand()));
        p.setPickedScore(0);
        p.setRankNo(0);
        p.setScoreChange(0);
        p.setIsWin(0);
        gameRecordMapper.insertPlayer(p);
      } catch (Exception e) {
        logger.warn("对局玩家落库失败 gameId={}, userId={}: {}", state.getId(), seat.getUserId(),
            e.getMessage());
      }
    }
  }

  private void pushAll(GameState state) {
    broker.broadcastToRoom(state.getRoomId(), "game.state", GameView.state(state));
    for (GameState.Seat seat : state.getSeats()) {
      broker.sendToUser(seat.getUserId(), "game.hand", GameView.hand(state, seat));
    }
  }

  /** "g_7" / "7" -> 7 */
  private Long parseGameId(String gameId) {
    if (gameId == null) {
      return null;
    }
    String raw = gameId.trim();
    if (raw.isEmpty()) {
      return null;
    }
    if (raw.startsWith("g_")) {
      raw = raw.substring(2);
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      return null;
    }
  }

  /** "u_17" -> 17 */
  private long parseId(String userId) {
    if (userId == null) {
      return 0L;
    }
    String raw = userId.trim();
    if (raw.startsWith("u_")) {
      raw = raw.substring(2);
    }
    try {
      return Long.parseLong(raw);
    } catch (NumberFormatException e) {
      return 0L;
    }
  }
}
