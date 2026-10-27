package com.tencent.wxcloudrun.dao;

import com.tencent.wxcloudrun.model.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

@Mapper
public interface UserMapper {

  User selectByOpenid(@Param("openid") String openid);

  User selectById(@Param("id") Long id);

  int insert(User user);

  int updateLoginInfo(User user);

  int updateProfile(User user);

  int updateProfileById(User user);

  int clearAccount(@Param("id") Long id);
}
