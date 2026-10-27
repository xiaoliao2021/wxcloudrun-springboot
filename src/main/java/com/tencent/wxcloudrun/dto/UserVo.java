package com.tencent.wxcloudrun.dto;

import com.tencent.wxcloudrun.model.User;
import lombok.Data;

/**
 * 下发给客户端的用户信息（不含 openid / unionid / session_key）
 */
@Data
public class UserVo {

  /** 服务端内部用户 ID */
  private String userId;
  private String nickname;
  private String avatarUrl;
  private Boolean isNewUser;
  private Boolean needProfile;
  /** inviteCode 有效时返回，暂为 null */
  private String roomId;

  public static UserVo from(User user, boolean isNewUser) {
    UserVo vo = new UserVo();
    vo.setUserId("u_" + user.getId());
    vo.setNickname(user.getNickname() == null ? "" : user.getNickname());
    vo.setAvatarUrl(user.getAvatarUrl() == null ? "" : user.getAvatarUrl());
    vo.setIsNewUser(isNewUser);
    vo.setNeedProfile(vo.getNickname().isEmpty() || vo.getAvatarUrl().isEmpty());
    vo.setRoomId(null);
    return vo;
  }
}
