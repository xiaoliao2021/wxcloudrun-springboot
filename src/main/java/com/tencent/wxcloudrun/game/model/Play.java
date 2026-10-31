package com.tencent.wxcloudrun.game.model;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/** 识别出的一手牌（当前桌面牌 / 新出牌） */
public class Play {
    private PlayType type;
    private List<Card> cards;
    /** 主体牌（三张/三带二/四带一的 rank 主体；顺子/同花顺的最大牌；同花全组） */
    private List<Card> mainCards;
    private int size;

    private static final ObjectMapper MAPPER = new ObjectMapper();

    public Play(PlayType type, List<Card> cards) {
        this.type = type;
        this.cards = cards;
        this.size = cards.size();
        this.mainCards = extractMain(type, cards);
    }

    private static List<Card> extractMain(PlayType type, List<Card> cards) {
        List<Card> main = new ArrayList<>();
        switch (type) {
            case SINGLE:
            case PAIR:
            case TRIPLE:
                main.addAll(cards);
                break;
            case TRIPLE_WITH_TWO:
            case FOUR_WITH_ONE: {
                // 主体为出现次数 >=3 的同 rank 组（对带牌为 4+1 或 3+2 结构，均正确）
                java.util.Map<Rank, List<Card>> groups = new java.util.LinkedHashMap<>();
                for (Card c : cards) groups.computeIfAbsent(c.getRank(), k -> new ArrayList<>()).add(c);
                List<Card> best = null;
                for (List<Card> g : groups.values()) {
                    if (g.size() >= 3 && (best == null || g.size() > best.size())) best = g;
                }
                main.addAll(best);
                break;
            }
            case STRAIGHT:
            case STRAIGHT_FLUSH: {
                // 主体 = 最大 sequenceValue 的那张（用于比较）
                Card max = cards.get(0);
                for (Card c : cards) {
                    if (c.getRank().getSequenceValue() > max.getRank().getSequenceValue()
                            || (c.getRank().getSequenceValue() == max.getRank().getSequenceValue()
                                && c.getSuit().getOrder() > max.getSuit().getOrder())) {
                        max = c;
                    }
                }
                main.add(max);
                break;
            }
            default:
                main.addAll(cards);
        }
        return main;
    }

    public PlayType getType() { return type; }
    public List<Card> getCards() { return cards; }
    public List<Card> getMainCards() { return mainCards; }
    public int getSize() { return size; }

    /** 主体 rank */
    public Rank getMainRank() { return mainCards.get(0).getRank(); }

    /** 主体最大花色 */
    public Suit getMainMaxSuit() {
        Suit max = mainCards.get(0).getSuit();
        for (Card c : mainCards) {
            if (c.getSuit().getOrder() > max.getOrder()) max = c.getSuit();
        }
        return max;
    }

    public int getScore() {
        int s = 0;
        for (Card c : cards) s += c.getScore();
        return s;
    }

    public ObjectNode toJson() {
        ObjectNode node = MAPPER.createObjectNode();
        node.put("playType", type.name());
        node.put("size", size);
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

    public static Play fromJson(JsonNode node) {
        PlayType type = PlayType.valueOf(node.get("playType").asText());
        List<Card> cards = new ArrayList<>();
        node.get("cards").forEach(o -> cards.add(new Card(
                o.get("cardId").asText(),
                Suit.fromString(o.get("suit").asText()),
                Rank.fromLabel(o.get("rank").asText()),
                o.get("deckNo").asInt())));
        return new Play(type, cards);
    }
}
