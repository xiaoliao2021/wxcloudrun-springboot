package com.tencent.wxcloudrun.ws;

import com.tencent.wxcloudrun.config.ErrorCode;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/**
 * 房间写接口提交后再广播：事务没提交就推，订阅方会读到旧状态。
 */
@Component
public class RoomEventSubscriber {

  private final WsRealtimeBroker broker;

  public RoomEventSubscriber(@Autowired WsRealtimeBroker broker) {
    this.broker = broker;
  }

  @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
  public void onRoomChanged(RoomChangedEvent event) {
    if (event == null || event.getRoomId() == null) {
      return;
    }
    if (event.isDismissed()) {
      broker.broadcastRoomClosed(event.getRoomId(), ErrorCode.ROOM_NOT_FOUND.getCode(), "房间已解散");
    } else {
      broker.broadcastRoomState(event.getRoomId(), event.getReason(), event.getActors());
    }
  }
}
