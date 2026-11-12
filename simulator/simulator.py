"""Coherer 设备模拟器

模拟一批虚拟设备接入 EMQX 并周期上报遥测数据。
主题规划与平台一致: {tenantId}/{deviceId}/thing/property/report (QoS1)

用法:
    python simulator.py                       # 默认 5 台设备（明文 1883）
    python simulator.py --count 100 --interval 3
    python simulator.py --tls                 # TLS 8883，校验本地自签 CA
    python simulator.py --tls --insecure      # 跳过证书校验（仅排障用）
"""

import argparse
import json
import os
import random
import signal
import threading
import time

import paho.mqtt.client as mqtt

STOP = threading.Event()

# 默认 CA 路径：仓库根目录下 deploy/certs/ca.crt
DEFAULT_CA_FILE = os.path.normpath(
    os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "deploy", "certs", "ca.crt")
)


class VirtualDevice(threading.Thread):
    """一台虚拟设备：独立 MQTT 连接 + 周期上报温度/湿度（随机游走）。"""

    def __init__(self, host, port, tenant, device_id, interval,
                 tls=False, ca_file=None, insecure=False):
        super().__init__(daemon=True, name=f"dev-{device_id}")
        self.host = host
        self.port = port
        self.device_id = device_id
        self.interval = interval
        self.topic = f"{tenant}/{device_id}/thing/property/report"
        self.temperature = random.uniform(18, 30)
        self.humidity = random.uniform(40, 65)
        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=f"sim-{device_id}",
        )
        self.client.on_connect = self._on_connect
        self.client.on_disconnect = self._on_disconnect

        if tls:
            if insecure:
                self.client.tls_set()                 # 使用系统 CA（随后关闭校验）
                self.client.tls_insecure_set(True)
                print(f"[{device_id}] 警告: 已跳过服务端证书校验，仅限排障")
            else:
                self.client.tls_set(ca_certs=ca_file)

    def run(self) -> None:
        try:
            self.client.connect(self.host, self.port, keepalive=30)
        except OSError as e:
            print(f"[{self.device_id}] 无法连接 {self.host}:{self.port}: {e}")
            return
        self.client.loop_start()
        seq = 0
        while not STOP.is_set():
            seq += 1
            self._drift()
            payload = {
                "id": seq,
                "ts": int(time.time() * 1000),
                "params": {
                    "temperature": round(self.temperature, 2),
                    "humidity": round(self.humidity, 1),
                },
            }
            self.client.publish(self.topic, json.dumps(payload), qos=1)
            STOP.wait(self.interval)
        self.client.disconnect()
        self.client.loop_stop()

    def _drift(self) -> None:
        self.temperature += random.uniform(-0.4, 0.4)
        self.humidity += random.uniform(-1.0, 1.0)
        self.humidity = min(90.0, max(20.0, self.humidity))

    def _on_connect(self, client, userdata, flags, reason_code, properties):
        if reason_code.is_failure:
            print(f"[{self.device_id}] 连接失败: {reason_code}")
        else:
            print(f"[{self.device_id}] 已连接, 上报主题: {self.topic}")

    def _on_disconnect(self, client, userdata, flags, reason_code, properties):
        if not STOP.is_set():
            print(f"[{self.device_id}] 意外断开: {reason_code} (paho 会自动重连)")


def main() -> None:
    parser = argparse.ArgumentParser(description="Coherer 设备模拟器")
    parser.add_argument("--host", default="localhost", help="EMQX 地址 (默认 localhost)")
    parser.add_argument("--port", type=int, default=None,
                        help="EMQX 端口 (默认 1883；--tls 时默认 8883)")
    parser.add_argument("--tenant", default="default", help="租户 ID (默认 default)")
    parser.add_argument("--count", type=int, default=5, help="模拟设备数量 (默认 5)")
    parser.add_argument("--interval", type=float, default=5.0, help="上报间隔秒 (默认 5)")
    parser.add_argument("--prefix", default="dev", help="设备 ID 前缀 (默认 dev)")
    parser.add_argument("--tls", action="store_true", help="使用 TLS 连接")
    parser.add_argument("--ca-file", default=None,
                        help="CA 证书路径 (默认 deploy/certs/ca.crt)")
    parser.add_argument("--insecure", action="store_true",
                        help="跳过服务端证书校验（仅排障）")
    args = parser.parse_args()

    port = args.port or (8883 if args.tls else 1883)
    ca_file = args.ca_file or DEFAULT_CA_FILE

    if args.tls and not args.insecure and not os.path.isfile(ca_file):
        print(f"CA 证书不存在: {ca_file}")
        print("请先运行: cd deploy/certs && ./gen-certs.sh")
        raise SystemExit(1)

    devices = [
        VirtualDevice(args.host, port, args.tenant, f"{args.prefix}-{i:04d}", args.interval,
                      tls=args.tls, ca_file=ca_file, insecure=args.insecure)
        for i in range(1, args.count + 1)
    ]

    signal.signal(signal.SIGINT, lambda *_: STOP.set())
    mode = "TLS" if args.tls else "TCP"
    print(f"启动 {len(devices)} 台虚拟设备 → {mode} {args.host}:{port} (Ctrl+C 退出)")

    for device in devices:
        device.start()
    for device in devices:
        device.join()
    print("已全部退出")


if __name__ == "__main__":
    main()
