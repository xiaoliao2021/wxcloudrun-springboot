package com.tencent.wxcloudrun.poker;

import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 一手牌（牌型 + 大小）。
 *
 * 7鬼523 的合法牌型：
 * <pre>
 *   1 单张   2 对子   3 三条   4 四条       —— 必须同一点数（王同属 JOKER 一档）
 *   5 顺子   6 同花   7 同花顺              —— 固定三张
 *   8 510K  9 同花510K                      —— 固定三张，炸弹
 * </pre>
 *
 * 张数不同的牌型永远不能互压（对子压不了单张，四条压不了三条）。
 * 同为三张时**完全按牌型层级**比，不同牌型之间也能互压：
 * <pre>
 *   顺子 &lt; 同花 &lt; 三条 &lt; 同花顺 &lt; 510K &lt; 同花510K
 * </pre>
 * 层级高的压层级低的（三条压同花、同花压顺子、同花顺压三条），
 * 层级相同必然是同一牌型，这时才比内部大小。
 * 单张 / 对子 / 四条不在这个序列里，仍然只能跟同牌型比。
 *
 * 同类之间的比较口径不一样，别混：
 * <ul>
 *   <li>顺子 / 同花顺：比**自然点数的顶端**。7鬼523 的顺序表是乱的（7 最大、5 次之），
 *       拿它去比连号会得到「345 = 456」「789 最大、8910 突然变小」这种荒谬结果，
 *       所以连号一律按扑克本来的点数排：234 &lt; 345 &lt; … &lt; QKA，A23 算最小的顺子。</li>
 *   <li>同花：不连号，比**当局顺序里最强的那张牌**，「含 7 的同花更大」符合玩家直觉。</li>
 *   <li>510K：三张牌的身份是固定的，直接比**那张 K 的花色**，不受 order 配置影响。</li>
 *   <li>单张/对子/三条/四条：老规矩，比档位权重，同档比花色。</li>
 * </ul>
 */
public final class CardPattern {

  public static final int SINGLE = 1;
  public static final int PAIR = 2;
  public static final int TRIPLE = 3;
  public static final int QUAD = 4;
  public static final int STRAIGHT = 5;
  public static final int FLUSH = 6;
  public static final int STRAIGHT_FLUSH = 7;
  /** 5 10 K 各一张（炸弹） */
  public static final int FIVE_TEN_K = 8;
  /** 5 10 K 各一张且同花色（最大的炸弹） */
  public static final int FLUSH_FIVE_TEN_K = 9;

  private final int type;
  /** 代表牌的档位：普通牌是主点数，顺子是顶端那张 */
  private final String mainToken;
  private final List<Card> cards;
  /** 当局强度最大的那张，用于同档位内的次级比较 */
  private final Card topCard;
  /** 顺子顶端的自然点数（非顺子为 0） */
  private final int straightTop;

  private CardPattern(int type, String mainToken, List<Card> cards, Card topCard, int straightTop) {
    this.type = type;
    this.mainToken = mainToken;
    this.cards = cards;
    this.topCard = topCard;
    this.straightTop = straightTop;
  }

  /**
   * 识别牌型。不合法返回 null。
   *
   * 判定优先级：同点数（四条/三条/对子/单张）> 510K > 同花顺 > 同花 > 顺子
   * —— 5 10 K 必须放在同花之前判，否则同花色的 510K 会被误判成普通同花
   */
  public static CardPattern of(List<Card> cards) {
    if (cards == null || cards.isEmpty() || cards.size() > 4) {
      return null;
    }

    Set<String> tokens = new LinkedHashSet<>();
    for (Card card : cards) {
      tokens.add(card.token());
    }
    Card top = topCard(cards);

    // 同一点数：单张 / 对子 / 三条 / 四条（王统一算一档）
    if (tokens.size() == 1) {
      return new CardPattern(cards.size(), tokens.iterator().next(), cards, top, 0);
    }

    // 不同点数只有三张牌才有别的牌型
    if (cards.size() != 3) {
      return null;
    }

    boolean flush = isFlush(cards);
    int topRank = straightTop(cards);

    // 510K：三张正好是 5 / 10 / K 各一张。同花色时单列成「同花510K」，比普通 510K 大
    if (isFiveTenK(cards)) {
      return new CardPattern(flush ? FLUSH_FIVE_TEN_K : FIVE_TEN_K, "K", cards, top, 0);
    }

    if (flush && topRank > 0) {
      return new CardPattern(STRAIGHT_FLUSH, tokenOfRank(cards, topRank), cards, top, topRank);
    }
    if (flush) {
      return new CardPattern(FLUSH, top.token(), cards, top, 0);
    }
    if (topRank > 0) {
      return new CardPattern(STRAIGHT, tokenOfRank(cards, topRank), cards, top, topRank);
    }
    return null;
  }

  /**
   * 这手牌里「最大的那张」：先比自然点数，同点数再比花色
   * 同点数的三条/对子因此取花色最大的那张，和原来按 tieBreak 选是一致的
   */
  private static Card topCard(List<Card> cards) {
    Card best = null;
    for (Card card : cards) {
      if (best == null
        || card.rankValue() > best.rankValue()
        || (card.rankValue() == best.rankValue() && card.tieBreak() > best.tieBreak())) {
        best = card;
      }
    }
    return best;
  }

  /** 三张同花色（王没有花色，不参与） */
  private static boolean isFlush(List<Card> cards) {
    char suit = 0;
    for (Card card : cards) {
      if (card.isJoker()) {
        return false;
      }
      if (suit == 0) {
        suit = card.suit();
      } else if (card.suit() != suit) {
        return false;
      }
    }
    return true;
  }

