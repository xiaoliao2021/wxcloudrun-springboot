package com.tencent.wxcloudrun.dto;

import lombok.Data;

/**
 * 上报头像昵称：POST /v1/auth/profile
 */
@Data
public class ProfileRequest {

  /** wx.createUserInfoButton 回调里的密文 */
  private String encryptedData;

  /** 初始向量 */
  private String iv;

  /** 未加密的用户信息 JSON，仅展示用，不作为信任依据 */
  private String rawData;

  /** rawData 的签名 */
  private String signature;
}
