package com.tencent.wxcloudrun.dao;

import com.tencent.wxcloudrun.model.UserLoginLog;
import org.apache.ibatis.annotations.Mapper;

@Mapper
public interface UserLoginLogMapper {

  int insert(UserLoginLog log);
}
