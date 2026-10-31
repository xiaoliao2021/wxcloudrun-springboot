package com.tencent.wxcloudrun.game.service;

import com.tencent.wxcloudrun.game.model.Card;
import com.tencent.wxcloudrun.game.model.Rank;
import com.tencent.wxcloudrun.game.model.RoomConfig;
import com.tencent.wxcloudrun.game.model.Suit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** 牌堆工厂：deckCount 副牌，每副 52 张无王 */
public class DeckFactory {

    public static List<Card> createDeck(int deckCount) {
        List<Card> deck = new ArrayList<>(deckCount * 52);
        String[] suitNames = {"SPADE", "HEART", "CLUB", "DIAMOND"};
        for (int d = 0; d < deckCount; d++) {
            for (String s : suitNames) {
                Suit suit = Suit.fromString(s);
                for (Rank rank : Rank.values()) {
                    deck.add(new Card(s + "-" + rank.getLabel() + "-" + d, suit, rank, d));
                }
            }
        }
        return deck;
    }

    public static void shuffle(List<Card> deck) {
        Collections.shuffle(deck);
    }

    /** 校验 maxPlayers * 5 <= deckCount * 52 */
    public static boolean validateCapacity(RoomConfig config) {
        return config.getMaxPlayers() * 5L <= config.getDeckCount() * 52L;
    }

    /** 发牌前断言无 JOKER */
    public static void assertNoJoker(List<Card> cards) {
        for (Card c : cards) {
            if (c == null || c.getRank() == null || c.getSuit() == null) {
                throw new IllegalStateException("INVALID_CARD");
            }
        }
    }
}
