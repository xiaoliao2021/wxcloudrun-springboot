package com.tencent.wxcloudrun.dto;

import lombok.Data;

/**
 * 登录请求：POST /v1/auth/login
 */
@Data
public class LoginRequest {

  /** wx.login() 得到的临时凭证 */
  private String code;

  /** 固定 minigame */
  private String channel;

  /** 启动场景值 */
  private Integer scene;

  /** 房间邀请码 */
  private String inviteCode;

  /** 设备信息 */
  private DeviceInfo device;
}
