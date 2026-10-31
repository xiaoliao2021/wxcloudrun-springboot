package com.tencent.wxcloudrun.game.service;

import com.tencent.wxcloudrun.game.model.Card;
import com.tencent.wxcloudrun.game.model.Play;
import com.tencent.wxcloudrun.game.model.PlayType;
import com.tencent.wxcloudrun.game.model.Rank;
import com.tencent.wxcloudrun.game.model.RoomConfig;
import com.tencent.wxcloudrun.game.model.Suit;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** 510K 规则引擎：牌型识别、压牌比较和托管可出牌枚举。 */
public class RuleService {
    private RuleService() {}

    public static class RuleException extends RuntimeException {
        public final String code;
        public RuleException(String code) { super(code); this.code = code; }
    }

    public static Play parsePlay(List<Card> cards, RoomConfig config) {
        if (cards == null || config == null || cards.isEmpty()) throw new RuleException("INVALID_CARD");
        Set<String> ids = new HashSet<>();
        for (Card card : cards) {
            if (card == null || card.getCardId() == null || card.getRank() == null || card.getSuit() == null
                    || !ids.add(card.getCardId())) throw new RuleException("INVALID_CARD");
        }
        switch (cards.size()) {
            case 1: return new Play(PlayType.SINGLE, cards);
            case 2:
                if (isSameRank(cards)) return new Play(PlayType.PAIR, cards);
                throw new RuleException("INVALID_PLAY_TYPE");
            case 3: return parseThree(cards);
            case 5: return parseFive(cards, config);
            default: throw new RuleException("INVALID_PLAY_TYPE");
        }
    }

    private static Play parseThree(List<Card> cards) {
        if (isSuited510K(cards)) return new Play(PlayType.SUITED_FIVE_TEN_KING, cards);
        if (is510K(cards)) return new Play(PlayType.FIVE_TEN_KING, cards);
        if (isStraightFlush(cards)) return new Play(PlayType.STRAIGHT_FLUSH, cards);
        if (isStraight(cards)) return new Play(PlayType.STRAIGHT, cards);
        if (isFlush(cards)) return new Play(PlayType.FLUSH, cards);
        if (isSameRank(cards)) return new Play(PlayType.TRIPLE, cards);
        throw new RuleException("INVALID_PLAY_TYPE");
    }

    private static Play parseFive(List<Card> cards, RoomConfig config) {
        // 房间关了「5 张牌型」时，5 张一律不允许出
        if (!config.isAllowFiveCardPlays()) throw new RuleException("FIVE_CARD_DISABLED");
        if (isSameRank(cards) || isFourWithOneShape(cards)) {
            if (!config.isAllowFourWithOne()) throw new RuleException("PLAY_TYPE_DISABLED");
            return new Play(PlayType.FOUR_WITH_ONE, cards);
        }
        if (isTripleWithTwoShape(cards)) {
            if (!config.isAllowTripleWithTwo()) throw new RuleException("PLAY_TYPE_DISABLED");
            return new Play(PlayType.TRIPLE_WITH_TWO, cards);
        }
        if (isStraightFlush(cards)) return new Play(PlayType.STRAIGHT_FLUSH, cards);
        if (isStraight(cards)) return new Play(PlayType.STRAIGHT, cards);
        if (isFlush(cards)) {
            if (!config.isAllowFiveCardFlush()) throw new RuleException("FLUSH_FIVE_DISABLED");
            return new Play(PlayType.FLUSH, cards);
        }
        throw new RuleException("INVALID_PLAY_TYPE");
    }

    public static boolean isSameRank(List<Card> cards) {
        if (cards == null || cards.isEmpty()) return false;
        Rank rank = cards.get(0).getRank();
        for (Card card : cards) if (card.getRank() != rank) return false;
        return true;
    }

    public static boolean isFourWithOneShape(List<Card> cards) {
        Map<Rank, Integer> count = countByRank(cards);
        boolean four = false, one = false;
        for (Integer value : count.values()) {
            if (value == 4) four = true;
            else if (value == 1) one = true;
            else return false;
        }
        return four && one && count.size() == 2;
    }

