#!/usr/bin/env bash
# 集成测试一键入口：启动专用 PG/Redis → mvn clean verify（单元 + *IT + 覆盖率门禁）→ 清理
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
COMPOSE="$ROOT/deploy/docker-compose.test.yml"

cleanup() {
  docker compose -f "$COMPOSE" down -v >/dev/null 2>&1 || true
}
trap cleanup EXIT

docker compose -f "$COMPOSE" up -d --wait
cd "$ROOT/backend"
mvn -B -ntp clean verify "$@"
