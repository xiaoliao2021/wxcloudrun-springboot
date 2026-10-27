package com.tencent.wxcloudrun.ws;

import java.util.Collections;
import java.util.List;

/**
 * 房间状态变化事件。HTTP 写接口在事务内发布，由 RoomEventSubscriber 在【提交之后】广播，
 * 避免订阅方读到未提交的数据。
 */
public class RoomChangedEvent {

  private final Long roomId;
  /** init / joined / left / ready / config / started / sync */
  private final String reason;
  /** 引起这次变化的 userId（原始 Long，广播时才转成 u_ 前缀） */
  private final List<Long> actors;
  /** 房间是否已解散（最后一人离开 / 房主解散） */
  private final boolean dismissed;

  public RoomChangedEvent(Long roomId, String reason, List<Long> actors, boolean dismissed) {
    this.roomId = roomId;
    this.reason = reason;
    this.actors = actors == null ? Collections.<Long>emptyList() : actors;
    this.dismissed = dismissed;
  }

  public Long getRoomId() {
    return roomId;
  }

  public String getReason() {
    return reason;
  }

  public List<Long> getActors() {
    return actors;
  }

  public boolean isDismissed() {
    return dismissed;
  }
}
