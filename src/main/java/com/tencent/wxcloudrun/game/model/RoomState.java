package com.tencent.wxcloudrun.game.model;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

public class RoomState {
    public enum Status { WAITING, PLAYING, REPLENISHING, SETTLING }
    private String roomId;
    private String ownerId;
    /** 房间名称（全局唯一） */
    private String name = "";
    /** 6 位数字密码，空串表示无密码 */
    private String password = "";
    private RoomConfig config;
    private Status status = Status.WAITING;
    private String gameId;
    private long stateVersion;
    private final List<PlayerState> players = new ArrayList<>();
    /** 上一局赢家：下一局沿用他先出 */
    private String lastWinnerId;
    /** 上一局参与玩家：人员一变就重新掷骰 */
    private Set<String> lastPlayerIds = new LinkedHashSet<>();

    public RoomState(String roomId, String ownerId, RoomConfig config) {
        this.roomId = roomId; this.ownerId = ownerId; this.config = config;
    }
    public String getRoomId() { return roomId; }
    public String getOwnerId() { return ownerId; }
    public void setOwnerId(String v) { ownerId = v; }
    public String getName() { return name; }
    public void setName(String v) { name = v == null ? "" : v; }
    public String getPassword() { return password; }
    public void setPassword(String v) { password = v == null ? "" : v; }
    public boolean hasPassword() { return password != null && !password.isEmpty(); }
    public RoomConfig getConfig() { return config; }
    public void setConfig(RoomConfig v) { config = v; }
    public Status getStatus() { return status; }
    public void setStatus(Status v) { status = v; }
    public String getGameId() { return gameId; }
    public void setGameId(String v) { gameId = v; }
    public long getStateVersion() { return stateVersion; }
    public void incrementVersion() { stateVersion++; }
    public List<PlayerState> getPlayers() { return players; }
    public String getLastWinnerId() { return lastWinnerId; }
    public void setLastWinnerId(String v) { lastWinnerId = v; }
    public Set<String> getLastPlayerIds() { return lastPlayerIds; }
    public void setLastPlayerIds(Set<String> v) { lastPlayerIds = v == null ? new LinkedHashSet<>() : v; }
    public PlayerState findPlayer(String playerId) {
        for (PlayerState p : players) if (p.getPlayerId().equals(playerId)) return p;
        return null;
    }
}
