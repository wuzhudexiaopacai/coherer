#!/usr/bin/env bash
# Coherer 备份：PostgreSQL + Redis + EMQX + TDengine + OTA 固件
# 用法：./scripts/backup.sh [备份根目录，默认 deploy/backups]
set -euo pipefail
cd "$(dirname "$0")/.."
set -a; . ./.env; set +a
export MSYS_NO_PATHCONV=1

BASE_DIR="${1:-backups}"
STAMP="$(date +%Y%m%d-%H%M%S)"
OUT="${BASE_DIR}/${STAMP}"
mkdir -p "$OUT"
ABS_OUT="$(cd "$OUT" && pwd -W 2>/dev/null || pwd)"

echo "[1/5] PostgreSQL"
docker exec -e PGPASSWORD="$COHERER_DB_PASSWORD" coherer-postgres \
  pg_dump -U coherer -d coherer -Fc -f /tmp/coherer.dump
docker cp coherer-postgres:/tmp/coherer.dump "$OUT/postgres.dump"

echo "[2/5] Redis"
docker exec -e REDISCLI_AUTH="$COHERER_REDIS_PASSWORD" coherer-redis \
  redis-cli --no-auth-warning BGSAVE >/dev/null
for _ in $(seq 1 60); do
  if docker exec -e REDISCLI_AUTH="$COHERER_REDIS_PASSWORD" coherer-redis \
      redis-cli --no-auth-warning INFO persistence | grep -q 'rdb_bgsave_in_progress:0'; then
    break
  fi
  sleep 1
done
docker cp coherer-redis:/data/dump.rdb "$OUT/redis-dump.rdb"

echo "[3/5] EMQX"
docker run --rm \
  -v coherer_emqx-data:/data:ro \
  -v "${ABS_OUT}:/backup" \
  alpine:3.20 \
  tar czf /backup/emqx-data.tar.gz -C /data .

echo "[4/5] TDengine"
if docker exec coherer-tdengine sh -c 'command -v taosdump >/dev/null'; then
  if docker exec coherer-tdengine sh -c \
      "rm -rf /tmp/tddump && mkdir -p /tmp/tddump && taosdump -h localhost -uroot -p'$COHERER_TDENGINE_PASSWORD' -D coherer -o /tmp/tddump"; then
    docker cp coherer-tdengine:/tmp/tddump "$OUT/tdengine"
  else
    echo "  [warn] taosdump 失败，跳过 TDengine 备份"
  fi
else
  echo "  [warn] 镜像无 taosdump，跳过 TDengine 备份"
fi

echo "[5/5] OTA 固件"
if docker volume inspect coherer_firmware-data >/dev/null 2>&1; then
  docker run --rm \
    -v coherer_firmware-data:/data:ro \
    -v "${ABS_OUT}:/backup" \
    alpine:3.20 \
    tar czf /backup/firmware-data.tar.gz -C /data .
else
  echo "  [warn] 固件卷不存在，跳过 OTA 固件备份"
fi

( cd "$OUT" && find . -type f ! -name SHA256SUMS -exec sha256sum {} + | sort > SHA256SUMS )
find "$BASE_DIR" -mindepth 1 -maxdepth 1 -type d -mtime +7 -exec rm -rf {} +

echo
echo "备份完成：$OUT"
ls -lh "$OUT"