    public static boolean isTripleWithTwoShape(List<Card> cards) {
        Map<Rank, Integer> count = countByRank(cards);
        boolean triple = false, pair = false;
        for (Integer value : count.values()) {
            if (value == 3) triple = true;
            else if (value == 2) pair = true;
            else return false;
        }
        return triple && pair && count.size() == 2;
    }

    public static boolean is510K(List<Card> cards) {
        if (cards == null || cards.size() != 3) return false;
        Set<Rank> ranks = new HashSet<>();
        for (Card card : cards) ranks.add(card.getRank());
        return ranks.size() == 3 && ranks.contains(Rank.R5) && ranks.contains(Rank.R10) && ranks.contains(Rank.RK);
    }

    public static boolean isSuited510K(List<Card> cards) {
        if (!is510K(cards)) return false;
        Suit suit = cards.get(0).getSuit();
        for (Card card : cards) if (card.getSuit() != suit) return false;
        return true;
    }

    public static boolean isFlush(List<Card> cards) {
        if (cards == null || cards.size() < 3) return false;
        Suit suit = cards.get(0).getSuit();
        for (Card card : cards) if (card.getSuit() != suit) return false;
        return true;
    }

    /** 顺子只允许 3/5 张；2 不可用，A 只能作为最大牌。 */
    public static boolean isStraight(List<Card> cards) {
        if (cards == null || (cards.size() != 3 && cards.size() != 5)) return false;
        List<Integer> values = new ArrayList<>();
        for (Card card : cards) {
            int value = card.getRank().getSequenceValue();
            if (value == 0 || values.contains(value)) return false;
            values.add(value);
        }
        Collections.sort(values);
        for (int i = 1; i < values.size(); i++) if (values.get(i) != values.get(i - 1) + 1) return false;
        return true;
    }

    public static boolean isStraightFlush(List<Card> cards) {
        return isFlush(cards) && isStraight(cards);
    }

    private static Map<Rank, Integer> countByRank(List<Card> cards) {
        Map<Rank, Integer> count = new EnumMap<>(Rank.class);
        for (Card card : cards) count.put(card.getRank(), count.getOrDefault(card.getRank(), 0) + 1);
        return count;
    }

    // ==================== 比较 ====================

    /** newPlay 是否严格大于 currentPlay。 */
    public static boolean canBeat(Play currentPlay, Play newPlay) {
        if (newPlay == null) return false;
        if (currentPlay == null) return true;
        if (currentPlay.getSize() != newPlay.getSize()) return false;

        if (newPlay.getType() == PlayType.FOUR_WITH_ONE && currentPlay.getType() == PlayType.TRIPLE_WITH_TWO) return true;
        if (newPlay.getType() == PlayType.TRIPLE_WITH_TWO && currentPlay.getType() == PlayType.FOUR_WITH_ONE) return false;
        // 产品规则：3 张三张（同点数）可以压 3 张顺子、3 张同花（非标准顺序）
        if (newPlay.getType() == PlayType.TRIPLE && newPlay.getSize() == 3
                && (currentPlay.getType() == PlayType.STRAIGHT || currentPlay.getType() == PlayType.FLUSH)
                && currentPlay.getSize() == 3) return true;

        int newLevel = specialLevel(newPlay.getType());
        int oldLevel = specialLevel(currentPlay.getType());
        if (newLevel != oldLevel) return newLevel > oldLevel;
        if (newLevel > 0) return compareSameType(currentPlay, newPlay) > 0;
        if (currentPlay.getType() != newPlay.getType()) return false;
        return compareSameType(currentPlay, newPlay) > 0;
    }

    /** 0=普通，1=同花顺，2=510K，3=同花510K。 */
    private static int specialLevel(PlayType type) {
        if (type == PlayType.STRAIGHT_FLUSH) return 1;
        if (type == PlayType.FIVE_TEN_KING) return 2;
        if (type == PlayType.SUITED_FIVE_TEN_KING) return 3;
        return 0;
    }

