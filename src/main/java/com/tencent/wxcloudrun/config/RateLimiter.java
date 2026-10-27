package com.tencent.wxcloudrun.config;

import org.springframework.stereotype.Component;

import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内固定窗口限流（单机够用；多实例部署时应换成 Redis 限流）
 */
@Component
public class RateLimiter {

  private final ConcurrentHashMap<String, Window> windows = new ConcurrentHashMap<>();

  public boolean tryAcquire(String key, int limit, long windowMillis) {
    long now = System.currentTimeMillis();
    Window window = windows.computeIfAbsent(key, k -> new Window(now));
    synchronized (window) {
      if (now - window.start >= windowMillis) {
        window.start = now;
        window.count = 0;
      }
      if (window.count >= limit) {
        return false;
      }
      window.count++;
      return true;
    }
  }

  /**
   * 清理长时间未使用的计数窗口，避免内存增长
   */
  public void cleanup(long maxIdleMillis) {
    long now = System.currentTimeMillis();
    Iterator<Map.Entry<String, Window>> it = windows.entrySet().iterator();
    while (it.hasNext()) {
      if (now - it.next().getValue().start > maxIdleMillis) {
        it.remove();
      }
    }
  }

  private static class Window {
    private long start;
    private int count;

    Window(long start) {
      this.start = start;
    }
  }
}
