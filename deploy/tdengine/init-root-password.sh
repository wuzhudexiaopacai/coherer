#!/bin/sh
# TDengine 首次启动时把 root 口令从默认值改为环境变量中的强口令。
# 可重复执行：已改过的实例直接判定为完成。
set -eu

HOST="${TD_HOST:-tdengine}"
PORT="${TD_PORT:-6041}"
TARGET_PASSWORD="${COHERER_TDENGINE_PASSWORD:?COHERER_TDENGINE_PASSWORD is required}"
URL="http://${HOST}:${PORT}/rest/sql"

wait_ready() {
  i=0
  while [ "$i" -lt 60 ]; do
    if curl -s -o /dev/null -m 2 "${URL}" -d "show databases" || true; then
      # 任何 HTTP 响应（含 401）都说明 adapter 已就绪
      code=$(curl -s -o /dev/null -w "%{http_code}" -m 2 "${URL}" -d "show databases" || true)
      [ "$code" != "000" ] && return 0
    fi
    i=$((i + 1))
    sleep 2
  done
  echo "TDengine adapter 未就绪: ${URL}" >&2
  return 1
}

is_ok() {
  body=$(curl -s -m 5 -u "root:$1" -X POST "${URL}" -d "select server_version()" || true)
  echo "$body" | grep -q '"code":0'
}

wait_ready

if is_ok "${TARGET_PASSWORD}"; then
  echo "TDengine root 口令已是目标值，跳过初始化"
  exit 0
fi

if is_ok "taosdata"; then
  body=$(curl -s -m 5 -u "root:taosdata" -X POST "${URL}" \
    -d "alter user root pass '${TARGET_PASSWORD}'" || true)
  echo "$body" | grep -q '"code":0' || { echo "修改 root 口令失败: $body" >&2; exit 1; }
  echo "TDengine root 口令已更新"
  exit 0
fi

echo "无法使用默认口令或目标口令登录 TDengine，请人工检查" >&2
exit 1
