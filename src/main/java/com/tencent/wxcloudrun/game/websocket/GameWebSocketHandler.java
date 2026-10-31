package com.tencent.wxcloudrun.game.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wxcloudrun.game.protocol.EventEnvelope;
import com.tencent.wxcloudrun.game.service.GameManager;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketSession;
import java.io.IOException;

public class GameWebSocketHandler implements WebSocketHandler {
    private final ObjectMapper mapper;
    private final GameManager manager;
    public GameWebSocketHandler(ObjectMapper mapper, GameManager manager) { this.mapper = mapper; this.manager = manager; }
    @Override public void afterConnectionEstablished(WebSocketSession session) { manager.onOpen(session); }
    @Override public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws IOException {
        if (!(message instanceof TextMessage)) return;
        EventEnvelope envelope = mapper.readValue(((TextMessage) message).getPayload(), EventEnvelope.class);
        String playerId = session.getAttributes().get("playerId") == null ? null : String.valueOf(session.getAttributes().get("playerId"));
        manager.handle(session, playerId, envelope.getEvent(), envelope.getRequestId(), envelope.getRoomId(), envelope.getGameId(), envelope.getPayload());
    }
    @Override public void handleTransportError(WebSocketSession session, Throwable exception) throws Exception { if (session.isOpen()) session.close(CloseStatus.SERVER_ERROR); }
    @Override public void afterConnectionClosed(WebSocketSession session, CloseStatus closeStatus) { manager.onClose(session); }
    @Override public boolean supportsPartialMessages() { return false; }
}
