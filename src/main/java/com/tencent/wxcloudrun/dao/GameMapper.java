package com.tencent.wxcloudrun.dao;

import com.tencent.wxcloudrun.model.Game;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface GameMapper {

  int insert(Game game);

  Game selectById(@Param("id") Long id);

  /** 房间当前进行中的那一局 */
  Game selectPlayingByRoom(@Param("roomId") Long roomId);

  int updateDeckRemain(@Param("id") Long id, @Param("deckRemain") String deckRemain);

  int updateStatus(@Param("id") Long id, @Param("status") Integer status);

  int settle(Game game);

  /** 服务重启后把还在进行中的对局标记为异常终止 */
  int abortPlaying();
}
