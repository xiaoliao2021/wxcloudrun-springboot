package com.tencent.wxcloudrun.poker;

import com.tencent.wxcloudrun.dto.RoomConfig;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 牌型判定与压牌规则。
 *
 * 跑法：mvn -o test -Dtest=CardPatternTest
 *
 * 顺序表用经典 7鬼523：7 > 王 > 5 > 2 > 3 > A > K > Q > J > 10 > 9 > 8 > 6 > 4
 * （权重：7=14 王=13 5=12 2=11 3=10 A=9 K=8 Q=7 J=6 10=5 9=4 8=3 6=2 4=1）
 */
class CardPatternTest {

  private static final Map<String, Integer> W = weightsOf(RoomConfig.defaultOrder());

  private static Map<String, Integer> weightsOf(List<String> order) {
    java.util.Map<String, Integer> map = new java.util.HashMap<>();
    for (int i = 0; i < order.size(); i++) {
      map.put(order.get(i), order.size() - i);
    }
    return map;
  }

  private static List<Card> cards(String... codes) {
    List<Card> list = new java.util.ArrayList<>();
    for (String code : codes) {
      Card card = Card.of(code);
      assertNotNull(card, "非法测试牌码 " + code);
      list.add(card);
    }
    return list;
  }

  private static CardPattern hand(String... codes) {
    return CardPattern.of(cards(codes));
  }

  @Test
  void 老四种牌型不变() {
    assertEquals(CardPattern.SINGLE, hand("7S").getType());
    assertEquals(CardPattern.PAIR, hand("7S", "7H").getType());
    assertEquals(CardPattern.TRIPLE, hand("7S", "7H", "7D").getType());
    assertEquals(CardPattern.QUAD, hand("7S", "7H", "7D", "7C").getType());

    // 王共用一个档位：大小王算对子
    assertEquals(CardPattern.PAIR, hand("XR", "XB").getType());

    // 不同点数凑不成对
    assertNull(hand("7S", "5H"));
    assertNull(hand("7S", "5H", "3D", "2C"));
  }

  @Test
  void 顺子判定() {
    assertEquals(CardPattern.STRAIGHT, hand("5S", "6H", "7D").getType());
    assertEquals(CardPattern.STRAIGHT, hand("9S", "TH", "JD").getType());
    assertEquals(CardPattern.STRAIGHT, hand("QS", "KH", "AD").getType(), "QKA，A 当最大");
    assertEquals(CardPattern.STRAIGHT, hand("AS", "2H", "3D").getType(), "A23，A 当 1");

    // A23 的顶端是 3，不是 A
    assertEquals("3", hand("AS", "2H", "3D").getMainToken());
    assertEquals("A", hand("QS", "KH", "AD").getMainToken());

    // 不连号 / 有重复点数 / 带王，都不算顺子
    assertNull(hand("5S", "7H", "8D"));
    assertNull(hand("5S", "5H", "6D"));
    assertNull(hand("5S", "6H", "XR"), "王不凑顺子");
    assertNull(hand("KS", "AH", "2D"), "KA2 不绕圈");
  }

  @Test
  void 同花与同花顺判定() {
    assertEquals(CardPattern.FLUSH, hand("7S", "3S", "2S").getType());
    assertEquals(CardPattern.FLUSH, hand("KS", "QS", "2S").getType());

    assertEquals(CardPattern.STRAIGHT_FLUSH, hand("5H", "6H", "7H").getType());
    assertEquals(CardPattern.STRAIGHT_FLUSH, hand("AH", "2H", "3H").getType());

    // 差一张同色就不是同花
    assertNull(hand("7S", "3H", "2D"));
    assertNull(hand("7S", "3S", "2H"));
    // 两张同花色不成牌型
    assertNull(hand("7S", "3S"));
  }

  @Test
  void 顺子按自然点数比大小() {
    assertTrue(hand("6S", "7H", "8D").beats(hand("5S", "6H", "7D"), W),
      "678 > 567");
    assertTrue(hand("QS", "KH", "AD").beats(hand("JS", "QH", "KD"), W),
      "QKA 是最大的顺子");
    assertTrue(hand("4S", "5H", "6D").beats(hand("AS", "2H", "3D"), W),
      "456 > A23（A 当 1 时 A23 最小）");
    assertFalse(hand("5S", "6H", "7D").beats(hand("6S", "7H", "8D"), W));
    assertFalse(hand("5S", "6H", "7D").beats(hand("5C", "6S", "7H"), W),
      "同样的顺子压不过（同顶端，靠花色也压不过自己）");
  }

  @Test
  void 同花按当局顺序比最大牌() {
    // 注意别挑到连号的组合，那会变成同花顺。这里都是不连号的同花
    assertEquals(CardPattern.FLUSH, hand("7S", "3S", "2S").getType());
    assertEquals(CardPattern.FLUSH, hand("5S", "2S", "AS").getType());

    // 含 7 的同花最大（7 权重 14）
    assertTrue(hand("7S", "3S", "2S").beats(hand("5S", "2S", "AS"), W));
    // 最大牌同点数时比花色：黑桃 > 红桃
    assertTrue(hand("5S", "3S", "2S").beats(hand("5H", "3H", "2H"), W));
    assertFalse(hand("5H", "3H", "2H").beats(hand("5S", "3S", "2S"), W));
  }

