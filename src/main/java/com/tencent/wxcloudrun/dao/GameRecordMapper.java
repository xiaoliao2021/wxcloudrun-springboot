package com.tencent.wxcloudrun.dao;

import com.tencent.wxcloudrun.model.GamePlay;
import com.tencent.wxcloudrun.model.GamePlayer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * 对局明细：玩家与出牌记录。
 * 两张表放一个接口里，语句不多，没必要拆成两个文件。
 */
@Mapper
public interface GameRecordMapper {

  int insertPlayer(GamePlayer player);

  List<GamePlayer> selectPlayers(@Param("gameId") Long gameId);

  /** 结算时回写捡分、名次、积分变化 */
  int updatePlayerResult(GamePlayer player);

  int insertPlay(GamePlay play);

  List<GamePlay> selectPlays(@Param("gameId") Long gameId);
}
