package com.tencent.wxcloudrun.service;

import com.tencent.wxcloudrun.dto.RoomBriefVo;
import com.tencent.wxcloudrun.dto.RoomConfig;
import com.tencent.wxcloudrun.dto.RoomVo;

import java.util.List;

public interface RoomService {

  /**
   * 创建房间，调用者成为房主
   */
  RoomVo create(Long userId, RoomConfig config);

  /**
   * 用房号加入
   */
  RoomVo join(Long userId, String code);

  /**
   * 离开房间，最后一人离开时散房（返回 null）
   */
  RoomVo leave(Long userId, Long roomId);

  /**
   * 准备 / 取消准备
   */
  RoomVo setReady(Long userId, Long roomId, boolean ready);

  /**
   * 房主改规则（不开始对局），改完推送 room.state(reason=config)
   */
  RoomVo updateConfig(Long userId, Long roomId, RoomConfig config);

  /**
   * 房主开局
   */
  RoomVo start(Long userId, Long roomId, RoomConfig config);

  /**
   * 一局结束：房间回到等待状态、清掉准备标记，推 room.state(ended)
   */
  RoomVo finishGame(Long roomId);

  /**
   * 房间详情
   */
  RoomVo detail(Long userId, Long roomId);

  /**
   * 我所在的房间，不在则 null
   */
  RoomVo mine(Long userId);

  /**
   * 可加入的房间列表
   */
  List<RoomBriefVo> list(Long userId);

  /**
   * 取房间全量快照（给 WS 推送用，不做成员校验）；房间不存在或已解散返回 null
   */
  RoomVo snapshot(Long roomId);
}
