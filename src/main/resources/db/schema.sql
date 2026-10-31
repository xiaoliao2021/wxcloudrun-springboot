-- =============================================================
--  7鬼523（七鬼五二三）小游戏 数据库结构
--  目标库：springboot_demo   引擎：MySQL 5.7 / CynosDB
--
--  玩法要点（决定表结构的关键规则）：
--   · 一副 54 张牌，2~5 人，各自为战，无盟友
--   · 每人发 5 张，最小牌持有者先出，可出含最小牌的任意牌型
--   · 压牌：同牌型同张数才能压（单张<对子<三条<四条）
--   · 一轮（trick）无人再压时结束，最后出牌者收走本轮分牌
--     分牌：5=5 分，10=10 分，K=10 分（全副共 100 分）
--   · 结束后所有人补牌到 5 张，由上轮赢家先出，直到牌堆摸完
--   · 牌堆发完且有人出完手牌，一局（game）结束，比捡到的分
--
--  牌力顺序（card_rank，由大到小，代码层按 weight 比较）：
--     7 > 大王 > 小王 > 5 > 2 > 3 > A > K > Q > J > 10 > 9 > 8 > 6 > 4
--  花色顺序（card_suit，同点数时比较）：黑桃 > 红桃 > 梅花 > 方块
--
--  牌编码（cards 字段统一用逗号分隔的字符串）：
--     普通牌 = 点数 + 花色首字母，如 7S(黑桃7) 7H(红桃7) 7C(梅花7) 7D(方块7)
--     10 用 T 表示：TS TH TC TD
--     大小王：XR(大王) XB(小王)
--     示例：'7S,5H,TD,XR'
-- =============================================================

SET NAMES utf8mb4;

