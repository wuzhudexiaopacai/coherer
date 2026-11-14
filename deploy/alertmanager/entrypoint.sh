#!/bin/sh
# Alertmanager 启动前注入 webhook 地址（Alertmanager 配置本身不展开环境变量）。
# 未配置 ALERT_WEBHOOK_URL 时指向本地占位地址：不会对外发消息，仅保证配置合法。
set -eu

WEBHOOK_URL="${ALERT_WEBHOOK_URL:-http://127.0.0.1:9999/alert}"

case "${WEBHOOK_URL}" in
  http://*|https://*) ;;
  *)
    echo "ALERT_WEBHOOK_URL 必须以 http:// 或 https:// 开头" >&2
    exit 1
    ;;
esac

mkdir -p /tmp/alertmanager
sed "s|__ALERT_WEBHOOK_URL__|${WEBHOOK_URL}|g" \
  /etc/alertmanager/alertmanager.yml > /tmp/alertmanager/alertmanager.yml

exec /bin/alertmanager \
  --config.file=/tmp/alertmanager/alertmanager.yml \
  --storage.path=/alertmanager
