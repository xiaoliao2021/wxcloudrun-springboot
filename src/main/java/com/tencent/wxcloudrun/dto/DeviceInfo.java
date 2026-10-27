package com.tencent.wxcloudrun.dto;

import lombok.Data;

/**
 * 设备信息，来自 wx.getSystemInfoSync()
 */
@Data
public class DeviceInfo {

  private String brand;
  private String model;
  private String system;
  private String sdkVersion;
}