-- ----------------------------
-- 1. 用户表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `user` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '主键',
  `openid`        VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '微信 openid',
  `unionid`       VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '微信 unionid',
  `session_key`   VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '微信会话密钥，解密敏感数据用',
  `nickname`      VARCHAR(64)  NOT NULL DEFAULT '' COMMENT '昵称（授权后缓存，登录时直接下发）',
  `avatar_url`    VARCHAR(255) NOT NULL DEFAULT '' COMMENT '头像（授权后缓存，登录时直接下发）',
  `profile_authorized` TINYINT NOT NULL DEFAULT 0 COMMENT '是否已完成头像昵称授权 0未授权 1已授权/已跳过',
  `gender`        TINYINT      NOT NULL DEFAULT 0 COMMENT '性别 0未知 1男 2女',
  `coin`          BIGINT       NOT NULL DEFAULT 0 COMMENT '金币/积分',
  `room_card`     INT          NOT NULL DEFAULT 0 COMMENT '房卡数量',
  `total_game`    INT          NOT NULL DEFAULT 0 COMMENT '总局数',
  `win_game`      INT          NOT NULL DEFAULT 0 COMMENT '胜局数',
  `status`        TINYINT      NOT NULL DEFAULT 1 COMMENT '状态 1正常 2封禁',
  `last_login_at` DATETIME     DEFAULT NULL COMMENT '最近登录时间',
  `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_openid` (`openid`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户表';


-- ----------------------------
-- 2. 房间表（好友房 / 快速匹配共用）
-- 注意：updated_at 用 DATETIME(3) 毫秒精度，客户端轮询靠它判断房间是否有变化，
--       秒级精度会让同一秒内的连续变动（连着准备/进出）被误判为无变化
-- ----------------------------
CREATE TABLE IF NOT EXISTS `room` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `room_no`       CHAR(6)     NOT NULL DEFAULT '' COMMENT '房间号，好友房才有',
  `owner_user_id` BIGINT      NOT NULL DEFAULT 0 COMMENT '房主',
  `source`        TINYINT     NOT NULL DEFAULT 1 COMMENT '来源 1好友房 2快速匹配',
  `status`        TINYINT     NOT NULL DEFAULT 0 COMMENT '0等待中 1游戏中 2已结束 3已解散',
  `max_player`    TINYINT     NOT NULL DEFAULT 4 COMMENT '人数上限 2~5',
  `total_round`   INT         NOT NULL DEFAULT 1 COMMENT '约定总局数（房卡模式）',
  `current_round` INT         NOT NULL DEFAULT 0 COMMENT '已进行局数',
  `base_score`    INT         NOT NULL DEFAULT 1 COMMENT '底分（结算倍率）',
  `options`       VARCHAR(512) NOT NULL DEFAULT '' COMMENT '扩展规则 JSON，见下方说明',
  `revision`      BIGINT      NOT NULL DEFAULT 0 COMMENT '房间级版本号，每次状态变化 +1，WS 用它判丢帧/乱序',
  `expire_at`     DATETIME(3) DEFAULT NULL COMMENT '房间过期时间',
  `created_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  `updated_at`    DATETIME(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_no` (`room_no`),
  KEY `idx_status` (`status`),
  KEY `idx_owner` (`owner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='房间表';
-- options 建议字段（JSON 字符串，代码层解析）：
--   rule_type      1=规则一(赢家收分牌) 2=规则二(不收分，按出完顺序排名)
--   allow_straight 是否允许顺子（部分地区规则）
--   allow_bomb     是否允许四条当炸弹
--   max_bet        下注上限


-- ----------------------------
-- 3. 房间玩家（座位）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `room_player` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `room_id`      BIGINT      NOT NULL DEFAULT 0,
  `user_id`      BIGINT      NOT NULL DEFAULT 0,
  `seat`         TINYINT     NOT NULL DEFAULT 0 COMMENT '座位号 1~5',
  `is_owner`     TINYINT     NOT NULL DEFAULT 0 COMMENT '是否房主',
  `is_ready`     TINYINT     NOT NULL DEFAULT 0 COMMENT '是否已准备',
  `is_online`    TINYINT     NOT NULL DEFAULT 1 COMMENT '是否在线',
  `total_score`  INT         NOT NULL DEFAULT 0 COMMENT '本房间累计积分（跨局）',
  `join_at`      DATETIME    NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `leave_at`     DATETIME    DEFAULT NULL COMMENT '离开时间',
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_room_seat` (`room_id`, `seat`),
  UNIQUE KEY `uk_room_user` (`room_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='房间玩家表';


-- ----------------------------
-- 4. 对局表（一整局：发牌到牌堆摸完）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `game` (
  `id`             BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `room_id`        BIGINT   NOT NULL DEFAULT 0,
  `round_no`       INT      NOT NULL DEFAULT 1 COMMENT '房间内第几局',
  `status`         TINYINT  NOT NULL DEFAULT 0 COMMENT '0发牌中 1进行中 2已结算 3异常终止',
  `rule_type`      TINYINT  NOT NULL DEFAULT 1 COMMENT '1规则一(收分牌) 2规则二(不收分)',
  `dealer_user_id` BIGINT   NOT NULL DEFAULT 0 COMMENT '首出者（手牌最小者）',
  `winner_user_id` BIGINT   NOT NULL DEFAULT 0 COMMENT '本局赢家（分最高/先出完）',
  `deck_seed`      VARCHAR(64) NOT NULL DEFAULT '' COMMENT '洗牌种子，用于复盘重放',
  `deck_remain`    VARCHAR(512) NOT NULL DEFAULT '' COMMENT '牌堆剩余牌，断线恢复用',
  `started_at`     DATETIME DEFAULT NULL,
  `ended_at`       DATETIME DEFAULT NULL,
  `created_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`     DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_room` (`room_id`),
  KEY `idx_status` (`status`),
  KEY `idx_winner` (`winner_user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对局表';


-- ----------------------------
-- 5. 对局玩家（本局每人一条）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `game_player` (
  `id`           BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `game_id`      BIGINT   NOT NULL DEFAULT 0,
  `user_id`      BIGINT   NOT NULL DEFAULT 0,
  `seat`         TINYINT  NOT NULL DEFAULT 0,
  `initial_cards` VARCHAR(64) NOT NULL DEFAULT '' COMMENT '初始手牌',
  `picked_score` INT      NOT NULL DEFAULT 0 COMMENT '捡到的分牌分（全桌合计 100）',
  `rank_no`      TINYINT  NOT NULL DEFAULT 0 COMMENT '名次，1 为第一',
  `score_change` INT      NOT NULL DEFAULT 0 COMMENT '本局积分变化（可正可负）',
  `is_win`       TINYINT  NOT NULL DEFAULT 0 COMMENT '是否获胜',
  `created_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`   DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_game_user` (`game_id`, `user_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='对局玩家表';


-- ----------------------------
-- 6. 轮次表（一圈出牌，无人再压即结束）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `game_trick` (
  `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `game_id`         BIGINT  NOT NULL DEFAULT 0,
  `trick_no`        INT     NOT NULL DEFAULT 0 COMMENT '本局第几轮',
  `starter_user_id` BIGINT  NOT NULL DEFAULT 0 COMMENT '先出者',
  `winner_user_id`  BIGINT  NOT NULL DEFAULT 0 COMMENT '本轮最大牌者（收分者）',
  `score_value`     INT     NOT NULL DEFAULT 0 COMMENT '本轮被收走的分牌分',
  `started_at`      DATETIME DEFAULT NULL,
  `ended_at`        DATETIME DEFAULT NULL,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_game_trick` (`game_id`, `trick_no`),
  KEY `idx_game` (`game_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出牌轮次表';


-- ----------------------------
-- 7. 出牌记录表（每一次出牌 / 过牌）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `game_play` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `game_id`       BIGINT  NOT NULL DEFAULT 0,
  `trick_id`      BIGINT  NOT NULL DEFAULT 0,
  `seq_no`        INT     NOT NULL DEFAULT 0 COMMENT '本轮内出牌顺序',
  `user_id`       BIGINT  NOT NULL DEFAULT 0,
  `action`        TINYINT NOT NULL DEFAULT 1 COMMENT '0过牌(pass) 1出牌',
  `card_type`     TINYINT NOT NULL DEFAULT 0 COMMENT '牌型，见下方枚举',
  `main_rank`     VARCHAR(4)  NOT NULL DEFAULT '' COMMENT '主牌点数，比较依据',
  `main_suit`     VARCHAR(4)  NOT NULL DEFAULT '' COMMENT '主牌花色，同点数时比较',
  `cards`         VARCHAR(64) NOT NULL DEFAULT '' COMMENT '实际打出的牌（含带的牌）',
  `cards_count`   TINYINT NOT NULL DEFAULT 0 COMMENT '打出张数',
  `is_bomb`       TINYINT NOT NULL DEFAULT 0 COMMENT '是否炸弹（四条/双王）',
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_trick` (`trick_id`),
  KEY `idx_game` (`game_id`),
  KEY `idx_user` (`user_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='出牌记录表';
-- card_type 枚举：
--   1 单张   2 对子   3 三条   4 三带一对   5 四条   6 四带一   7 顺子(>=3连)


-- ----------------------------
-- 8. 积分 / 房卡流水表
-- ----------------------------
CREATE TABLE IF NOT EXISTS `user_coin_log` (
  `id`            BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`       BIGINT  NOT NULL DEFAULT 0,
  `coin_type`     TINYINT NOT NULL DEFAULT 1 COMMENT '1金币 2房卡',
  `biz_type`      TINYINT NOT NULL DEFAULT 0 COMMENT '1开房消耗 2对局结算 3充值 4系统赠送 5退款',
  `change_amount` INT     NOT NULL DEFAULT 0 COMMENT '变动值，正为增加',
  `balance_after` BIGINT  NOT NULL DEFAULT 0 COMMENT '变动后余额',
  `biz_id`        VARCHAR(64) NOT NULL DEFAULT '' COMMENT '关联业务 id（room_id / game_id）',
  `remark`        VARCHAR(128) NOT NULL DEFAULT '',
  `created_at`    DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_biz` (`biz_type`, `biz_id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='积分房卡流水表';


-- ----------------------------
-- 9. 服务端会话表（登录态）
--    登录态放服务端而非纯 JWT，才能支持互踢、登出、封禁
--    token 只存 sha256，不存明文
-- ----------------------------
CREATE TABLE IF NOT EXISTS `user_session` (
  `id`                 BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`            BIGINT   NOT NULL DEFAULT 0,
  `access_token_hash`  CHAR(64) NOT NULL DEFAULT '' COMMENT 'accessToken 的 sha256',
  `refresh_token_hash` CHAR(64) NOT NULL DEFAULT '' COMMENT 'refreshToken 的 sha256',
  `access_expire_at`   DATETIME DEFAULT NULL COMMENT 'accessToken 过期时间',
  `refresh_expire_at`  DATETIME DEFAULT NULL COMMENT 'refreshToken 过期时间',
  `status`             TINYINT  NOT NULL DEFAULT 1 COMMENT '1有效 0已登出 2被踢下线 3已封禁',
  `ip`                 VARCHAR(64)  NOT NULL DEFAULT '',
  `device`             VARCHAR(255) NOT NULL DEFAULT '' COMMENT '设备信息 JSON',
  `client_version`     VARCHAR(32)  NOT NULL DEFAULT '',
  `last_access_at`     DATETIME DEFAULT NULL,
  `created_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `updated_at`         DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_access` (`access_token_hash`),
  UNIQUE KEY `uk_refresh` (`refresh_token_hash`),
  KEY `idx_user` (`user_id`),
  KEY `idx_status` (`status`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='服务端会话表';


-- ----------------------------
-- 10. 登录日志表（风控排查用）
-- ----------------------------
CREATE TABLE IF NOT EXISTS `user_login_log` (
  `id`         BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
  `user_id`    BIGINT   NOT NULL DEFAULT 0,
  `action`     VARCHAR(16) NOT NULL DEFAULT '' COMMENT 'login/refresh/logout/destroy',
  `result`     TINYINT  NOT NULL DEFAULT 1 COMMENT '1成功 0失败',
  `fail_code`  INT      NOT NULL DEFAULT 0 COMMENT '失败时的业务错误码',
  `ip`         VARCHAR(64)  NOT NULL DEFAULT '',
  `device`     VARCHAR(255) NOT NULL DEFAULT '',
  `channel`    VARCHAR(32)  NOT NULL DEFAULT '',
  `scene`      INT      NOT NULL DEFAULT 0,
  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_user` (`user_id`),
  KEY `idx_created` (`created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='登录日志表';
