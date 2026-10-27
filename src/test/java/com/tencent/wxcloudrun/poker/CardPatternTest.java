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
  void 同花顺大于同花和顺子但压不了它们() {
    CardPattern sf = hand("5H", "6H", "7H");
    CardPattern flush = hand("7S", "3S", "2S");
    CardPattern straight = hand("5S", "6H", "7D");

    assertEquals(CardPattern.STRAIGHT_FLUSH, sf.getType());
    // 牌型不同就压不了，不存在跨型比较
    assertFalse(sf.beats(flush, W));
    assertFalse(flush.beats(sf, W));
    assertFalse(sf.beats(straight, W));

    // 同型之间：同花顺也按自然点数顶端比
    assertTrue(hand("6H", "7H", "8H").beats(sf, W));
  }

  @Test
  void 不同牌型不能互压() {
    assertFalse(hand("7S", "7H", "7D").beats(hand("5S", "6H", "7D"), W), "三条压不了顺子");
    assertFalse(hand("5S", "6H", "7D").beats(hand("7S", "7H", "7D"), W), "顺子压不了三条");
    assertFalse(hand("7S", "3S", "2S").beats(hand("5S", "6H", "7D"), W), "同花压不了顺子");
    assertFalse(hand("7S").beats(hand("7S", "7H"), W), "单张压不了对子");
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
