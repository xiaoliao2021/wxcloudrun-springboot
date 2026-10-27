package com.tencent.wxcloudrun.poker;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * 一张牌。编码规则（与前端、schema.sql 注释一致）：
 * <pre>
 *   点数 + 花色   7S 黑桃7、TD 方块10（10 写作 T）、AC 梅花A
 *   大小王        XR 大王、XB 小王
 * </pre>
 *
 * 花色大小：黑桃 > 红桃 > 梅花 > 方块（同点数时比花色）
 */
public final class Card {

  /** 王在顺序表里的档位占位符，大小王共用一档 */
  public static final String JOKER_TOKEN = "JOKER";

  private static final String RANKS = "23456789TJQKA";
  private static final String SUITS = "SHCD";
  public static final String BIG_JOKER = "XR";
  public static final String SMALL_JOKER = "XB";

  private final String code;
  private final boolean joker;
  private final char rank;
  private final char suit;

  private Card(String code, boolean joker, char rank, char suit) {
    this.code = code;
    this.joker = joker;
    this.rank = rank;
    this.suit = suit;
  }

  /** 解析一张牌，非法编码返回 null（调用方负责转成参数错误） */
  public static Card of(String code) {
    if (code == null) {
      return null;
    }
    String c = code.trim().toUpperCase();
    if (c.isEmpty()) {
      return null;
    }
    if (BIG_JOKER.equals(c) || SMALL_JOKER.equals(c)) {
      return new Card(c, true, 'X', 'X');
    }
    if (c.length() != 2) {
      return null;
    }
    char r = c.charAt(0);
    char s = c.charAt(1);
    if (RANKS.indexOf(r) < 0 || SUITS.indexOf(s) < 0) {
      return null;
    }
    return new Card(c, false, r, s);
  }

  /** 解析逗号分隔的牌串，任意一张非法就整串返回 null */
  public static List<Card> parse(String csv) {
    List<Card> result = new ArrayList<>();
    if (csv == null || csv.trim().isEmpty()) {
      return result;
    }
    for (String part : csv.split(",")) {
      if (part.trim().isEmpty()) {
        continue;
      }
      Card card = of(part);
      if (card == null) {
        return null;
      }
      result.add(card);
    }
    return result;
  }

  /** 拼回逗号分隔的字符串 */
  public static String join(List<Card> cards) {
    if (cards == null || cards.isEmpty()) {
      return "";
    }
    StringBuilder sb = new StringBuilder();
    for (Card card : cards) {
      if (sb.length() > 0) {
        sb.append(',');
      }
      sb.append(card.getCode());
    }
    return sb.toString();
  }

  /** 按副数拼一副完整牌堆（未洗牌） */
  public static List<Card> buildDeck(int deckCount, boolean withJokers) {
    List<Card> cards = new ArrayList<>();
    int decks = Math.max(1, deckCount);
    for (int d = 0; d < decks; d++) {
      for (int s = 0; s < SUITS.length(); s++) {
        for (int r = 0; r < RANKS.length(); r++) {
          cards.add(new Card("" + RANKS.charAt(r) + SUITS.charAt(s), false,
              RANKS.charAt(r), SUITS.charAt(s)));
        }
      }
      if (withJokers) {
        cards.add(of(BIG_JOKER));
        cards.add(of(SMALL_JOKER));
      }
    }
    return cards;
  }

  /** 洗牌；同一个 seed 洗出来的顺序一致，方便复盘 */
  public static void shuffle(List<Card> cards, long seed) {
    Collections.shuffle(cards, new Random(seed));
  }

  /** 顺序表里的档位：王统一算 JOKER，其余用可读点数（10 而不是 T） */
  public String token() {
    if (joker) {
      return JOKER_TOKEN;
    }
    return rank == 'T' ? "10" : String.valueOf(rank);
  }

  /** 花色序，越大越强；王固定 0，靠 jokerKind 区分大小 */
  public int suitOrder() {
    switch (suit) {
      case 'S':
        return 4;
      case 'H':
        return 3;
      case 'C':
        return 2;
      case 'D':
        return 1;
      default:
        return 0;
    }
  }

  /** 王的大小：大王 1、小王 0；非王恒为 0 */
  public int jokerKind() {
    return BIG_JOKER.equals(code) ? 1 : 0;
  }

  /**
   * 自然点数：2~10 按面值，J=11 Q=12 K=13 A=14；王返回 0（不参与顺子）
   *
   * 和 token() 不是一回事：token 是「顺序表里的档位」（7 最大），
   * 这里是扑克原本的点数大小，只有判顺子连号时才用。
   */
  public int rankValue() {
    if (joker) {
      return 0;
    }
    switch (rank) {
      case 'T':
        return 10;
      case 'J':
        return 11;
      case 'Q':
        return 12;
      case 'K':
        return 13;
      case 'A':
        return 14;
      default:
        return rank >= '2' && rank <= '9' ? rank - '0' : 0;
    }
  }

  /** 花色字符（王是 'X'） */
  public char suit() {
    return suit;
  }

  /**
   * 同一档位内的「次级比较值」：花色序 * 2 + 王种
   * 让 大王 > 小王，同时 黑桃 > 红桃 > 梅花 > 方块
   */
  public int tieBreak() {
    return suitOrder() * 2 + jokerKind();
  }

  public String getCode() {
    return code;
  }

  public boolean isJoker() {
    return joker;
  }

  /** 普通点数的可读形式（'T' -> "10"），王返回 "王" */
  public String displayRank() {
    if (joker) {
      return BIG_JOKER.equals(code) ? "大王" : "小王";
    }
    return rank == 'T' ? "10" : String.valueOf(rank);
  }

  @Override
  public String toString() {
    return code;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof Card)) {
      return false;
    }
    return code.equals(((Card) o).code);
  }

  @Override
  public int hashCode() {
    return code.hashCode();
  }
}
