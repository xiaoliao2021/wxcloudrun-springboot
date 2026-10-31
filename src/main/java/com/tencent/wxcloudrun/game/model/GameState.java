package com.tencent.wxcloudrun.game.model;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class GameState {
    public enum Phase { DEALING, PLAYING, REPLENISHING, SETTLING, FINISHED }
    private String gameId;
    private String roomId;
    private RoomConfig config;
    private Phase phase = Phase.DEALING;
    private final Map<String, List<Card>> hands = new LinkedHashMap<>();
    /** 每个玩家本轮之前抢到的牌（含 5/10/K 分牌），供客户端展示已出分牌 */
    private final Map<String, List<Card>> capturedCards = new LinkedHashMap<>();
    private final Map<String, Integer> capturedScores = new LinkedHashMap<>();
    private final Map<String, PlayerState> players = new LinkedHashMap<>();
    private List<Card> drawPile = new ArrayList<>();
    private List<Card> roundTableCards = new ArrayList<>();
    /** 本轮按先后顺序的每一手（含过牌），收轮清空，供客户端铺开本轮出牌区 */
    private final List<RoundAction> roundActions = new ArrayList<>();
    private Play currentPlay;
    private String turnPlayerId;
    private String lastPlayerId;
    private String roundWinnerId;
    private int passCount;
    private int roundTableScore;
    private long stateVersion;

    public GameState(String gameId, String roomId, RoomConfig config) {
        this.gameId = gameId; this.roomId = roomId; this.config = config;
    }
    public String getGameId() { return gameId; }
    public String getRoomId() { return roomId; }
    public RoomConfig getConfig() { return config; }
    public Phase getPhase() { return phase; }
    public void setPhase(Phase v) { phase = v; }
    public Map<String, List<Card>> getHands() { return hands; }
    public Map<String, List<Card>> getCapturedCards() { return capturedCards; }
    public Map<String, Integer> getCapturedScores() { return capturedScores; }
    public Map<String, PlayerState> getPlayers() { return players; }
    public List<Card> getDrawPile() { return drawPile; }
    public void setDrawPile(List<Card> v) { drawPile = v; }
    public List<Card> getRoundTableCards() { return roundTableCards; }
    public void setRoundTableCards(List<Card> v) { roundTableCards = v; }
    public List<RoundAction> getRoundActions() { return roundActions; }
    public Play getCurrentPlay() { return currentPlay; }
    public void setCurrentPlay(Play v) { currentPlay = v; }
    public String getTurnPlayerId() { return turnPlayerId; }
    public void setTurnPlayerId(String v) { turnPlayerId = v; }
    public String getLastPlayerId() { return lastPlayerId; }
    public void setLastPlayerId(String v) { lastPlayerId = v; }
    public String getRoundWinnerId() { return roundWinnerId; }
    public void setRoundWinnerId(String v) { roundWinnerId = v; }
    public int getPassCount() { return passCount; }
    public void setPassCount(int v) { passCount = v; }
    public int getRoundTableScore() { return roundTableScore; }
    public void setRoundTableScore(int v) { roundTableScore = v; }
    public long nextVersion() { return ++stateVersion; }
    public long getStateVersion() { return stateVersion; }
}
