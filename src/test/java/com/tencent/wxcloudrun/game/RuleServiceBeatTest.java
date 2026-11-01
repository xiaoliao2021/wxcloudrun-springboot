package com.tencent.wxcloudrun.game;

import com.tencent.wxcloudrun.game.model.Card;
import com.tencent.wxcloudrun.game.model.Play;
import com.tencent.wxcloudrun.game.model.PlayType;
import com.tencent.wxcloudrun.game.model.Rank;
import com.tencent.wxcloudrun.game.model.Suit;
import com.tencent.wxcloudrun.game.service.RuleService;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** 三张牌型互压：顺子 < 同花 < 三条 < 同花顺 < 510K < 同花510K */
public class RuleServiceBeatTest {

    private static Card c(Suit s, Rank r) {
        return new Card(s.name() + r.getLabel(), s, r, 1);
    }

    private static Play play(PlayType type, Card... cards) {
        return new Play(type, Arrays.asList(cards));
    }

    private static final Play STRAIGHT_456 = play(PlayType.STRAIGHT,
            c(Suit.SPADE, Rank.R4), c(Suit.HEART, Rank.R5), c(Suit.CLUB, Rank.R6));

    private static final Play FLUSH_SMALL = play(PlayType.FLUSH,
            c(Suit.DIAMOND, Rank.RK), c(Suit.DIAMOND, Rank.R9), c(Suit.DIAMOND, Rank.R7));

    private static final Play FLUSH_BIG = play(PlayType.FLUSH,
            c(Suit.HEART, Rank.RK), c(Suit.HEART, Rank.R9), c(Suit.HEART, Rank.R8));

    private static final Play TRIPLE_9 = play(PlayType.TRIPLE,
            c(Suit.SPADE, Rank.R9), c(Suit.HEART, Rank.R9), c(Suit.CLUB, Rank.R9));

    private static final Play STRAIGHT_FLUSH_456 = play(PlayType.STRAIGHT_FLUSH,
            c(Suit.SPADE, Rank.R4), c(Suit.SPADE, Rank.R5), c(Suit.SPADE, Rank.R6));

    private static final Play FIVE_TEN_KING = play(PlayType.FIVE_TEN_KING,
            c(Suit.SPADE, Rank.R5), c(Suit.HEART, Rank.R10), c(Suit.CLUB, Rank.RK));

    private static final Play SUITED_FIVE_TEN_KING = play(PlayType.SUITED_FIVE_TEN_KING,
            c(Suit.DIAMOND, Rank.R5), c(Suit.DIAMOND, Rank.R10), c(Suit.DIAMOND, Rank.RK));

    @Test
    public void flushBeatsAnyStraight() {
        assertTrue(RuleService.canBeat(STRAIGHT_456, FLUSH_SMALL), "同花应能压顺子");
        assertTrue(RuleService.canBeat(STRAIGHT_456, FLUSH_BIG), "同花应能压顺子");
        assertFalse(RuleService.canBeat(FLUSH_SMALL, STRAIGHT_456), "顺子不能压同花");
    }

    @Test
    public void flushCompareWithinType() {
        assertTrue(RuleService.canBeat(FLUSH_SMALL, FLUSH_BIG), "大一点的同花能压小的");
        assertFalse(RuleService.canBeat(FLUSH_BIG, FLUSH_SMALL), "小的同花不能压大的");
    }

    @Test
    public void tripleBeatsFlushAndStraight() {
        assertTrue(RuleService.canBeat(FLUSH_BIG, TRIPLE_9), "三条应能压同花");
        assertTrue(RuleService.canBeat(STRAIGHT_456, TRIPLE_9), "三条应能压顺子");
        assertFalse(RuleService.canBeat(TRIPLE_9, FLUSH_BIG), "同花不能压三条");
    }

    @Test
    public void higherTiersBeatLowerOnes() {
        assertTrue(RuleService.canBeat(TRIPLE_9, STRAIGHT_FLUSH_456), "同花顺应能压三条");
        assertTrue(RuleService.canBeat(STRAIGHT_FLUSH_456, FIVE_TEN_KING), "510K 能压同花顺");
        assertTrue(RuleService.canBeat(FIVE_TEN_KING, SUITED_FIVE_TEN_KING), "同花510K 能压 510K");
        assertFalse(RuleService.canBeat(SUITED_FIVE_TEN_KING, FIVE_TEN_KING), "510K 不能压同花510K");
    }

    @Test
    public void nonThreeCardTypesStaySeparated() {
        Play single2 = play(PlayType.SINGLE, c(Suit.SPADE, Rank.R2));
        Play pair3 = play(PlayType.PAIR, c(Suit.SPADE, Rank.R3), c(Suit.HEART, Rank.R3));
        assertFalse(RuleService.canBeat(single2, pair3), "不同张数不能互压");

        Play tripleWithTwo = play(PlayType.TRIPLE_WITH_TWO,
                c(Suit.SPADE, Rank.R9), c(Suit.HEART, Rank.R9), c(Suit.CLUB, Rank.R9),
                c(Suit.SPADE, Rank.R4), c(Suit.HEART, Rank.R4));
        Play fourWithOne = play(PlayType.FOUR_WITH_ONE,
                c(Suit.SPADE, Rank.R9), c(Suit.HEART, Rank.R9), c(Suit.CLUB, Rank.R9), c(Suit.DIAMOND, Rank.R9),
                c(Suit.SPADE, Rank.R4));
        assertTrue(RuleService.canBeat(tripleWithTwo, fourWithOne), "四带一能压三带二");
        assertFalse(RuleService.canBeat(fourWithOne, tripleWithTwo), "三带二不能压四带一");
    }

    @Test
    public void straightComparedByTopCard() {
        Play s456 = STRAIGHT_456;
        Play s567 = play(PlayType.STRAIGHT,
                c(Suit.DIAMOND, Rank.R5), c(Suit.CLUB, Rank.R6), c(Suit.SPADE, Rank.R7));
        assertTrue(RuleService.canBeat(s456, s567), "567 能压 456");
        assertFalse(RuleService.canBeat(s567, s456), "456 不能压 567");
    }

    @Test
    public void enumerateFindsFlushOverStraight() {
        List<Card> hand = Arrays.asList(
                c(Suit.DIAMOND, Rank.RK), c(Suit.DIAMOND, Rank.R9), c(Suit.DIAMOND, Rank.R7),
                c(Suit.SPADE, Rank.R4), c(Suit.HEART, Rank.R5), c(Suit.CLUB, Rank.R6));
        List<Play> plays = RuleService.enumerateLegalPlays(hand, STRAIGHT_456, new com.tencent.wxcloudrun.game.model.RoomConfig());
        boolean hasFlush = plays.stream().anyMatch(p -> p.getType() == PlayType.FLUSH);
        assertTrue(hasFlush, "提示/托管枚举应把能压顺子的同花列出来");
    }
}