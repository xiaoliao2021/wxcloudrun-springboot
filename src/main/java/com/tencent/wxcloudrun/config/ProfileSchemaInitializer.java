package com.tencent.wxcloudrun.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * 启动时幂等补齐 user.profile_authorized 列。
 *
 * 该列用于标记「头像昵称授权」是否已经完成：
 *   · 0 未授权 —— 首次登录，客户端需要拉一次授权
 *   · 1 已授权（或用户明确跳过）—— 之后登录直接读库里的昵称头像，不再弹授权
 *
 * 老库没有这一列时 UserMapper 的 SELECT 会直接报错、整个登录不可用，
 * 所以这里用 information_schema 判断后 ALTER；失败只告警，不阻塞应用启动。
 */
@Component
public class ProfileSchemaInitializer implements ApplicationRunner {

  private static final String COLUMN = "profile_authorized";
  private static final String ADD_COLUMN_SQL = "ALTER TABLE `user` ADD COLUMN `profile_authorized` "
      + "TINYINT NOT NULL DEFAULT 0 COMMENT '是否已完成头像昵称授权 0未授权 1已授权/已跳过' AFTER `avatar_url`";

  private final JdbcTemplate jdbcTemplate;
  private final Logger logger = LoggerFactory.getLogger(ProfileSchemaInitializer.class);

  public ProfileSchemaInitializer(@Autowired JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  @Override
  public void run(ApplicationArguments args) {
    try {
      Integer count = jdbcTemplate.queryForObject(
          "SELECT COUNT(*) FROM information_schema.columns WHERE table_schema = DATABASE() "
              + "AND table_name = 'user' AND column_name = ?",
          Integer.class, COLUMN);
      if (count != null && count > 0) {
        return;
      }
      jdbcTemplate.execute(ADD_COLUMN_SQL);
      logger.info("added column user.{}", COLUMN);
    } catch (Exception e) {
      logger.warn("ensure column user.{} failed, profile cache may not work", COLUMN, e);
    }
  }
}