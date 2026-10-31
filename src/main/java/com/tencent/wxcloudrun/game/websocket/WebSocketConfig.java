package com.tencent.wxcloudrun.game.websocket;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wxcloudrun.game.service.GameManager;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {
    private final ObjectMapper mapper;
    private final GameManager manager;
    public WebSocketConfig(ObjectMapper mapper) { this.mapper = mapper; this.manager = new GameManager(mapper); }
    @Override public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(new GameWebSocketHandler(mapper, manager), "/ws/game").setAllowedOrigins("*");
    }
}
