"""注册演示设备并生成模拟器凭证文件

自动完成：查找/创建产品 → 应用演示物模型 → 批量注册设备 → 输出凭证 JSON（含一次性密钥）。

用法:
    python register_devices.py --tenant default --password '<admin password>' --count 5
    python register_devices.py --product-name 我的产品 --count 10 --out creds.json
    python register_devices.py --model-only        # 只更新演示物模型

密码优先取环境变量 COHERER_ADMIN_PASSWORD；后端需要先启动（默认 http://localhost:8081）。
生成的凭证文件请勿提交到 git。
"""

import argparse
import json
import os
import sys
import urllib.error
import urllib.parse
import urllib.request

TOKEN = None

# 模拟器上报的属性集合，必须与物模型一致，否则遥测会被校验丢弃
DEMO_MODEL_ITEMS = [
    {"type": "PROPERTY", "identifier": "temperature", "name": "温度",
     "dataType": "FLOAT", "accessMode": "R", "unit": "℃",
     "spec": {"min": -40, "max": 125}},
    {"type": "PROPERTY", "identifier": "humidity", "name": "湿度",
     "dataType": "FLOAT", "accessMode": "R", "unit": "%RH",
     "spec": {"min": 0, "max": 100}},
    {"type": "PROPERTY", "identifier": "reportInterval", "name": "上报间隔",
     "dataType": "INT", "accessMode": "RW", "unit": "s",
     "spec": {"min": 1, "max": 60}},
    {"type": "PROPERTY", "identifier": "battery", "name": "电量",
     "dataType": "INT", "accessMode": "R", "unit": "%",
     "spec": {"min": 0, "max": 100}},
    {"type": "PROPERTY", "identifier": "signal", "name": "信号强度",
     "dataType": "INT", "accessMode": "R", "unit": "dBm",
     "spec": {"min": -120, "max": 0}},
    {"type": "PROPERTY", "identifier": "voltage", "name": "电池电压",
     "dataType": "FLOAT", "accessMode": "R", "unit": "V",
     "spec": {"min": 3.0, "max": 4.5}},
    {"type": "PROPERTY", "identifier": "doorOpen", "name": "门磁状态",
     "dataType": "BOOL", "accessMode": "R"},
    {"type": "PROPERTY", "identifier": "co2", "name": "二氧化碳",
     "dataType": "INT", "accessMode": "R", "unit": "ppm",
     "spec": {"min": 400, "max": 2000}},
    {"type": "EVENT", "identifier": "highTempAlarm", "name": "高温告警",
     "spec": {"params": {"temperature": "FLOAT"}}},
    {"type": "SERVICE", "identifier": "reboot", "name": "重启",
     "spec": {"input": {"delaySeconds": "INT"}}},
]


def call(base, method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    headers = {"Content-Type": "application/json"}
    if TOKEN:
        headers["Authorization"] = f"Bearer {TOKEN}"
    req = urllib.request.Request(base + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(req) as r:
            return json.load(r)
    except urllib.error.HTTPError as e:
        print(f"HTTP {e.code}: {e.read().decode('utf-8', 'replace')}", file=sys.stderr)
        raise SystemExit(1)
    except urllib.error.URLError as e:
        print(f"无法连接后端 {base}: {e.reason}（先启动 backend）", file=sys.stderr)
        raise SystemExit(1)


def main():
    global TOKEN
    parser = argparse.ArgumentParser(description="注册演示设备并生成凭证文件")
    parser.add_argument("--base", default="http://localhost:8081", help="后端地址")
    parser.add_argument("--tenant", default="default", help="租户代码（默认 default）")
    parser.add_argument("--username", default="admin", help="登录用户名（默认 admin）")
    parser.add_argument("--password", default=os.environ.get("COHERER_ADMIN_PASSWORD"),
                        help="登录密码；也可用环境变量 COHERER_ADMIN_PASSWORD")
    parser.add_argument("--product-name", default="演示产品", help="产品名称（不存在则创建）")
    parser.add_argument("--count", type=int, default=5, help="注册设备数量")
    parser.add_argument("--prefix", default="demo", help="设备名前缀")
    parser.add_argument("--out", default="creds.json", help="凭证输出文件")
    parser.add_argument("--model-only", action="store_true", help="只更新演示物模型，不注册设备")
    args = parser.parse_args()

    if not args.password:
        print("缺少登录密码：请传 --password 或设置 COHERER_ADMIN_PASSWORD",
              file=sys.stderr)
        raise SystemExit(1)

    base = args.base.rstrip("/")

    # 多租户登录：租户代码决定后续 API 的数据边界
    login = call(base, "POST", "/api/auth/login", {
        "tenantCode": args.tenant,
        "username": args.username,
        "password": args.password,
    })
    TOKEN = login["data"]["accessToken"]
    print(f"已登录租户 {args.tenant}（{args.username}）")

    # 查找或创建产品
    keyword = urllib.parse.quote(args.product_name)
    page = call(base, "GET", f"/api/products?current=1&size=1&keyword={keyword}")["data"]
    if page["records"]:
        product = page["records"][0]
        print(f"使用已有产品: {product['name']} ({product['productKey']})")
    else:
        product = call(base, "POST", "/api/products", {
            "name": args.product_name,
            "category": "DEMO",
        })["data"]
        print(f"创建产品: {product['name']} ({product['productKey']})")

    # 应用演示物模型（属性集合与模拟器上报保持一致）
    call(base, "PUT", f"/api/products/{product['id']}/thing-model", {"items": DEMO_MODEL_ITEMS})
    print("物模型已应用：8 个属性 + 1 个事件 + 1 个服务")

    if args.model_only:
        print("已跳过设备注册（--model-only）")
        return

    # 批量注册设备
    devices = call(base, "POST", "/api/devices", {
        "productId": product["id"],
        "count": args.count,
        "namePrefix": args.prefix,
    })["data"]

    creds = [{"tenant": args.tenant, "deviceId": d["deviceId"],
              "secret": d["deviceSecret"]} for d in devices]
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(creds, f, ensure_ascii=False, indent=2)

    print(f"已注册 {len(creds)} 台设备，凭证写入 {args.out}")
    print(f"启动模拟器: python simulator.py --credentials {args.out} --tls")


if __name__ == "__main__":
    main()
