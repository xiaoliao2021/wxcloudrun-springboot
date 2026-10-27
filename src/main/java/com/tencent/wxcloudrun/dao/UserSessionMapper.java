package com.tencent.wxcloudrun.dao;

import com.tencent.wxcloudrun.model.UserSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserSessionMapper {

  UserSession selectByAccessTokenHash(@Param("hash") String hash);

  UserSession selectByRefreshTokenHash(@Param("hash") String hash);

  int insert(UserSession session);

  int updateTokens(UserSession session);

  int updateStatusByUserId(@Param("userId") Long userId, @Param("status") Integer status);

  int updateStatusById(@Param("id") Long id, @Param("status") Integer status);

  int touch(@Param("id") Long id);
}
