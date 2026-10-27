package com.tencent.wxcloudrun.dto;

import lombok.Data;

@Data
public class RoomPlayerVo {

  private String userId;
  private String nickname;
  private String avatarUrl;
  private Boolean isOwner;
  private Boolean ready;
}
