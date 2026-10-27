package com.tencent.wxcloudrun.config;

import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

  private final TraceInterceptor traceInterceptor;
  private final AuthInterceptor authInterceptor;

  public WebConfig(TraceInterceptor traceInterceptor, AuthInterceptor authInterceptor) {
    this.traceInterceptor = traceInterceptor;
    this.authInterceptor = authInterceptor;
  }

  @Override
  public void addInterceptors(InterceptorRegistry registry) {
    registry.addInterceptor(traceInterceptor).addPathPatterns("/**");

    // /v1/ws 是 WebSocket 握手路径，握手时还没有 token（token 在连接后的第一帧里带），必须放行
    registry.addInterceptor(authInterceptor)
        .addPathPatterns("/v1/**")
        .excludePathPatterns("/v1/auth/login", "/v1/auth/refresh", "/v1/ws");
  }
}
