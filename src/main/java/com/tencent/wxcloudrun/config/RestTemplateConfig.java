package com.tencent.wxcloudrun.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.converter.StringHttpMessageConverter;
import org.springframework.web.client.RestTemplate;

import java.nio.charset.StandardCharsets;

/**
 * 单独放置，避免与 WebConfig 里的拦截器产生循环依赖
 */
@Configuration
public class RestTemplateConfig {

  @Bean
  public RestTemplate restTemplate() {
    RestTemplate restTemplate = new RestTemplate();
    // 微信部分接口返回 text/plain 且不带 charset，默认 ISO-8859-1 会把中文 errmsg 读乱码
    restTemplate.getMessageConverters().stream()
        .filter(c -> c instanceof StringHttpMessageConverter)
        .forEach(c -> ((StringHttpMessageConverter) c).setDefaultCharset(StandardCharsets.UTF_8));
    return restTemplate;
  }
}
