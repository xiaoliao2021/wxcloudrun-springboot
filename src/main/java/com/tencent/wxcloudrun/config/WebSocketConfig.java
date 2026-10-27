package com.tencent.wxcloudrun.config;

import com.tencent.wxcloudrun.ws.RoomWebSocketHandler;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;
import org.springframework.web.socket.server.standard.ServletServerContainerFactoryBean;

/**
 * 长连接入口。文档 §14 里 /ws 和 /v1/ws 还没定，两个都注册上，前端挑一个即可。
 */
@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

  private final RoomWebSocketHandler roomWebSocketHandler;

  public WebSocketConfig(@Autowired RoomWebSocketHandler roomWebSocketHandler) {
    this.roomWebSocketHandler = roomWebSocketHandler;
  }

  @Override
  public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
    registry.addHandler(roomWebSocketHandler, "/ws", "/v1/ws").setAllowedOrigins("*");
  }

  @Bean
  public ServletServerContainerFactoryBean createWebSocketContainer() {
    ServletServerContainerFactoryBean container = new ServletServerContainerFactoryBean();
    // 单帧上限 64KB，超了容器直接关连接
    container.setMaxTextMessageBufferSize(64 * 1024);
    container.setMaxBinaryMessageBufferSize(64 * 1024);
    // 容器层兜底：比应用层的 60 秒空闲超时宽松，正常情况应用层先断开
    container.setMaxSessionIdleTimeout(120 * 1000L);
    return container;
  }
}
