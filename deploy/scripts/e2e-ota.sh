#!/usr/bin/env bash
# OTA 端到端验收：登录 → 建产品/设备 → 上传固件 → 启动模拟器 → 下发任务 → 等待 SUCCESS
# 前置：compose app 栈已启动（backend/frontend/EMQX/PG/Redis 健康），simulator 依赖已安装
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/../.." && pwd)"
BASE_URL="${COHERER_BASE_URL:-https://localhost:8443}"
export PYTHONIOENCODING=utf-8

set -a
. "$ROOT/backend/.env"
set +a
ADMIN_PASSWORD="${COHERER_ADMIN_PASSWORD:?COHERER_ADMIN_PASSWORD not found in backend/.env}"

TMP_DIR="$(mktemp -d)"
SIM_PID=""
cleanup() {
  if [ -n "$SIM_PID" ]; then
    kill "$SIM_PID" >/dev/null 2>&1 || true
    wait "$SIM_PID" >/dev/null 2>&1 || true
  fi
  rm -rf "$TMP_DIR"
}
trap cleanup EXIT

json_get() {
  python -c 'import sys,json; d=json.load(sys.stdin); print(eval(sys.argv[1], {"d": d}))' "$1"
}

echo "[1/7] 登录 ${BASE_URL}"
LOGIN="$(curl -sk "$BASE_URL/api/auth/login" -H 'Content-Type: application/json' \
  -d "{\"username\":\"admin\",\"password\":\"$ADMIN_PASSWORD\"}")"
TOKEN="$(printf '%s' "$LOGIN" | json_get 'd["data"]["accessToken"]')"
[ -n "$TOKEN" ] || { echo "登录失败: $LOGIN"; exit 1; }
AUTH="Authorization: Bearer $TOKEN"

SUFFIX="$(date +%s)"
echo "[2/7] 创建产品/设备"
PRODUCT="$(curl -sk "$BASE_URL/api/products" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"name\":\"e2e-$SUFFIX\",\"category\":\"E2E\",\"nodeType\":\"DIRECT\"}")"
PRODUCT_ID="$(printf '%s' "$PRODUCT" | json_get 'd["data"]["id"]')"

DEVICES="$(curl -sk "$BASE_URL/api/devices" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"productId\":\"$PRODUCT_ID\",\"count\":1,\"namePrefix\":\"e2e\"}")"
DEVICE_ID="$(printf '%s' "$DEVICES" | json_get 'd["data"][0]["deviceId"]')"
DEVICE_SECRET="$(printf '%s' "$DEVICES" | json_get 'd["data"][0]["deviceSecret"]')"
printf '[{"deviceId":"%s","secret":"%s"}]' "$DEVICE_ID" "$DEVICE_SECRET" \
  > "$TMP_DIR/creds.json"

echo "[3/7] 上传固件"
head -c 1048576 /dev/urandom > "$TMP_DIR/firmware.bin"
VERSION="e2e-$SUFFIX"
# Git Bash 的 curl/python 是原生 Windows 程序，文件参数需要 Windows 路径
FIRMWARE_WIN="$(cygpath -w "$TMP_DIR/firmware.bin")"
FIRMWARE="$(curl -sk "$BASE_URL/api/ota/firmwares" -H "$AUTH" \
  -F "productId=$PRODUCT_ID" -F "version=$VERSION" \
  -F "file=@$FIRMWARE_WIN;type=application/octet-stream")"
FIRMWARE_ID="$(printf '%s' "$FIRMWARE" | json_get 'd["data"]["id"]')"

echo "[4/7] 启动模拟器 deviceId=$DEVICE_ID"
CREDS_WIN="$(cygpath -w "$TMP_DIR/creds.json")"
(
  cd "$ROOT/simulator"
  python -u simulator.py --credentials "$CREDS_WIN" --tls
) > "$TMP_DIR/simulator.log" 2>&1 &
SIM_PID=$!
for _ in $(seq 1 30); do
  grep -q "已连接" "$TMP_DIR/simulator.log" && break
  sleep 1
done
grep -q "已连接" "$TMP_DIR/simulator.log" || {
  echo "模拟器未连接:"; cat "$TMP_DIR/simulator.log"; exit 1;
}
# on_connect 打印后才会 subscribe 三个主题；等待订阅稳定再下发，避免 QoS1 消息落在订阅前
sleep 3

echo "[5/7] 下发 OTA 任务 version=$VERSION"
TASK="$(curl -sk "$BASE_URL/api/ota/tasks" -H "$AUTH" -H 'Content-Type: application/json' \
  -d "{\"deviceId\":\"$DEVICE_ID\",\"firmwareId\":\"$FIRMWARE_ID\",\"maxAttempts\":2}")"
TASK_ID="$(printf '%s' "$TASK" | json_get 'd["data"]["id"]')"

echo "[6/7] 等待任务完成（最多 120 秒）"
STATUS=""
for _ in $(seq 1 60); do
  sleep 2
  CURRENT="$(curl -sk "$BASE_URL/api/ota/tasks?deviceId=$DEVICE_ID&limit=1" -H "$AUTH")"
  STATUS="$(printf '%s' "$CURRENT" | json_get 'd["data"][0]["status"]')"
  PROGRESS="$(printf '%s' "$CURRENT" | json_get 'd["data"][0]["progress"]')"
  echo "  task=$TASK_ID status=$STATUS progress=$PROGRESS"
  case "$STATUS" in
    SUCCESS) break ;;
    FAILED|TIMEOUT) break ;;
  esac
done

echo "[7/7] 结果"
if [ "$STATUS" != "SUCCESS" ]; then
  echo "E2E 失败: status=$STATUS"
  echo "--- simulator ---"; tail -30 "$TMP_DIR/simulator.log"
  exit 1
fi
grep -q "OTA 升级成功" "$TMP_DIR/simulator.log" || {
  echo "任务 SUCCESS 但模拟器未打印升级成功"; tail -20 "$TMP_DIR/simulator.log"; exit 1;
}
echo "E2E 通过：设备 $DEVICE_ID 升级到 $VERSION（task=$TASK_ID）"
