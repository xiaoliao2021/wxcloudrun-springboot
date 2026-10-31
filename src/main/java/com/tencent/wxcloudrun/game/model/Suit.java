package com.tencent.wxcloudrun.game.model;

/** 扑克花色 */
public enum Suit {
    SPADE(4), HEART(3), CLUB(2), DIAMOND(1);

    private final int order;

    Suit(int order) { this.order = order; }

    public int getOrder() { return order; }

    public static Suit fromString(String s) {
        return valueOf(s.toUpperCase());
    }
}
