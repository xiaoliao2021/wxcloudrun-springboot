package com.tencent.wxcloudrun.game;

import com.tencent.wxcloudrun.poker.Card;
import com.tencent.wxcloudrun.poker.CardPattern;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 把运行时状态翻成推给客户端的 JSON 结构，形状见 docs/api-game.md §3、§5。
 *
 * 注意：公共状态里**不带任何人的手牌**，只给张数；自己的牌单独走 game.hand。
 */
public final class GameView {

  private GameView() {
  }

  /** 公共桌面状态 game.state 的 data */
  public static Map<String, Object> state(GameState g) {
    Map<String, Object> data = new LinkedHashMap<>(16);
    data.put("gameId", "g_" + g.getId());
    data.put("roomId", "r_" + g.getRoomId());
    data.put("status", g.isEnded() ? "ended" : "playing");
    data.put("revision", g.getRevision());
    data.put("reason", g.getReason());
    data.put("trickNo", g.getTrickNo());
    data.put("turnSeat", g.getTurnSeat());
    data.put("leaderSeat", g.getLeaderSeat());
    data.put("deckRemain", g.getDeck().size());
    data.put("totalScore", g.getRuleBook().totalScore());
    data.put("topPlay", topPlay(g));
    data.put("table", table(g));
    data.put("players", players(g));
    data.put("trickWinnerSeat", g.getTrickWinnerSeat());
    if (g.isEnded()) {
      data.put("result", result(g));
    } else {
      data.put("result", null);
    }
    return data;
  }

  /** 只发给本人的手牌 */
  public static Map<String, Object> hand(GameState g, GameState.Seat seat) {
    Map<String, Object> data = new LinkedHashMap<>(4);
    data.put("gameId", "g_" + g.getId());
    data.put("seat", seat.getSeat());
    data.put("cards", codes(seat.getHand()));
    return data;
  }

  private static Map<String, Object> topPlay(GameState g) {
    CardPattern top = g.getTopPlay();
    if (top == null) {
      return null;
    }
    Map<String, Object> m = new LinkedHashMap<>(6);
    m.put("seat", g.getTopSeat());
    m.put("type", top.getType());
    m.put("typeName", top.typeName());
    m.put("mainToken", top.getMainToken());
    m.put("cards", codes(top.getCards()));
    m.put("score", g.getTrickScore());
    return m;
  }

  private static List<Map<String, Object>> table(GameState g) {
    List<Map<String, Object>> list = new ArrayList<>();
    for (GameState.PlayEntry entry : g.getTable()) {
      Map<String, Object> m = new LinkedHashMap<>(4);
      m.put("seat", entry.getSeat());
      m.put("cards", codes(entry.getCards()));
      m.put("type", entry.getType());
      m.put("passed", entry.isPassed());
      list.add(m);
    }
    return list;
  }

  private static List<Map<String, Object>> players(GameState g) {
    List<Map<String, Object>> list = new ArrayList<>();
    for (GameState.Seat seat : g.getSeats()) {
      Map<String, Object> m = new LinkedHashMap<>(12);
      m.put("seat", seat.getSeat());
      m.put("userId", "u_" + seat.getUserId());
      m.put("nickname", seat.getNickname());
      m.put("handCount", seat.getHand().size());
      m.put("pickedScore", seat.getPickedScore());
      m.put("finished", seat.isFinished());
      m.put("rankNo", seat.getRankNo());
      m.put("scoreChange", seat.getScoreChange());
      m.put("isTurn", !g.isEnded() && seat.getSeat() == g.getTurnSeat());
      m.put("isLeader", seat.getSeat() == g.getLeaderSeat());
      m.put("passed", g.getPassed().contains(seat.getSeat()));
      list.add(m);
    }
    return list;
  }

  private static Map<String, Object> result(GameState g) {
    Map<String, Object> m = new LinkedHashMap<>(3);
    long winnerUserId = 0L;
    List<Map<String, Object>> list = new ArrayList<>();

    // 结算列表按名次排好，客户端拿到直接从上往下画
    List<GameState.Seat> seats = new ArrayList<>(g.getSeats());
    seats.sort((a, b) -> Integer.compare(a.getRankNo(), b.getRankNo()));

    for (GameState.Seat seat : seats) {
      Map<String, Object> p = new LinkedHashMap<>(6);
      p.put("seat", seat.getSeat());
      p.put("userId", "u_" + seat.getUserId());
      p.put("nickname", seat.getNickname());
      p.put("pickedScore", seat.getPickedScore());
      p.put("rankNo", seat.getRankNo());
      p.put("scoreChange", seat.getScoreChange());
      p.put("isWin", seat.isWin());
      list.add(p);
      if (seat.isWin()) {
        winnerUserId = seat.getUserId();
      }
    }
    m.put("winnerUserId", "u_" + winnerUserId);
    m.put("players", list);
    return m;
  }

  private static List<String> codes(List<Card> cards) {
    if (cards == null || cards.isEmpty()) {
      return Collections.emptyList();
    }
    List<String> list = new ArrayList<>(cards.size());
    for (Card card : cards) {
      list.add(card.getCode());
    }
    return list;
  }
}
