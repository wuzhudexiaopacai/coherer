"""注册演示设备并生成模拟器凭证文件

自动完成：查找/创建产品 → 批量注册设备 → 输出凭证 JSON（含一次性密钥）。

用法:
    python register_devices.py --count 5
    python register_devices.py --product-name 我的产品 --count 10 --out creds.json

后端需要先启动（默认 http://localhost:8081）。生成的凭证文件请勿提交到 git。
"""

import argparse
import json
import sys
import urllib.error
import urllib.parse
import urllib.request


def call(base, method, path, body=None):
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(
        base + path, data=data, headers={"Content-Type": "application/json"}, method=method)
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
    parser = argparse.ArgumentParser(description="注册演示设备并生成凭证文件")
    parser.add_argument("--base", default="http://localhost:8081", help="后端地址")
    parser.add_argument("--product-name", default="演示产品", help="产品名称（不存在则创建）")
    parser.add_argument("--count", type=int, default=5, help="注册设备数量")
    parser.add_argument("--prefix", default="demo", help="设备名前缀")
    parser.add_argument("--out", default="creds.json", help="凭证输出文件")
    args = parser.parse_args()

    base = args.base.rstrip("/")

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

    # 批量注册设备
    devices = call(base, "POST", "/api/devices", {
        "productId": product["id"],
        "count": args.count,
        "namePrefix": args.prefix,
    })["data"]

    creds = [{"deviceId": d["deviceId"], "secret": d["deviceSecret"]} for d in devices]
    with open(args.out, "w", encoding="utf-8") as f:
        json.dump(creds, f, ensure_ascii=False, indent=2)

    print(f"已注册 {len(creds)} 台设备，凭证写入 {args.out}")
    print(f"启动模拟器: python simulator.py --credentials {args.out} --tls")


if __name__ == "__main__":
    main()
