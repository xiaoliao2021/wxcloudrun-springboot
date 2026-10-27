package com.tencent.wxcloudrun.poker;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一手牌（牌型 + 大小）。
 *
 * 7鬼523 只有四种牌型，且所有牌必须同一点数：
 * <pre>
 *   1 单张   2 对子   3 三条   4 四条
 * </pre>
 * 大小：先比档位在房间 order 里的权重，权重相同再比最大那张牌的花色（王比大小）。
 */
public final class CardPattern {

  public static final int SINGLE = 1;
  public static final int PAIR = 2;
  public static final int TRIPLE = 3;
  public static final int QUAD = 4;

  private final int type;
  /** 主牌档位：普通点数或 JOKER */
  private final String mainToken;
  private final List<Card> cards;
  /** 这手牌里最大的那张，用于同档位内的次级比较 */
  private final Card topCard;

  private CardPattern(int type, String mainToken, List<Card> cards, Card topCard) {
    this.type = type;
    this.mainToken = mainToken;
    this.cards = cards;
    this.topCard = topCard;
  }

  /**
   * 识别牌型。不合法返回 null：
   * 张数不在 1~4，或点数不一致（王统一视作 JOKER 一档）
   */
  public static CardPattern of(List<Card> cards) {
    if (cards == null || cards.isEmpty() || cards.size() > 4) {
      return null;
    }
    Set<String> tokens = new LinkedHashSet<>();
    Card top = null;
    for (Card card : cards) {
      tokens.add(card.token());
      if (top == null || better(card, top)) {
        top = card;
      }
    }
    if (tokens.size() != 1) {
      return null;
    }
    int type = cards.size();
    return new CardPattern(type, tokens.iterator().next(), cards, top);
  }

  /** 同一档位内比大小：花色优先，再看是不是大王 */
  private static boolean better(Card a, Card b) {
    return a.tieBreak() > b.tieBreak();
  }

  /**
   * 我能不能压过 other
   * 必须同牌型（同张数），然后比权重、比花色
   */
  public boolean beats(CardPattern other, Map<String, Integer> weights) {
    if (other == null) {
      return true;
    }
    if (this.type != other.type) {
      return false;
    }
    Integer mine = weights.get(this.mainToken);
    Integer theirs = weights.get(other.mainToken);
    int w1 = mine == null ? 0 : mine;
    int w2 = theirs == null ? 0 : theirs;
    if (w1 != w2) {
      return w1 > w2;
    }
    return this.topCard.tieBreak() > other.topCard.tieBreak();
  }

  /** 分牌分值：5=5，10=10，K=10，其余 0（每副合计 100） */
  public int score() {
    int sum = 0;
    for (Card card : cards) {
      if (card.isJoker()) {
        continue;
      }
      String token = card.token();
      if ("5".equals(token)) {
        sum += 5;
      } else if ("10".equals(token) || "K".equals(token)) {
        sum += 10;
      }
    }
    return sum;
  }

  public String typeName() {
    switch (type) {
      case SINGLE:
        return "单张";
      case PAIR:
        return "对子";
      case TRIPLE:
        return "三条";
      case QUAD:
        return "四条";
      default:
        return "未知";
    }
  }

  public int getType() {
    return type;
  }

  public String getMainToken() {
    return mainToken;
  }

  public List<Card> getCards() {
    return Collections.unmodifiableList(cards);
  }

  public String csv() {
    return Card.join(cards);
  }
}
