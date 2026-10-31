package com.tencent.wxcloudrun.game.model;

/** 房间配置 */
public class RoomConfig {
    private int maxPlayers = 5;
    private int deckCount = 2;
    private boolean allowTripleWithTwo = false;
    private boolean allowFourWithOne = false;
    private boolean allowFiveCardFlush = false;
    /** 是否允许 5 张牌型（顺子/三带二/四带一/同花/同花顺）；关掉后只能出 3 张及以下的牌型 */
    private boolean allowFiveCardPlays = false;

    public RoomConfig() {}

    public int getMaxPlayers() { return maxPlayers; }
    public void setMaxPlayers(int maxPlayers) { this.maxPlayers = maxPlayers; }
    public int getDeckCount() { return deckCount; }
    public void setDeckCount(int deckCount) { this.deckCount = deckCount; }
    public boolean isAllowTripleWithTwo() { return allowTripleWithTwo; }
    public void setAllowTripleWithTwo(boolean allowTripleWithTwo) { this.allowTripleWithTwo = allowTripleWithTwo; }
    public boolean isAllowFourWithOne() { return allowFourWithOne; }
    public void setAllowFourWithOne(boolean allowFourWithOne) { this.allowFourWithOne = allowFourWithOne; }
    public boolean isAllowFiveCardFlush() { return allowFiveCardFlush; }
    public void setAllowFiveCardFlush(boolean allowFiveCardFlush) { this.allowFiveCardFlush = allowFiveCardFlush; }
    public boolean isAllowFiveCardPlays() { return allowFiveCardPlays; }
    public void setAllowFiveCardPlays(boolean allowFiveCardPlays) { this.allowFiveCardPlays = allowFiveCardPlays; }
}
