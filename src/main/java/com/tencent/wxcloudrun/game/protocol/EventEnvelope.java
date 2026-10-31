package com.tencent.wxcloudrun.game.protocol;

import com.fasterxml.jackson.databind.JsonNode;

public class EventEnvelope {
    private String event;
    private String requestId;
    private String eventId;
    private long seq;
    private String roomId;
    private String gameId;
    private JsonNode payload;
    public String getEvent() { return event; }
    public void setEvent(String v) { event = v; }
    public String getRequestId() { return requestId; }
    public void setRequestId(String v) { requestId = v; }
    public String getEventId() { return eventId; }
    public void setEventId(String v) { eventId = v; }
    public long getSeq() { return seq; }
    public void setSeq(long v) { seq = v; }
    public String getRoomId() { return roomId; }
    public void setRoomId(String v) { roomId = v; }
    public String getGameId() { return gameId; }
    public void setGameId(String v) { gameId = v; }
    public JsonNode getPayload() { return payload; }
    public void setPayload(JsonNode v) { payload = v; }
}
