package com.tencent.wxcloudrun.dao;

import com.tencent.wxcloudrun.model.RoomPlayer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

@Mapper
public interface RoomPlayerMapper {

  List<RoomPlayer> selectByRoomId(@Param("roomId") Long roomId);

  RoomPlayer selectByUserId(@Param("userId") Long userId);

  RoomPlayer selectByRoomAndUser(@Param("roomId") Long roomId, @Param("userId") Long userId);

  int countByRoomId(@Param("roomId") Long roomId);

  Integer maxSeat(@Param("roomId") Long roomId);

  int insert(RoomPlayer player);

  int deleteByRoomAndUser(@Param("roomId") Long roomId, @Param("userId") Long userId);

  int deleteByRoomId(@Param("roomId") Long roomId);

  int updateReady(@Param("roomId") Long roomId, @Param("userId") Long userId, @Param("ready") Integer ready);

  int setOwner(@Param("roomId") Long roomId, @Param("userId") Long userId, @Param("isOwner") Integer isOwner);

  int resetReady(@Param("roomId") Long roomId);
}