  /**
   * 三张正好是 5 / 10 / K 各一张
   * 多副牌时也不允许拿两张 5 凑数（5 5 K 不是 510K）
   */
  private static boolean isFiveTenK(List<Card> cards) {
    if (cards.size() != 3) {
      return false;
    }

    boolean five = false;
    boolean ten = false;
    boolean king = false;

    for (Card card : cards) {
      if (card.isJoker()) {
        return false;
      }

      String token = card.token();
      if ("5".equals(token) && !five) {
        five = true;
      } else if ("10".equals(token) && !ten) {
        ten = true;
      } else if ("K".equals(token) && !king) {
        king = true;
      } else {
        return false;
      }
    }

    return five && ten && king;
  }

  /**
   * 三张连号时顶端的自然点数；不连号返回 0
   * A 可大可小：QKA 的顶端是 14，A23 的顶端是 3（最小的顺子）
   */
  private static int straightTop(List<Card> cards) {
    int[] v = new int[cards.size()];
    for (int i = 0; i < cards.size(); i++) {
      Card card = cards.get(i);
      if (card.isJoker()) {
        return 0; // 王不能凑顺子
      }
      v[i] = card.rankValue();
    }

    Arrays.sort(v);
    if (isSequence(v)) {
      return v[v.length - 1];
    }

    // A 当 1 再试一次：{14,2,3} -> {1,2,3}
    int[] alt = v.clone();
    for (int i = 0; i < alt.length; i++) {
      if (alt[i] == 14) alt[i] = 1;
    }
    Arrays.sort(alt);

    return isSequence(alt) ? alt[alt.length - 1] : 0;
  }

  private static boolean isSequence(int[] sorted) {
    for (int i = 1; i < sorted.length; i++) {
      if (sorted[i] != sorted[i - 1] + 1) {
        return false;
      }
    }
    return true;
  }

  /** 顺子顶端那张牌的档位（A23 取到的是 3 不是 A） */
  private static String tokenOfRank(List<Card> cards, int rank) {
    for (Card card : cards) {
      if (card.rankValue() == rank) {
        return card.token();
      }
    }
    return "";
  }

  /**
   * 三张牌型之间的大小层级：顺子 &lt; 同花 &lt; 三条 &lt; 同花顺 &lt; 510K &lt; 同花510K
   *
   * 返回 0 表示「不在这个序列里」（单张 / 对子 / 四条），它们走老规矩：只跟同牌型比。
   */
  private int tier() {
    switch (type) {
      case STRAIGHT:
        return 1;
      case FLUSH:
        return 2;
      case TRIPLE:
        return 3;
      case STRAIGHT_FLUSH:
        return 4;
      case FIVE_TEN_K:
        return 5;
      case FLUSH_FIVE_TEN_K:
        return 6;
      default:
        return 0;
    }
  }

  /**
   * 我能不能压过 other
   *
   * 张数不同永远压不了（四条压不了三条，对子压不了单张）。
   *
   * 同为三张时**完全按牌型层级**比，不再要求同牌型：
   * <pre>
   *   顺子 &lt; 同花 &lt; 三条 &lt; 同花顺 &lt; 510K &lt; 同花510K
   * </pre>
   * 层级高的直接压层级低的（三条能压同花、同花能压顺子）。
   * 层级相同必然是同一个牌型，这时才比内部大小。
   *
   * 单张 / 对子 / 四条不在这个序列里，它们仍然只能跟同牌型比。
   */
  public boolean beats(CardPattern other, Map<String, Integer> weights) {
    if (other == null) {
      return true;
    }
    if (this.cards.size() != other.cards.size()) {
      return false;
    }

    int mine = tier();
    int theirs = other.tier();

    // 不在三张序列里：单张 / 对子 / 四条，只跟同牌型比
    if (mine == 0 || theirs == 0) {
      return this.type == other.type && this.key(weights) > other.key(weights);
    }

    // 层级不同：高的赢，跟牌型无关
    if (mine != theirs) {
      return mine > theirs;
    }

    // 层级相同 = 同一个牌型，比内部大小
    return this.key(weights) > other.key(weights);
  }

  /**
   * 同类内的可比键：顺子走自然点数，510K 走 K 的花色，其余走「当局最强的那张牌」
   *
   * 之所以不用 mainToken 直接查权重：同花里 mainToken 是展示用的最大点数牌，
   * 而当局顺序是乱的（7 最大），两张牌谁强得真去查权重表才知道。
   */
  private int key(Map<String, Integer> weights) {
    if (type == STRAIGHT || type == STRAIGHT_FLUSH) {
      return straightTop * 100 + topCard.tieBreak();
    }
    if (type == FIVE_TEN_K || type == FLUSH_FIVE_TEN_K) {
      return kingTieBreak();
    }
    int best = 0;
    for (Card card : cards) {
      Integer w = weights.get(card.token());
      int k = (w == null ? 0 : w) * 100 + card.tieBreak();
      if (k > best) {
        best = k;
      }
    }
    return best;
  }

  /**
   * 510K 里那张 K 的花色序，用于两个 510K 之间比大小。
   * 不走 order 权重：510K 三张牌的身份是固定的，不该受房间配置影响。
   */
  private int kingTieBreak() {
    for (Card card : cards) {
      if ("K".equals(card.token())) {
        return card.tieBreak();
      }
    }
    return 0;
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
      case STRAIGHT:
        return "顺子";
      case FLUSH:
        return "同花";
      case STRAIGHT_FLUSH:
        return "同花顺";
      case FIVE_TEN_K:
        return "510K";
      case FLUSH_FIVE_TEN_K:
        return "同花510K";
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
