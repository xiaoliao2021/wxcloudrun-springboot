package com.tencent.wxcloudrun.game.protocol;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.UUID;

public class EventSender {
    private final ObjectMapper mapper;
    public EventSender(ObjectMapper mapper) { this.mapper = mapper; }
    public String event(String name, String requestId, Object payload) {
        try {
            java.util.Map<String, Object> envelope = new java.util.LinkedHashMap<>();
            envelope.put("event", name);
            envelope.put("eventId", UUID.randomUUID().toString());
            if (requestId != null) envelope.put("requestId", requestId);
            envelope.put("payload", payload);
            return mapper.writeValueAsString(envelope);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
}
