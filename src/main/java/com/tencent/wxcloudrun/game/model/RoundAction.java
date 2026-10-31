package com.tencent.wxcloudrun.game.model;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;

/**
 * 本轮桌上的一条动作（出牌或过牌），按发生先后保留，收轮补牌时清空。
 * 客户端据此把本轮每一家出的牌从左到右铺开，而不是只剩最大的一手。
 */
public class RoundAction {
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String playerId;
    private final boolean passed;
    private final List<Card> cards;
    private final String playType;
    private final int score;

    private RoundAction(String playerId, boolean passed, List<Card> cards, String playType, int score) {
        this.playerId = playerId;
        this.passed = passed;
        this.cards = cards == null ? new ArrayList<>() : new ArrayList<>(cards);
        this.playType = playType;
        this.score = score;
    }

    public static RoundAction play(String playerId, List<Card> cards, String playType, int score) {
        return new RoundAction(playerId, false, cards, playType, score);
    }

    public static RoundAction pass(String playerId) {
        return new RoundAction(playerId, true, new ArrayList<>(), null, 0);
    }

    public String getPlayerId() { return playerId; }
    public boolean isPassed() { return passed; }
    public List<Card> getCards() { return cards; }
    public String getPlayType() { return playType; }
    public int getScore() { return score; }

    public ObjectNode toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("playerId", playerId);
        node.put("passed", passed);
        node.put("playType", playType == null ? "" : playType);
        node.put("score", score);
        ArrayNode arr = node.putArray("cards");
        for (Card c : cards) {
            ObjectNode cn = arr.addObject();
            cn.put("cardId", c.getCardId());
            cn.put("suit", c.getSuit().name());
            cn.put("rank", c.getRank().getLabel());
            cn.put("deckNo", c.getDeckNo());
        }
        return node;
    }
}