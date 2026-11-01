package com.tencent.wxcloudrun.game;

import com.tencent.wxcloudrun.game.model.Card;
import com.tencent.wxcloudrun.game.model.GameState;
import com.tencent.wxcloudrun.game.model.PlayerState;
import com.tencent.wxcloudrun.game.model.Rank;
import com.tencent.wxcloudrun.game.model.RoomConfig;
import com.tencent.wxcloudrun.game.model.Suit;
import com.tencent.wxcloudrun.game.service.GameManager;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/** 结算名次：按总分（抢分 + 手牌分）降序，60 分应排第一，而不是抢分 35 的玩家 */
public class SettlementRankTest {

    private static PlayerState player(String id, int seat, int captured, int total) {
        PlayerState p = new PlayerState(id, id, seat);
        p.setCapturedScore(captured);
        p.setTotalScore(total);
        return p;
    }

    @Test
    public void rankByTotalScoreNotCaptured() {
        GameState game = new GameState("G1", "R1", new RoomConfig());
        List<PlayerState> rank = new ArrayList<>(Arrays.asList(
                player("ZHER", 1, 35, 35),
                player("ME", 0, 30, 60),
                player("TEST", 2, 5, 5)
        ));
        for (PlayerState p : rank) {
            game.getHands().put(p.getPlayerId(), new ArrayList<Card>(Arrays.asList(
                    new Card("x" + p.getPlayerId(), Suit.SPADE, Rank.R4, 1))));
        }

        GameManager.sortSettlementRank(rank, game);

        assertEquals("ME", rank.get(0).getPlayerId(), "60 分应该排第一");
        assertEquals("ZHER", rank.get(1).getPlayerId(), "35 分排第二");
        assertEquals("TEST", rank.get(2).getPlayerId(), "5 分排最后");
    }

    @Test
    public void tieBreaksByCapturedThenFewerCards() {
        GameState game = new GameState("G2", "R1", new RoomConfig());
        PlayerState a = player("A", 0, 20, 40);
        PlayerState b = player("B", 1, 30, 40);
        PlayerState c = player("C", 2, 10, 40);
        List<PlayerState> rank = new ArrayList<>(Arrays.asList(a, b, c));
        game.getHands().put("A", new ArrayList<Card>());
        game.getHands().put("B", new ArrayList<Card>());
        game.getHands().put("C", new ArrayList<Card>());

        GameManager.sortSettlementRank(rank, game);

        assertEquals("B", rank.get(0).getPlayerId(), "同分时抢分高的靠前");
        assertEquals("A", rank.get(1).getPlayerId());
        assertEquals("C", rank.get(2).getPlayerId());
    }
}