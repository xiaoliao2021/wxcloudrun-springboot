package com.tencent.wxcloudrun.poker;

import com.tencent.wxcloudrun.dto.RoomConfig;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 当局规则：把房间配置里的 order 数组翻译成权重表，并提供排序 / 比大小。
 *
 * order 是 14 个档位，从强到弱。默认是常规 32AK（王最大）：
 * <pre>
 *   JOKER 3 2 A K Q J 10 9 8 7 6 5 4
 * </pre>
 * 经典 7鬼523 则是：
 * <pre>
 *   7 JOKER 5 2 3 A K Q J 10 9 8 6 4
 * </pre>
 * 王在表里只占一个 JOKER 档，大小王之间再按 jokerKind 区分。
 */
public final class RuleBook {

  private final List<String> order;
  private final Map<String, Integer> weights;
  private final int deckCount;
  private final boolean withJokers;

  private RuleBook(List<String> order, Map<String, Integer> weights, int deckCount, boolean withJokers) {
    this.order = order;
    this.weights = weights;
    this.deckCount = deckCount;
    this.withJokers = withJokers;
  }

  public static RuleBook of(RoomConfig config) {
    RoomConfig c = config == null ? new RoomConfig() : config;
    List<String> rawOrder = c.getOrder();
    boolean orderProvided = rawOrder != null && !rawOrder.isEmpty();
    List<String> order = orderProvided ? rawOrder : RoomConfig.defaultOrder();
    Map<String, Integer> weights = new HashMap<>();
    int n = order.size();
    for (int i = 0; i < n; i++) {
      weights.put(order.get(i), n - i);
    }
    int decks = c.getDeckCount() == null || c.getDeckCount() < 1 ? 1 : c.getDeckCount();
    // 档位表不管有没有鬼都含 JOKER，所以只在 order 被显式提供时才拿它当线索
    boolean jokers = c.getWithJokers() != null
        ? c.getWithJokers()
        : (orderProvided && rawOrder.contains("JOKER"));
    return new RuleBook(order, weights, decks, jokers);
  }

  public int weightOf(Card card) {
    Integer w = weights.get(card.token());
    return w == null ? 0 : w;
  }

  /** 综合强度：权重为主，花色/王种为次，用于排序和找最小牌 */
  public int strength(Card card) {
    return weightOf(card) * 100 + card.tieBreak();
  }

  /** 从大到小（按当局顺序），返回新列表 */
  public List<Card> sortDesc(List<Card> cards) {
    List<Card> copy = new ArrayList<>(cards);
    copy.sort(Comparator.comparingInt(this::strength).reversed());
    return copy;
  }

  /** 全场最小的那张牌：用于决定谁先出 */
  public Card smallest(List<Card> cards) {
    Card min = null;
    for (Card card : cards) {
      if (min == null || strength(card) < strength(min)) {
        min = card;
      }
    }
    return min;
  }

  /** 用本局权重判定 a 能否压过 b（b 为 null 表示首出，直接通过） */
  public boolean beats(CardPattern a, CardPattern b) {
    if (a == null) {
      return false;
    }
    return a.beats(b, weights);
  }

  /** 每副 100 分，多副累加 */
  public int totalScore() {
    return 100 * deckCount;
  }

  public int getDeckCount() {
    return deckCount;
  }

  public boolean isWithJokers() {
    return withJokers;
  }

  public List<String> getOrder() {
    return order;
  }

  /** 新牌堆（已按配置的副数与是否带鬼生成，未洗牌） */
  public List<Card> newDeck() {
    return Card.buildDeck(deckCount, withJokers);
  }
}
