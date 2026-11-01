package com.tencent.wxcloudrun.game;

import com.tencent.wxcloudrun.game.model.Card;
import com.tencent.wxcloudrun.game.model.GameState;
import com.tencent.wxcloudrun.game.model.Play;
import com.tencent.wxcloudrun.game.model.PlayType;
import com.tencent.wxcloudrun.game.model.Rank;
import com.tencent.wxcloudrun.game.model.RoomConfig;
import com.tencent.wxcloudrun.game.model.Suit;
import com.tencent.wxcloudrun.game.service.GameManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/** 托管出牌策略回归：首出优先甩三张牌型，跟牌同张数且炸弹留到最后 */
public class TrusteeStrategyTest {

    private static Card c(String id, Suit s, Rank r) {
        return new Card(id, s, r, 1);
    }

    private static Play decide(GameState g, String pid) throws Exception {
        GameManager gm = new GameManager(new com.fasterxml.jackson.databind.ObjectMapper());
        Method m = GameManager.class.getDeclaredMethod("chooseTrusteePlay", GameState.class, String.class);
        m.setAccessible(true);
        return (Play) m.invoke(gm, g, pid);
    }

    private static GameState game(List<Card> hand) {
        GameState g = new GameState("G1", "R1", new RoomConfig());
        g.getHands().put("P1", hand);
        return g;
    }

    @Test
    public void firstPlayPrefersStraight() throws Exception {
        Play p = decide(game(Arrays.asList(
                c("a", Suit.SPADE, Rank.R4), c("b", Suit.HEART, Rank.R5), c("d", Suit.CLUB, Rank.R6),
                c("e", Suit.SPADE, Rank.R9), c("f", Suit.HEART, Rank.RK))), "P1");
        assertNotNull(p);
        assertEquals(3, p.getSize(), "首出应优先甩三张牌型");
        assertEquals(PlayType.STRAIGHT, p.getType());
    }

    @Test
    public void firstPlayFallsBackToSmallestSingle() throws Exception {
        Play p = decide(game(Arrays.asList(
                c("a", Suit.SPADE, Rank.R4), c("b", Suit.HEART, Rank.R9), c("d", Suit.CLUB, Rank.RK))), "P1");
        assertNotNull(p);
        assertEquals(1, p.getSize(), "没有三张牌型时出单张");
        assertEquals(Rank.R4, p.getCards().get(0).getRank(), "出最小的单张");
    }

    @Test
    public void followPairWithPair() throws Exception {
        GameState g = game(Arrays.asList(
                c("a", Suit.SPADE, Rank.R6), c("b", Suit.HEART, Rank.R6),
                c("d", Suit.CLUB, Rank.R3)));
        g.setCurrentPlay(new Play(PlayType.PAIR, Arrays.asList(
                c("x", Suit.SPADE, Rank.R5), c("y", Suit.HEART, Rank.R5))));
        Play p = decide(g, "P1");
        assertNotNull(p);
        assertEquals(2, p.getSize(), "跟对子必须同张数");
        assertEquals(PlayType.PAIR, p.getType());
        assertEquals(Rank.R6, p.getCards().get(0).getRank());
    }

    @Test
    public void followKeepsStraightOverBomb() throws Exception {
        // 桌面顺子 4-5-6；手上有更大的顺子 6-7-8，也有一组 510K（同花炸弹）
        GameState g = game(Arrays.asList(
                c("a", Suit.SPADE, Rank.R6), c("b", Suit.HEART, Rank.R7), c("d", Suit.CLUB, Rank.R8),
                c("k1", Suit.DIAMOND, Rank.R5), c("k2", Suit.DIAMOND, Rank.R10), c("k3", Suit.DIAMOND, Rank.RK)));
        g.setCurrentPlay(new Play(PlayType.STRAIGHT, Arrays.asList(
                c("x", Suit.SPADE, Rank.R4), c("y", Suit.HEART, Rank.R5), c("z", Suit.CLUB, Rank.R6))));
        Play p = decide(g, "P1");
        assertNotNull(p);
        assertEquals(PlayType.STRAIGHT, p.getType(), "普通牌能压时不动 510K 炸弹");
    }

    @Test
    public void followReturnsNullWhenCannotBeat() throws Exception {
        GameState g = game(Arrays.asList(
                c("a", Suit.SPADE, Rank.R4), c("b", Suit.HEART, Rank.R5)));
        g.setCurrentPlay(new Play(PlayType.PAIR, Arrays.asList(
                c("x", Suit.SPADE, Rank.RK), c("y", Suit.HEART, Rank.RK))));
        assertEquals(null, decide(g, "P1"), "压不过就返回 null（由调用方过牌）");
    }
}