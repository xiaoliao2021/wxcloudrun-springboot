package com.tencent.wxcloudrun.dao;

import com.tencent.wxcloudrun.model.Room;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RoomMapper {

  Room selectById(@Param("id") Long id);

  Room selectByRoomNo(@Param("roomNo") String roomNo);

  List<Room> selectLobbyRooms(@Param("limit") Integer limit);

  int insert(Room room);

  int updateStatus(@Param("id") Long id, @Param("status") Integer status);

  int updateOwner(@Param("id") Long id, @Param("ownerUserId") Long ownerUserId);

  int updateConfig(Room room);

  /**
   * 改配置 + 切状态一次写完，避免开局时连着三条 UPDATE 把 revision 顶高 3
   */
  int updateConfigAndStatus(Room room);

  /**
   * 刷新 updated_at，用于客户端轮询判断房间是否有变化
   */
  int touch(@Param("id") Long id);
}
