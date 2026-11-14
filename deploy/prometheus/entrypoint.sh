#!/bin/sh
# Prometheus 启动前把内部令牌注入到抓取配置。
# 令牌来自环境变量，只写入容器内 /tmp，不落仓库、不进入镜像。
set -eu

: "${COHERER_INTERNAL_TOKEN:?COHERER_INTERNAL_TOKEN is required}"

mkdir -p /tmp/prometheus
# 令牌限定为字母/数字/-/_，避免 sed 替换元字符问题
case "${COHERER_INTERNAL_TOKEN}" in
  *[!A-Za-z0-9_-]*)
    echo "COHERER_INTERNAL_TOKEN 含非法字符（仅允许字母、数字、-、_）" >&2
    exit 1
    ;;
esac

sed "s|__COHERER_INTERNAL_TOKEN__|${COHERER_INTERNAL_TOKEN}|g" \
  /etc/prometheus/prometheus.yml > /tmp/prometheus/prometheus.yml

exec /bin/prometheus \
  --config.file=/tmp/prometheus/prometheus.yml \
  --storage.tsdb.path=/prometheus \
  --storage.tsdb.retention.time=15d