  @Test
  void 同花顺压得了三条同花顺子() {
    CardPattern sf = hand("5H", "6H", "7H");

    assertEquals(CardPattern.STRAIGHT_FLUSH, sf.getType());
    assertTrue(sf.beats(hand("7S", "7H", "7D"), W), "同花顺压三条");
    assertTrue(sf.beats(hand("7S", "3S", "2S"), W), "同花顺压同花");
    assertTrue(sf.beats(hand("5S", "6H", "7D"), W), "同花顺压顺子");

    // 反方向压不了
    assertFalse(hand("7S", "7H", "7D").beats(sf, W));
    assertFalse(hand("7S", "3S", "2S").beats(sf, W));

    // 同型之间：同花顺按自然点数顶端比
    assertTrue(hand("6H", "7H", "8H").beats(sf, W));
  }

  @Test
  void 三张牌型之间按层级互压() {
    CardPattern straight = hand("5S", "6H", "7D");
    CardPattern flush = hand("7S", "3S", "2S");
    CardPattern triple = hand("7S", "7H", "7D");
    CardPattern sf = hand("5H", "6H", "7H");

    // 层级 顺子 < 同花 < 三条 < 同花顺，高的压低的
    assertTrue(flush.beats(straight, W), "同花压顺子");
    assertTrue(triple.beats(straight, W), "三条压顺子");
    assertTrue(triple.beats(flush, W), "三条压同花");
    assertTrue(sf.beats(triple, W), "同花顺压三条");

    // 反方向：层级低的压不了层级高的
    assertFalse(straight.beats(flush, W), "顺子压不了同花");
    assertFalse(straight.beats(triple, W), "顺子压不了三条");
    assertFalse(flush.beats(triple, W), "同花压不了三条");
    assertFalse(triple.beats(sf, W), "三条压不了同花顺");
  }

  @Test
  void 跨型压只看层级不看具体点数() {
    // 层级高就赢，哪怕单看点数更小
    assertTrue(hand("2S", "2H", "2D").beats(hand("7S", "3S", "2S"), W), "三条 222 压同花");
    // 最小的同花顺（A23，注意三张要同花色）也压得过三条 777
    assertTrue(hand("AS", "2S", "3S").beats(hand("7S", "7H", "7D"), W), "同花顺 A23 压三条");
    // 最小的 510K 压得过最大的同花顺
    assertTrue(hand("5D", "TD", "KD").beats(hand("QH", "KH", "AH"), W), "510K 压同花顺");
  }

  @Test
  void 张数不同永远压不了() {
    assertFalse(hand("7S").beats(hand("7S", "7H"), W), "单张压不了对子");
    assertFalse(hand("7S", "7H").beats(hand("7S", "7H", "7D"), W), "对子压不了三条");
    assertFalse(hand("7S", "7H", "7D").beats(hand("7S", "7H", "7D", "7C"), W), "三条压不了四条");
    assertFalse(hand("7S", "7H", "7D", "7C").beats(hand("7S", "7H", "7D"), W), "四条压不了三条");
    // 三张牌型层级再高也压不了别的张数
    assertFalse(hand("5S", "TS", "KS").beats(hand("7S", "7H", "7D", "7C"), W), "炸弹压不了四条");
  }

  @Test
  void 判定510K() {
    assertEquals(CardPattern.FIVE_TEN_K, hand("5S", "TH", "KD").getType());
    assertEquals(CardPattern.FLUSH_FIVE_TEN_K, hand("5S", "TS", "KS").getType(), "同花色 510K 单列");

    // 顺序无所谓，三张身份对了就行
    assertEquals(CardPattern.FIVE_TEN_K, hand("KH", "5D", "TC").getType());

    // 必须各一张：两张 5 / 两张 10 / 两张 K 都不算
    assertNull(hand("5S", "5H", "KD"));
    assertNull(hand("5S", "TH", "TD"));
    assertNull(hand("5S", "TH", "KD", "KC"), "四张不合法");
    // 差一张就不是 510K
    assertNull(hand("5S", "9H", "KD"));
    assertNull(hand("4S", "TH", "KD"));
    assertNull(hand("5S", "TH", "QD"));
    // 王不能顶替其中任何一张
    assertNull(hand("XR", "TH", "KD"));
    assertNull(hand("5S", "XB", "KD"));
    assertNull(hand("5S", "TH", "XR"));

    assertEquals("K", hand("5S", "TH", "KD").getMainToken());
  }