    /** 返回 current 与 candidate 的比较结果：candidate > current 时返回正数。 */
    private static int compareSameType(Play current, Play candidate) {
        PlayType type = candidate.getType();
        switch (type) {
            case SINGLE:
            case PAIR:
            case TRIPLE:
            case TRIPLE_WITH_TWO:
            case FOUR_WITH_ONE:
                int rank = Integer.compare(candidate.getMainRank().getRankOrder(), current.getMainRank().getRankOrder());
                if (rank != 0) return rank;
                return Integer.compare(candidate.getMainMaxSuit().getOrder(), current.getMainMaxSuit().getOrder());
            case STRAIGHT:
            case STRAIGHT_FLUSH:
                int sequence = Integer.compare(candidate.getMainRank().getSequenceValue(), current.getMainRank().getSequenceValue());
                if (sequence != 0) return sequence;
                return Integer.compare(candidate.getMainMaxSuit().getOrder(), current.getMainMaxSuit().getOrder());
            case FLUSH:
                return compareFlush(current.getCards(), candidate.getCards());
            case FIVE_TEN_KING:
                return Integer.compare(maxSuitOfRank(candidate.getCards(), Rank.RK).getOrder(),
                        maxSuitOfRank(current.getCards(), Rank.RK).getOrder());
            case SUITED_FIVE_TEN_KING:
                return Integer.compare(candidate.getCards().get(0).getSuit().getOrder(),
                        current.getCards().get(0).getSuit().getOrder());
            default: return 0;
        }
    }

    private static int compareFlush(List<Card> current, List<Card> candidate) {
        List<Card> a = new ArrayList<>(current);
        List<Card> b = new ArrayList<>(candidate);
        Comparator<Card> comparator = Comparator.comparingInt((Card c) -> c.getRank().getRankOrder())
                .thenComparingInt(c -> c.getSuit().getOrder()).reversed();
        a.sort(comparator); b.sort(comparator);
        for (int i = 0; i < a.size(); i++) {
            int rank = Integer.compare(b.get(i).getRank().getRankOrder(), a.get(i).getRank().getRankOrder());
            if (rank != 0) return rank;
        }
        return Integer.compare(maxSuit(b).getOrder(), maxSuit(a).getOrder());
    }

    private static Suit maxSuitOfRank(List<Card> cards, Rank rank) {
        Suit result = Suit.DIAMOND;
        for (Card card : cards) if (card.getRank() == rank && card.getSuit().getOrder() > result.getOrder()) result = card.getSuit();
        return result;
    }

    private static Suit maxSuit(List<Card> cards) {
        Suit result = Suit.DIAMOND;
        for (Card card : cards) if (card.getSuit().getOrder() > result.getOrder()) result = card.getSuit();
        return result;
    }

    public static int calculateCardScore(List<Card> cards) {
        int result = 0;
        for (Card card : cards) result += card.getScore();
        return result;
    }

    // ==================== 托管牌枚举 ====================

    /** 枚举手牌中所有合法牌型，可选地筛出能压当前牌的牌。 */
    public static List<Play> enumerateLegalPlays(List<Card> hand, Play current, RoomConfig config) {
        List<Play> result = new ArrayList<>();
        if (hand == null || hand.isEmpty()) return result;
        for (int size : new int[]{1, 2, 3, 5}) {
            combine(hand, size, 0, new ArrayList<>(), result, current, config);
        }
        result.sort(Comparator.comparingInt(Play::getSize).thenComparing(p -> p.getType().ordinal()));
        return result;
    }

    private static void combine(List<Card> hand, int target, int start, List<Card> selected,
                                List<Play> output, Play current, RoomConfig config) {
        if (selected.size() == target) {
            try {
                Play play = parsePlay(new ArrayList<>(selected), config);
                if (current == null || canBeat(current, play)) output.add(play);
            } catch (RuleException ignored) { }
            return;
        }
        for (int i = start; i <= hand.size() - (target - selected.size()); i++) {
            selected.add(hand.get(i));
            combine(hand, target, i + 1, selected, output, current, config);
            selected.remove(selected.size() - 1);
        }
    }
}
