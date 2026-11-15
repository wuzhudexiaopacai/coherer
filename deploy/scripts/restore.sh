#!/usr/bin/env bash
# Coherer 恢复：PostgreSQL + Redis + EMQX + TDengine + OTA 固件
# 用法：FORCE=1 ./scripts/restore.sh deploy/backups/20260101-120000
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
export MSYS_NO_PATHCONV=1

SRC="${1:?用法: FORCE=1 ./scripts/restore.sh <备份目录>}"
if [ "${FORCE:-0}" != "1" ]; then
  echo "恢复会覆盖当前数据。确认后使用：FORCE=1 $0 $SRC"
  exit 1
fi
ABS_SRC="$(cd "$SRC" && pwd -W 2>/dev/null || pwd)"
( cd "$SRC" && sha256sum -c SHA256SUMS )

echo "[1/5] PostgreSQL"
docker cp "$SRC/postgres.dump" coherer-postgres:/tmp/coherer.dump
docker exec -e PGPASSWORD="$COHERER_DB_PASSWORD" coherer-postgres \
  pg_restore -U coherer -d coherer --clean --if-exists --no-owner /tmp/coherer.dump

echo "[2/5] Redis"
docker compose stop redis >/dev/null
docker run --rm -v coherer_redis-data:/data -v "${ABS_SRC}:/backup:ro" alpine:3.20 \
  sh -c 'cp /backup/redis-dump.rdb /data/dump.rdb && chown 999:999 /data/dump.rdb'
docker compose start redis >/dev/null

echo "[3/5] EMQX"
docker compose stop emqx >/dev/null
docker run --rm -v coherer_emqx-data:/data -v "${ABS_SRC}:/backup:ro" alpine:3.20 \
  sh -c 'rm -rf /data/* && tar xzf /backup/emqx-data.tar.gz -C /data'
docker compose start emqx >/dev/null

echo "[4/5] TDengine"
if [ -d "$SRC/tdengine" ]; then
  docker cp "$SRC/tdengine" coherer-tdengine:/tmp/tdrestore
  docker exec coherer-tdengine taosdump -i /tmp/tdrestore || \
    echo "  [warn] TDengine 恢复失败，请人工检查"
else
  echo "  [skip] 备份中没有 TDengine 数据"
fi

echo "[5/5] OTA 固件"
if [ -f "$SRC/firmware-data.tar.gz" ]; then
  docker stop coherer-backend >/dev/null 2>&1 || true
  docker run --rm -v coherer_firmware-data:/data -v "${ABS_SRC}:/backup:ro" alpine:3.20 \
    sh -c 'rm -rf /data/* && tar xzf /backup/firmware-data.tar.gz -C /data'
  docker start coherer-backend >/dev/null 2>&1 || true
else
  echo "  [skip] 备份中没有 OTA 固件"
fi

echo
echo "恢复完成。后端已尝试重启；若未启动请手动执行 docker compose restart backend"