  @Test
  void 炸弹能跨型压任意三张() {
    CardPattern bomb = hand("5S", "TH", "KD");
    CardPattern flushBomb = hand("5S", "TS", "KS");

    assertTrue(bomb.beats(hand("5H", "6H", "7H"), W), "510K 压同花顺");
    assertTrue(bomb.beats(hand("7S", "7H", "7D"), W), "510K 压三条");
    assertTrue(bomb.beats(hand("7S", "3S", "2S"), W), "510K 压同花");
    assertTrue(bomb.beats(hand("5C", "6H", "7D"), W), "510K 压顺子");
    assertTrue(flushBomb.beats(hand("5H", "6H", "7H"), W), "同花510K 压同花顺");

    // 同花510K > 510K
    assertTrue(flushBomb.beats(bomb, W));
    assertFalse(bomb.beats(flushBomb, W));

    // 反方向：普通三张压不了炸弹
    assertFalse(hand("5H", "6H", "7H").beats(bomb, W), "同花顺压不了 510K");
    assertFalse(hand("7S", "7H", "7D").beats(bomb, W), "三条压不了 510K");
    assertFalse(hand("7S", "3S", "2S").beats(bomb, W), "同花压不了 510K");
    assertFalse(hand("5C", "6H", "7D").beats(bomb, W), "顺子压不了 510K");
  }

  @Test
  void 两个510K比K的花色() {
    // 黑桃 K > 红桃 K > 梅花 K > 方块 K
    assertTrue(hand("5S", "TH", "KS").beats(hand("5H", "TS", "KH"), W));
    assertFalse(hand("5H", "TS", "KH").beats(hand("5S", "TH", "KS"), W));
    assertTrue(hand("5C", "TD", "KC").beats(hand("5S", "TH", "KD"), W));
    assertTrue(hand("5S", "TS", "KS").beats(hand("5H", "TS", "KH"), W), "同花510K 同样比 K 的花色");

    // 花色一样就是压不过自己
    assertFalse(hand("5S", "TH", "KS").beats(hand("5H", "TS", "KS"), W));
  }

  @Test
  void 炸弹也压不了别的张数() {
    CardPattern bomb = hand("5S", "TH", "KD");

    assertFalse(bomb.beats(hand("7S", "7H", "7D", "7C"), W), "三张压不了四条");
    assertFalse(hand("7S", "7H", "7D", "7C").beats(bomb, W), "四条压不了三张");
    assertFalse(bomb.beats(hand("7S"), W), "三张压不了单张");
    assertFalse(hand("7S").beats(bomb, W), "单张压不了三张");
    assertFalse(bomb.beats(hand("7S", "7H"), W), "三张压不了对子");
  }

  @Test
  void 分值和牌型名覆盖510K() {
    assertEquals(25, hand("5S", "TH", "KD").score(), "5 + 10 + 10");
    assertEquals(25, hand("5S", "TS", "KS").score());
    assertEquals("510K", hand("5S", "TH", "KD").typeName());
    assertEquals("同花510K", hand("5S", "TS", "KS").typeName());
  }

  @Test
  void 首出总能通过() {
    assertNotNull(hand("5S", "6H", "7D").beats(null, W));
    assertTrue(hand("5S", "6H", "7D").beats(null, W));
  }

  @Test
  void 分数只在5和10K上算() {
    assertEquals(0, hand("7S").score());
    assertEquals(5, hand("5S").score());
    assertEquals(10, hand("TS").score());
    assertEquals(10, hand("KS").score());
    // 顺子里的分牌也照算：5+6+7 -> 5 分
    assertEquals(5, hand("5S", "6H", "7D").score());
    // 同花 KQJ -> K 是 10 分
    assertEquals(10, hand("KS", "QS", "JS").score());
  }

  @Test
  void 牌型中文名() {
    assertEquals("顺子", hand("5S", "6H", "7D").typeName());
    assertEquals("同花", hand("7S", "3S", "2S").typeName());
    assertEquals("同花顺", hand("5H", "6H", "7H").typeName());
    assertEquals("三条", hand("7S", "7H", "7D").typeName());
  }

  @Test
  void 多副牌时的边角情况() {
    // 多副牌里可能有两张 5S：5S 5S 6S 不连号，但三张同花色，算同花
    assertEquals(CardPattern.FLUSH, hand("5S", "5S", "6S").getType());
    // 三张同点数仍然是三条（同花判定让位给三条）
    assertEquals(CardPattern.TRIPLE, hand("5S", "5H", "5S").getType());
    // 三张王：王共一档，算三条
    assertEquals(CardPattern.TRIPLE, hand("XR", "XB", "XR").getType());
  }

  @Test
  void 空手牌和超四张都不合法() {
    assertNull(CardPattern.of(Collections.emptyList()));
    assertNull(CardPattern.of(null));
    assertNull(hand("5S", "6S", "7S", "8S", "9S"));
  }

  @Test
  void 顺序表可配置时同花跟着变() {
    // 换成「A 最大」的普通顺序，同花大小跟着变
    Map<String, Integer> w2 = weightsOf(Arrays.asList(
      "A", "K", "Q", "J", "10", "9", "8", "7", "6", "5", "4", "3", "2", "JOKER"));
    // 注意避开 A23 —— 那会被判成同花顺
    assertTrue(hand("AS", "5S", "2S").beats(hand("7S", "4S", "2S"), w2));
    assertFalse(hand("7S", "4S", "2S").beats(hand("AS", "5S", "2S"), w2));
  }
}
