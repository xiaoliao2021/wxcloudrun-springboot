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
  /** true 表示还需要客户端拉一次微信授权（首次登录） */
  private Boolean needProfile;
  /** 是否已完成头像昵称授权（含用户明确跳过），之后登录直接读缓存 */
  private Boolean profileAuthorized;
  /** inviteCode 有效时返回，暂为 null */
  private String roomId;

  public static UserVo from(User user, boolean isNewUser) {
    UserVo vo = new UserVo();
    vo.setUserId("u_" + user.getId());

    String nickname = user.getNickname() == null ? "" : user.getNickname();
    String avatarUrl = user.getAvatarUrl() == null ? "" : user.getAvatarUrl();
    vo.setNickname(nickname);
    vo.setAvatarUrl(avatarUrl);
    vo.setIsNewUser(isNewUser);

    // 授权信息必须齐全：昵称或头像缺一个，客户端就要再拉一次授权。
    // 不支持「跳过授权」，历史跳过过的账号同样要求补全。
    boolean complete = !nickname.isEmpty() && !avatarUrl.isEmpty();
    vo.setProfileAuthorized(complete);
    vo.setNeedProfile(!complete);
    vo.setRoomId(null);
    return vo;
  }
}
