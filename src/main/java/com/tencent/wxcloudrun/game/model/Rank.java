package com.tencent.wxcloudrun.game.model;

import com.fasterxml.jackson.annotation.JsonValue;

import java.util.HashMap;
import java.util.Map;

/**
 * 扑克点数。
 * rankOrder: 3 > 2 > A > K > Q > J > 10 > 9 > 8 > 7 > 6 > 5 > 4（普通牌型比较）
 * sequenceValue: 3 < 4 < ... < A（顺子比较，2 不能进顺子，A 只能作最大）
 */
public enum Rank {
    R4("4", 1, 4),
    R5("5", 2, 5),
    R6("6", 3, 6),
    R7("7", 4, 7),
    R8("8", 5, 8),
    R9("9", 6, 9),
    R10("10", 7, 10),
    RJ("J", 8, 11),
    RQ("Q", 9, 12),
    RK("K", 10, 13),
    RA("A", 11, 14),
    R2("2", 12, 0),   // 2 不能进顺子，sequenceValue = 0
    R3("3", 13, 3);

    private final String label;
    private final int rankOrder;
    private final int sequenceValue;

    Rank(String label, int rankOrder, int sequenceValue) {
        this.label = label;
        this.rankOrder = rankOrder;
        this.sequenceValue = sequenceValue;
    }

    @JsonValue
    public String getLabel() { return label; }
    public int getRankOrder() { return rankOrder; }
    public int getSequenceValue() { return sequenceValue; }

    private static final Map<String, Rank> BY_LABEL = new HashMap<>();
    static {
        for (Rank r : values()) BY_LABEL.put(r.label, r);
    }

    public static Rank fromLabel(String label) {
        Rank r = BY_LABEL.get(label);
        if (r == null) throw new IllegalArgumentException("invalid rank: " + label);
        return r;
    }

    /** 分牌：5=5分 10=10分 K=10分 其他=0 */
    public int getScore() {
        switch (this) {
            case R5: return 5;
            case R10: return 10;
            case RK: return 10;
            default: return 0;
        }
    }
}
