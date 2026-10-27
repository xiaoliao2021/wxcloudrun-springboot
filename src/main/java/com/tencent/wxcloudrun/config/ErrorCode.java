package com.tencent.wxcloudrun.config;

/**
 * 业务错误码，与前端 api-login.md §7 一致
 */
public enum ErrorCode {

  SUCCESS(0, "ok"),

  PARAM_ERROR(1001, "参数缺失或格式错误"),
  FRAME_UNKNOWN_TYPE(1002, "未知的消息类型"),

  CODE_INVALID(2001, "code 无效或已使用"),
  CODE2SESSION_FAIL(2002, "code2Session 调用失败"),
  APPID_MISMATCH(2003, "appid 不匹配"),

  ACCESS_TOKEN_INVALID(3001, "accessToken 无效或已过期"),
  REFRESH_TOKEN_INVALID(3002, "refreshToken 失效"),
  KICKED_OFFLINE(3003, "账号已在其他设备登录或被封禁"),

  SESSION_KEY_EXPIRED(4001, "session_key 失效，请重新登录"),
  WATERMARK_MISMATCH(4002, "水印 appid 不匹配"),

  RATE_LIMITED(5001, "操作过于频繁，请稍后再试"),

  ROOM_NOT_FOUND(4101, "房间不存在"),
  ROOM_FULL(4102, "房间已满"),
  ROOM_PLAYING(4103, "房间已经开始"),
  NOT_IN_ROOM(4104, "你不在该房间或房间已解散"),
  NOT_ROOM_OWNER(4105, "只有房主可以开始"),
  ALREADY_IN_ROOM(4106, "你已在其它房间里"),
  ROOM_NEED_MORE_PLAYER(4107, "人数不足，至少需要 2 人"),
  ROOM_IDLE_TIMEOUT(4108, "房间闲置超时已回收"),
  ROOM_NOT_ALL_READY(4109, "有玩家还没准备"),

  GAME_NOT_FOUND(5101, "不在对局中或对局已结束"),
  GAME_PATTERN_INVALID(5102, "牌型不合法"),
  GAME_NOT_YOUR_TURN(5103, "还没轮到你"),
  GAME_CARDS_NOT_IN_HAND(5104, "你没有这些牌"),
  GAME_CANNOT_BEAT(5105, "压不过上家"),
  GAME_CANNOT_PASS(5106, "首出必须出牌，不能要不起"),
  GAME_ALREADY_PASSED(5107, "本轮已过牌，不能再出"),

  SERVER_ERROR(9000, "服务内部错误");

  private final int code;
  private final String message;

  ErrorCode(int code, String message) {
    this.code = code;
    this.message = message;
  }

  public int getCode() {
    return code;
  }

  public String getMessage() {
    return message;
  }

  /**
   * 微信 errcode 映射为本系统错误码
   */
  public static ErrorCode fromWxErrcode(int errcode) {
    switch (errcode) {
      case 40029:
      case 40163:
        return CODE_INVALID;
      case 40226:
      case 45011:
        return RATE_LIMITED;
      case 40013:
        return APPID_MISMATCH;
      default:
        return CODE2SESSION_FAIL;
    }
  }
}
