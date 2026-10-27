package com.tencent.wxcloudrun.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.tencent.wxcloudrun.config.ErrorCode;
import com.tencent.wxcloudrun.config.RateLimiter;
import com.tencent.wxcloudrun.config.WxProperties;
import com.tencent.wxcloudrun.dao.UserLoginLogMapper;
import com.tencent.wxcloudrun.dao.UserMapper;
import com.tencent.wxcloudrun.dao.UserSessionMapper;
import com.tencent.wxcloudrun.dto.LoginRequest;
import com.tencent.wxcloudrun.dto.ProfileRequest;
import com.tencent.wxcloudrun.dto.TokenVo;
import com.tencent.wxcloudrun.dto.UserVo;
import com.tencent.wxcloudrun.exception.BizException;
import com.tencent.wxcloudrun.model.User;
import com.tencent.wxcloudrun.model.UserLoginLog;
import com.tencent.wxcloudrun.model.UserSession;
import com.tencent.wxcloudrun.service.AuthService;
import com.tencent.wxcloudrun.util.WxAesUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.io.UnsupportedEncodingException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class AuthServiceImpl implements AuthService {

  private static final String CODE2SESSION_URL =
      "https://api.weixin.qq.com/sns/jscode2session?appid=%s&secret=%s&js_code=%s&grant_type=authorization_code";

  /** 状态码：1 有效 / 0 已登出 / 2 被踢下线 / 3 已封禁，用户侧 2 表示封禁 */
  private static final int SESSION_VALID = 1;
  private static final int SESSION_LOGOUT = 0;
  private static final int SESSION_KICKED = 2;
  private static final int USER_STATUS_BANNED = 2;
  private static final int USER_STATUS_DESTROYED = 3;

  private final Logger logger = LoggerFactory.getLogger(AuthServiceImpl.class);

  final UserMapper userMapper;
  final UserSessionMapper userSessionMapper;
  final UserLoginLogMapper userLoginLogMapper;
  final WxProperties wxProperties;
  final RestTemplate restTemplate;
  final RateLimiter rateLimiter;
  final ObjectMapper objectMapper;

  /** 已使用过的 code，防止并发/重放（微信对同一 code 只认一次） */
  private final ConcurrentHashMap<String, Long> usedCodes = new ConcurrentHashMap<>();

  public AuthServiceImpl(@Autowired UserMapper userMapper,
                         @Autowired UserSessionMapper userSessionMapper,
                         @Autowired UserLoginLogMapper userLoginLogMapper,
                         @Autowired WxProperties wxProperties,
                         @Autowired RestTemplate restTemplate,
                         @Autowired RateLimiter rateLimiter,
                         @Autowired ObjectMapper objectMapper) {
    this.userMapper = userMapper;
    this.userSessionMapper = userSessionMapper;
    this.userLoginLogMapper = userLoginLogMapper;
    this.wxProperties = wxProperties;
    this.restTemplate = restTemplate;
    this.rateLimiter = rateLimiter;
    this.objectMapper = objectMapper;
  }

  @Override
  public TokenVo login(LoginRequest request, String ip, String clientVersion) {
    if (request == null || isBlank(request.getCode())) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "缺少 code");
    }
    if (!rateLimiter.tryAcquire("login:ip:" + ip, 60, 60_000L)) {
      throw new BizException(ErrorCode.RATE_LIMITED.getCode(), ErrorCode.RATE_LIMITED.getMessage());
    }
    String device = request.getDevice() == null ? "" : safeJson(request.getDevice());
    String channel = request.getChannel() == null ? "" : request.getChannel();

    Map<String, Object> wxSession = code2Session(request.getCode());
    // 换到 openid 之后再标记已用：网络抖动时不会白白烧掉 code，客户端拿同一个 code 重试还能成功
    if (!markCodeUsed(request.getCode())) {
      throw new BizException(ErrorCode.CODE_INVALID.getCode(), ErrorCode.CODE_INVALID.getMessage());
    }
    String openid = str(wxSession.get("openid"));
    String sessionKey = str(wxSession.get("session_key"));
    String unionid = str(wxSession.get("unionid"));

    if (!rateLimiter.tryAcquire("login:openid:" + openid, 10, 60_000L)) {
      throw new BizException(ErrorCode.RATE_LIMITED.getCode(), ErrorCode.RATE_LIMITED.getMessage());
    }

    User user = userMapper.selectByOpenid(openid);
    boolean isNewUser = user == null;
    if (isNewUser) {
      User created = new User();
      created.setOpenid(openid);
      created.setUnionid(unionid);
      created.setSessionKey(sessionKey);
      created.setNickname("");
      created.setAvatarUrl("");
      created.setGender(0);
      userMapper.insert(created);
      user = userMapper.selectByOpenid(openid);
      logger.info("register new user, userId={}", user.getId());
    } else {
      if (user.getStatus() != null && (user.getStatus() == USER_STATUS_BANNED || user.getStatus() == USER_STATUS_DESTROYED)) {
        throw new BizException(ErrorCode.KICKED_OFFLINE.getCode(), ErrorCode.KICKED_OFFLINE.getMessage());
      }
      User update = new User();
      update.setOpenid(openid);
      update.setSessionKey(sessionKey);
      update.setUnionid(unionid);
      userMapper.updateLoginInfo(update);
      user = userMapper.selectByOpenid(openid);
    }

    // 互踢：同一用户只保留最新会话
    userSessionMapper.updateStatusByUserId(user.getId(), SESSION_KICKED);

    TokenVo tokenVo = issueTokens(user.getId(), ip, device, clientVersion);
    tokenVo.setUser(UserVo.from(user, isNewUser));

    writeLog(user.getId(), "login", true, 0, ip, device, channel,
        request.getScene() == null ? 0 : request.getScene());
    return tokenVo;
  }

  @Override
  public TokenVo refresh(String refreshToken, String ip) {
    if (isBlank(refreshToken)) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "缺少 refreshToken");
    }
    String refreshHash = sha256(refreshToken);
    if (!rateLimiter.tryAcquire("refresh:" + refreshHash, 30, 3_600_000L)) {
      throw new BizException(ErrorCode.RATE_LIMITED.getCode(), ErrorCode.RATE_LIMITED.getMessage());
    }
    UserSession session = userSessionMapper.selectByRefreshTokenHash(refreshHash);
    if (session == null || session.getStatus() == null || session.getStatus() != SESSION_VALID
        || session.getRefreshExpireAt() == null || session.getRefreshExpireAt().isBefore(LocalDateTime.now())) {
      throw new BizException(ErrorCode.REFRESH_TOKEN_INVALID.getCode(), ErrorCode.REFRESH_TOKEN_INVALID.getMessage());
    }
    User user = userMapper.selectById(session.getUserId());
    if (user == null || (user.getStatus() != null && user.getStatus() != 1)) {
      throw new BizException(ErrorCode.KICKED_OFFLINE.getCode(), ErrorCode.KICKED_OFFLINE.getMessage());
    }

    // 轮换 refreshToken，旧的立即失效
    String newAccess = "at_" + randomId();
    String newRefresh = "rt_" + randomId();
    session.setAccessTokenHash(sha256(newAccess));
    session.setRefreshTokenHash(sha256(newRefresh));
    session.setAccessExpireAt(LocalDateTime.now().plusSeconds(wxProperties.getAccessExpireSeconds()));
    session.setRefreshExpireAt(LocalDateTime.now().plusSeconds(wxProperties.getRefreshExpireSeconds()));
    userSessionMapper.updateTokens(session);

    TokenVo vo = new TokenVo();
    vo.setAccessToken(newAccess);
    vo.setRefreshToken(newRefresh);
    vo.setExpiresIn(wxProperties.getAccessExpireSeconds());
    vo.setRefreshExpiresIn(wxProperties.getRefreshExpireSeconds());
    vo.setServerTime(System.currentTimeMillis());
    writeLog(session.getUserId(), "refresh", true, 0, ip, session.getDevice(), "", 0);
    return vo;
  }

  @Override
  public void logout(Long userId, String accessToken) {
    UserSession session = userSessionMapper.selectByAccessTokenHash(sha256(accessToken));
    if (session != null && session.getUserId().equals(userId)) {
      userSessionMapper.updateStatusById(session.getId(), SESSION_LOGOUT);
    }
    writeLog(userId, "logout", true, 0, "", "", "", 0);
  }

  @Override
  public void destroy(Long userId) {
    userMapper.clearAccount(userId);
    userSessionMapper.updateStatusByUserId(userId, SESSION_LOGOUT);
    writeLog(userId, "destroy", true, 0, "", "", "", 0);
  }

  @Override
  public UserVo updateProfile(Long userId, ProfileRequest request) {
    if (request == null || isBlank(request.getEncryptedData()) || isBlank(request.getIv())) {
      throw new BizException(ErrorCode.PARAM_ERROR.getCode(), "缺少 encryptedData 或 iv");
    }
    User user = userMapper.selectById(userId);
    if (user == null || isBlank(user.getSessionKey())) {
      throw new BizException(ErrorCode.SESSION_KEY_EXPIRED.getCode(), ErrorCode.SESSION_KEY_EXPIRED.getMessage());
    }

    String plain;
    try {
      plain = WxAesUtil.decrypt(user.getSessionKey(), request.getEncryptedData(), request.getIv());
    } catch (Exception e) {
      logger.warn("decrypt user info failed, userId={}", userId);
      throw new BizException(ErrorCode.SESSION_KEY_EXPIRED.getCode(), ErrorCode.SESSION_KEY_EXPIRED.getMessage());
    }

    JsonNode json;
    try {
      json = objectMapper.readTree(plain);
    } catch (Exception e) {
      throw new BizException(ErrorCode.SESSION_KEY_EXPIRED.getCode(), ErrorCode.SESSION_KEY_EXPIRED.getMessage());
    }

    String watermarkAppid = json.path("watermark").path("appid").asText("");
    if (!wxProperties.getAppid().equals(watermarkAppid)) {
      logger.warn("watermark appid mismatch, expect={}, actual={}", wxProperties.getAppid(), watermarkAppid);
      throw new BizException(ErrorCode.WATERMARK_MISMATCH.getCode(), ErrorCode.WATERMARK_MISMATCH.getMessage());
    }

    User update = new User();
    update.setId(userId);
    update.setNickname(json.path("nickName").asText(""));
    update.setAvatarUrl(json.path("avatarUrl").asText(""));
    update.setGender(json.path("gender").asInt(0));
    update.setUnionid(json.path("unionId").asText(""));
    userMapper.updateProfileById(update);

    return UserVo.from(userMapper.selectById(userId), false);
  }

  @Override
  public UserSession getSession(String accessToken) {
    if (isBlank(accessToken)) {
      return null;
    }
    UserSession session = userSessionMapper.selectByAccessTokenHash(sha256(accessToken));
    if (session == null || session.getStatus() == null || session.getStatus() != SESSION_VALID
        || session.getAccessExpireAt() == null || session.getAccessExpireAt().isBefore(LocalDateTime.now())) {
      return null;
    }
    return session;
  }

  @Override
  public long remainingSeconds(UserSession session) {
    if (session == null || session.getAccessExpireAt() == null) {
      return 0;
    }
    long seconds = java.time.Duration.between(LocalDateTime.now(), session.getAccessExpireAt()).getSeconds();
    return Math.max(seconds, 0);
  }

  private TokenVo issueTokens(Long userId, String ip, String device, String clientVersion) {
    String access = "at_" + randomId();
    String refresh = "rt_" + randomId();
    UserSession session = new UserSession();
    session.setUserId(userId);
    session.setAccessTokenHash(sha256(access));
    session.setRefreshTokenHash(sha256(refresh));
    session.setAccessExpireAt(LocalDateTime.now().plusSeconds(wxProperties.getAccessExpireSeconds()));
    session.setRefreshExpireAt(LocalDateTime.now().plusSeconds(wxProperties.getRefreshExpireSeconds()));
    session.setStatus(SESSION_VALID);
    session.setIp(ip == null ? "" : ip);
    session.setDevice(device == null ? "" : device);
    session.setClientVersion(clientVersion == null ? "" : clientVersion);
    userSessionMapper.insert(session);

    TokenVo vo = new TokenVo();
    vo.setAccessToken(access);
    vo.setRefreshToken(refresh);
    vo.setExpiresIn(wxProperties.getAccessExpireSeconds());
    vo.setRefreshExpiresIn(wxProperties.getRefreshExpireSeconds());
    vo.setServerTime(System.currentTimeMillis());
    return vo;
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> code2Session(String code) {
    String url;
    try {
      url = String.format(CODE2SESSION_URL,
          wxProperties.getAppid(),
          wxProperties.getSecret(),
          URLEncoder.encode(code, StandardCharsets.UTF_8.name()));
    } catch (UnsupportedEncodingException e) {
      throw new BizException(ErrorCode.SERVER_ERROR.getCode(), ErrorCode.SERVER_ERROR.getMessage());
    }
    // 微信这个接口成功失败都返回 HTTP 200，且 Content-Type 是 text/plain（不是 application/json）。
    // 直接 getForObject(url, Map.class) 会抛 UnknownContentTypeException，
    // 导致真实的 errcode（40029/40013 等）被吞掉、统一变成 2002，所以先取 String 再自己解析。
    String body;
    try {
      body = restTemplate.getForObject(url, String.class);
    } catch (Exception e) {
      logger.error("code2Session request failed", e);
      throw new BizException(ErrorCode.CODE2SESSION_FAIL.getCode(), ErrorCode.CODE2SESSION_FAIL.getMessage());
    }
    if (body == null || body.trim().isEmpty()) {
      logger.error("code2Session empty response");
      throw new BizException(ErrorCode.CODE2SESSION_FAIL.getCode(), ErrorCode.CODE2SESSION_FAIL.getMessage());
    }
    Map<String, Object> resp;
    try {
      @SuppressWarnings("unchecked")
      Map<String, Object> parsed = objectMapper.readValue(body, Map.class);
      resp = parsed;
    } catch (Exception e) {
      logger.error("code2Session response is not json, body={}", body);
      throw new BizException(ErrorCode.CODE2SESSION_FAIL.getCode(), ErrorCode.CODE2SESSION_FAIL.getMessage());
    }
    if (resp == null) {
      throw new BizException(ErrorCode.CODE2SESSION_FAIL.getCode(), ErrorCode.CODE2SESSION_FAIL.getMessage());
    }
    Object errcode = resp.get("errcode");
    if (errcode != null && !"0".equals(String.valueOf(errcode))) {
      int codeInt;
      try {
        codeInt = Integer.parseInt(String.valueOf(errcode));
      } catch (NumberFormatException e) {
        codeInt = -1;
      }
      logger.warn("code2session error, errcode={}, errmsg={}", errcode, resp.get("errmsg"));
      ErrorCode mapped = ErrorCode.fromWxErrcode(codeInt);
      throw new BizException(mapped.getCode(), mapped.getMessage());
    }
    return resp;
  }

  private boolean markCodeUsed(String code) {
    cleanupUsedCodes();
    return usedCodes.putIfAbsent(code, System.currentTimeMillis()) == null;
  }

  private void cleanupUsedCodes() {
    if (usedCodes.size() < 1000) {
      return;
    }
    long expireBefore = System.currentTimeMillis() - 5 * 60_000L;
    usedCodes.entrySet().removeIf(entry -> entry.getValue() < expireBefore);
  }

  private void writeLog(Long userId, String action, boolean success, int failCode,
                        String ip, String device, String channel, int scene) {
    try {
      UserLoginLog log = new UserLoginLog();
      log.setUserId(userId);
      log.setAction(action);
      log.setResult(success ? 1 : 0);
      log.setFailCode(failCode);
      log.setIp(ip);
      log.setDevice(device == null ? "" : device);
      log.setChannel(channel);
      log.setScene(scene);
      userLoginLogMapper.insert(log);
    } catch (Exception e) {
      logger.warn("write login log failed", e);
    }
  }

  private String safeJson(Object obj) {
    try {
      return objectMapper.writeValueAsString(obj);
    } catch (Exception e) {
      return "";
    }
  }

  private static String randomId() {
    return UUID.randomUUID().toString().replace("-", "");
  }

  private static String sha256(String text) {
    try {
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      byte[] bytes = digest.digest(text.getBytes(StandardCharsets.UTF_8));
      StringBuilder sb = new StringBuilder();
      for (byte b : bytes) {
        sb.append(String.format("%02x", b));
      }
      return sb.toString();
    } catch (Exception e) {
      throw new IllegalStateException("sha256 不可用", e);
    }
  }

  private static boolean isBlank(String s) {
    return s == null || s.trim().isEmpty();
  }

  private static String str(Object o) {
    return o == null ? "" : String.valueOf(o);
  }
}
