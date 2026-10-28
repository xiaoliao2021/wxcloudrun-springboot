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
import java.util.Collections;
import java.util.Comparator;
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

      // 通过校验，落到状态上
      hand.clear();
      hand.addAll(remaining);
      if (hand.isEmpty()) {
        me.setFinished(true);
      }

      // 追加而不是覆盖：同一家一轮里可以出好几手，每一手都要留在桌面上
      state.getTable().add(
          new GameState.PlayEntry(me.getSeat(), pattern.getCards(), pattern.getType(), false));
      state.getPassed().clear();
      state.setTopPlay(pattern);
      state.setTopSeat(me.getSeat());
      state.addTrickScore(pattern.score());

      recordPlay(state, me, GamePlay.ACTION_PLAY, pattern);
      state.setReason("play");
      state.bumpRevision();

      afterAction(state);
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

      state.getPassed().add(me.getSeat());
      state.getTable().add(
          new GameState.PlayEntry(me.getSeat(), Collections.emptyList(), 0, true));

      recordPlay(state, me, GamePlay.ACTION_PASS, null);
      state.setReason("pass");
      state.bumpRevision();

      afterAction(state);
      pushAll(state);
    }
  }

  /* ---------------- 回合推进 ---------------- */

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

  private GameState findActiveByUser(Long userId) {
    for (GameState state : games.values()) {
      if (state.isEnded()) {
        continue;
      }
      if (state.seatOf(userId) != null) {
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
