package com.tencent.wxcloudrun.controller;

import com.tencent.wxcloudrun.config.ApiResponse;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;

/**
 * 健康检查
 */
@RestController
public class HealthController {

  @GetMapping(value = {"/", "/health"})
  public ApiResponse health() {
    Map<String, Object> data = new HashMap<>(4);
    data.put("status", "UP");
    data.put("app", "wxcloudrun-springboot");
    data.put("time", LocalDateTime.now().toString());
    return ApiResponse.ok(data);
  }
}
