package com.tencent.wxcloudrun.game.model;

public class PlayerState {
    private String playerId;
    private String nickname;
    private String avatar;
    private int seatNo;
    private boolean ready;
    private boolean online = true;
    private boolean trustee;
    private boolean settled;
    private int capturedScore;
    private int handsScore;
    private int totalScore;
    private int rankNo;

    public PlayerState() {}
    public PlayerState(String playerId, String nickname, int seatNo) {
        this.playerId = playerId; this.nickname = nickname; this.seatNo = seatNo;
    }
    public String getPlayerId() { return playerId; }
    public void setPlayerId(String v) { playerId = v; }
    public String getNickname() { return nickname; }
    public void setNickname(String v) { nickname = v; }
    public String getAvatar() { return avatar; }
    public void setAvatar(String v) { avatar = v; }
    public int getSeatNo() { return seatNo; }
    public void setSeatNo(int v) { seatNo = v; }
    public boolean isReady() { return ready; }
    public void setReady(boolean v) { ready = v; }
    public boolean isOnline() { return online; }
    public void setOnline(boolean v) { online = v; }
    public boolean isTrustee() { return trustee; }
    public void setTrustee(boolean v) { trustee = v; }
    public boolean isSettled() { return settled; }
    public void setSettled(boolean v) { settled = v; }
    public int getCapturedScore() { return capturedScore; }
    public void setCapturedScore(int v) { capturedScore = v; }
    public int getHandsScore() { return handsScore; }
    public void setHandsScore(int v) { handsScore = v; }
    public int getTotalScore() { return totalScore; }
    public void setTotalScore(int v) { totalScore = v; }
    public int getRankNo() { return rankNo; }
    public void setRankNo(int v) { rankNo = v; }
}
