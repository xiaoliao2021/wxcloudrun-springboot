#!/usr/bin/env bash
# 本地联调：绕过微信 code2Session，直接造一个可用的 accessToken
#
# 用途：后端接口自测（curl / 脚本）时不必每次去开发者工具里捞一次性的 wx.login code。
#      正式登录流程仍然走 /v1/auth/login，这里只是在库里写一条 session 记录。
#
# 用法：
#   ./dev-mint-token.sh              # 用默认测试账号 devtest_1
#   ./dev-mint-token.sh devtest_2    # 指定 openid 后缀，方便模拟多个玩家
#   ./dev-mint-token.sh devtest_1 24 # 有效期 24 小时
#
# 输出：只有 token 一行，方便 TOKEN=$(./dev-mint-token.sh) 这样取
#
# 清理测试数据：
#   DELETE FROM user_session WHERE user_id IN (SELECT id FROM user WHERE openid LIKE 'devtest_%');
#   DELETE FROM room_player WHERE user_id IN (SELECT id FROM user WHERE openid LIKE 'devtest_%');
#   DELETE FROM user WHERE openid LIKE 'devtest_%';

set -e

cd "$(dirname "$0")"

# 数据库连接统一从 dev-env.sh 来，不在这里再存一份密码
if [ -z "${MYSQL_ADDRESS:-}" ]; then
  # shellcheck source=dev-env.sh
  source ./dev-env.sh
fi

if [ -z "${MYSQL_ADDRESS:-}" ]; then
  echo "缺少 MYSQL_ADDRESS，检查 dev-env.sh" >&2
  exit 1
fi

SUFFIX="${1:-1}"
HOURS="${2:-12}"
OPENID="devtest_${SUFFIX}"

DB_HOST="${MYSQL_ADDRESS%%:*}"
DB_PORT="${MYSQL_ADDRESS##*:}"
[ "$DB_PORT" = "$DB_HOST" ] && DB_PORT=3306

MYSQL_BIN="${MYSQL_BIN:-mysql}"
if ! command -v "$MYSQL_BIN" >/dev/null 2>&1; then
  MYSQL_BIN="/c/Program Files/MySQL/MySQL Server 8.0/bin/mysql"
fi

sql() {
  "$MYSQL_BIN" --protocol=TCP -h "$DB_HOST" -P "$DB_PORT" \
    -u "$MYSQL_USERNAME" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE" -N -B -e "$1" 2>/dev/null
}

TOKEN="devtok_$(date +%s)_${RANDOM}${RANDOM}"
HASH=$(printf '%s' "$TOKEN" | sha256sum | awk '{print $1}')
REFRESH="devref_$(date +%s)_${RANDOM}${RANDOM}"
REFRESH_HASH=$(printf '%s' "$REFRESH" | sha256sum | awk '{print $1}')

# 1. 测试用户（存在就复用）
USER_ID=$(sql "SELECT id FROM \`user\` WHERE openid='${OPENID}' LIMIT 1")
if [ -z "$USER_ID" ]; then
  sql "INSERT INTO \`user\`(openid, nickname, avatar_url, gender, status, last_login_at)
       VALUES('${OPENID}', '测试${SUFFIX}', '', 0, 1, NOW())"
  USER_ID=$(sql "SELECT id FROM \`user\` WHERE openid='${OPENID}' LIMIT 1")
fi

if [ -z "$USER_ID" ]; then
  echo "建测试用户失败，检查数据库连接和权限" >&2
  exit 1
fi

# 2. 清掉这个用户之前的 dev 会话，模拟「只保留最新会话」
sql "UPDATE user_session SET status=0 WHERE user_id=${USER_ID} AND status=1"

# 3. 写一条有效会话
sql "INSERT INTO user_session(user_id, access_token_hash, refresh_token_hash,
                              access_expire_at, refresh_expire_at, status, ip, device, client_version, last_access_at)
     VALUES(${USER_ID}, '${HASH}', '${REFRESH_HASH}',
            DATE_ADD(NOW(), INTERVAL ${HOURS} HOUR), DATE_ADD(NOW(), INTERVAL 30 DAY),
            1, '127.0.0.1', 'dev-mint-token', '1.0.0', NOW())"

echo "$TOKEN"
