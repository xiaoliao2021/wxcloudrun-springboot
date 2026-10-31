package com.tencent.wxcloudrun.game.model;

/** 牌型枚举 */
public enum PlayType {
    SINGLE("单张", 1),
    PAIR("对子", 2),
    TRIPLE("三张", 3),
    TRIPLE_WITH_TWO("三带二", 5),
    FOUR_WITH_ONE("四带一", 5),
    STRAIGHT("顺子", -1),        // 3 或 5
    FLUSH("同花", -1),           // 3 或 5
    STRAIGHT_FLUSH("同花顺", -1), // 3 或 5
    FIVE_TEN_KING("510K", 3),
    SUITED_FIVE_TEN_KING("同花510K", 3);

    private final String name;
    private final int fixedSize;

    PlayType(String name, int fixedSize) {
        this.name = name;
        this.fixedSize = fixedSize;
    }

    public String getName() { return name; }
    public int getFixedSize() { return fixedSize; }

    public boolean isSizeValid(int n) {
        return fixedSize > 0 ? fixedSize == n : (n == 3 || n == 5);
    }

    /** 是否特殊牌型（跨类型总序：同花510K > 510K > 同花顺 > 普通牌型） */
    public boolean isSpecial() {
        return this == SUITED_FIVE_TEN_KING || this == FIVE_TEN_KING || this == STRAIGHT_FLUSH;
    }
}
