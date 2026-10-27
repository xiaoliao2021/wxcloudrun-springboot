package com.tencent.wxcloudrun.service;

import com.tencent.wxcloudrun.dto.LoginRequest;
import com.tencent.wxcloudrun.dto.ProfileRequest;
import com.tencent.wxcloudrun.dto.TokenVo;
import com.tencent.wxcloudrun.dto.UserVo;
import com.tencent.wxcloudrun.model.UserSession;

public interface AuthService {

  /**
   * 登录：code 换 openid，建号/查号，签发双 token，并踢掉该用户其它有效会话
   */
  TokenVo login(LoginRequest request, String ip, String clientVersion);

  /**
   * 刷新：轮换 refreshToken
   */
  TokenVo refresh(String refreshToken, String ip);

  /**
   * 登出：只失效当前会话
   */
  void logout(Long userId, String accessToken);

  /**
   * 注销账号：匿名化用户数据并失效全部会话
   */
  void destroy(Long userId);

  /**
   * 上报头像昵称：AES 解密 + 水印校验
   */
  UserVo updateProfile(Long userId, ProfileRequest request);

  /**
   * 取会话（探活用）
   */
  UserSession getSession(String accessToken);

  /**
   * accessToken 剩余有效秒数
   */
  long remainingSeconds(UserSession session);
}
