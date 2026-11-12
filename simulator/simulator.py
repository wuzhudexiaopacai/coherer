"""Coherer 设备模拟器

模拟一批虚拟设备接入 EMQX，上报遥测、同步设备影子，并维护在线状态。

主题协议:
    遥测上报    {tenant}/{deviceId}/thing/property/report (QoS1)
    影子下行    {tenant}/{deviceId}/shadow/update  (retained)
    影子上报    {tenant}/{deviceId}/shadow/report  (QoS1)
    在线状态    {tenant}/{deviceId}/status        (online / LWT offline)

认证方式（EMQX 开启 HTTP 认证后必须使用凭证）:
    username = 毫秒时间戳
    password = hex(HMAC-SHA256(deviceSecret, deviceId + "|" + username))

用法:
    python simulator.py                            # 匿名连接（仅 EMQX 未开认证时可用）
    python simulator.py --credentials creds.json   # 使用注册设备凭证（认证模式）
    python simulator.py --count 100 --interval 3   # 匿名模拟 100 台设备
    python simulator.py --tls                      # TLS 8883（校验本地自签 CA）

凭证文件格式（由设备注册接口 / register_devices.py 生成）:
    [{"deviceId": "dev_xxx", "secret": "..."}]
"""

import argparse
import hashlib
import hmac
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
    """一台虚拟设备：独立 MQTT 连接 + 遥测上报 + 影子同步 + 在线状态。"""

    def __init__(self, host, port, tenant, device_id, interval,
                 tls=False, ca_file=None, insecure=False, device_secret=None):
        super().__init__(daemon=True, name=f"dev-{device_id}")
        self.host = host
        self.port = port
        self.device_id = device_id
        self.device_secret = device_secret
        self.interval = interval
        self.topic = f"{tenant}/{device_id}/thing/property/report"
        self.shadow_topic = f"{tenant}/{device_id}/shadow/update"
        self.shadow_report_topic = f"{tenant}/{device_id}/shadow/report"
        self.status_topic = f"{tenant}/{device_id}/status"
        self.temperature = random.uniform(18, 30)
        self.humidity = random.uniform(40, 65)

        # 认证模式下 clientId 必须是设备唯一标识，与平台注册信息一致
        client_id = device_id if device_secret else f"sim-{device_id}"
        self.client = mqtt.Client(
            callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
            client_id=client_id,
        )
        self.client.on_connect = self._on_connect
        self.client.on_disconnect = self._on_disconnect
        self.client.on_message = self._on_message
        # 异常断连（掉电/网络断）由 Broker 代发离线消息
        self.client.will_set(self.status_topic, "offline", qos=1, retain=False)

        if device_secret:
            self._refresh_auth()

        if tls:
            if insecure:
                self.client.tls_set()                 # 使用系统 CA（随后关闭校验）
                self.client.tls_insecure_set(True)
                print(f"[{device_id}] 警告: 已跳过服务端证书校验，仅限排障")
            else:
                self.client.tls_set(ca_certs=ca_file)

    def _refresh_auth(self) -> None:
        """重新生成时间戳签名，保证断线重连时凭证仍然有效。"""
        username = str(int(time.time() * 1000))
        content = f"{self.device_id}|{username}"
        password = hmac.new(
            self.device_secret.encode(), content.encode(), hashlib.sha256
        ).hexdigest()
        self.client.username_pw_set(username, password)

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
            if self.device_secret:
                self._refresh_auth()
            self._drift()
            self._publish_telemetry(seq)
            STOP.wait(self.interval)

        # 优雅退出：主动上报离线
        try:
            self.client.publish(self.status_topic, "offline", qos=1)
            time.sleep(0.2)
        except Exception:
            pass
        self.client.disconnect()
        self.client.loop_stop()

    def _publish_telemetry(self, seq: int) -> None:
        payload = {
            "id": seq,
            "ts": int(time.time() * 1000),
            "params": {
                "temperature": round(self.temperature, 2),
                "humidity": round(self.humidity, 1),
            },
        }
        self.client.publish(self.topic, json.dumps(payload), qos=1)

    def _publish_shadow_report(self, version=None) -> None:
        payload = {
            "reported": {
                "reportInterval": self.interval,
                "temperature": round(self.temperature, 2),
                "humidity": round(self.humidity, 1),
            }
        }
        if version is not None:
            payload["version"] = version
        self.client.publish(self.shadow_report_topic, json.dumps(payload), qos=1)

    def _drift(self) -> None:
        self.temperature += random.uniform(-0.4, 0.4)
        self.humidity += random.uniform(-1.0, 1.0)
        self.humidity = min(90.0, max(20.0, self.humidity))

    def _on_connect(self, client, userdata, flags, reason_code, properties):
        if reason_code.is_failure:
            print(f"[{self.device_id}] 连接失败: {reason_code}")
            return
        print(f"[{self.device_id}] 已连接, 上报主题: {self.topic}")
        client.publish(self.status_topic, "online", qos=1)
        client.subscribe(self.shadow_topic, qos=1)
        # 上线即上报一次当前状态
        self._publish_shadow_report()

    def _on_message(self, client, userdata, message):
        if message.topic != self.shadow_topic:
            return
        try:
            data = json.loads(message.payload.decode())
            desired = data.get("desired") or {}
            if isinstance(desired.get("reportInterval"), (int, float)):
                self.interval = max(1.0, min(60.0, float(desired["reportInterval"])))
                print(f"[{self.device_id}] 应用期望配置 reportInterval={self.interval}s")
            self._publish_shadow_report(data.get("version"))
        except Exception as e:
            print(f"[{self.device_id}] 影子消息处理失败: {e}")

    def _on_disconnect(self, client, userdata, flags, reason_code, properties):
        if not STOP.is_set():
            print(f"[{self.device_id}] 意外断开: {reason_code} (paho 会自动重连)")


def main() -> None:
    parser = argparse.ArgumentParser(description="Coherer 设备模拟器")
    parser.add_argument("--host", default="localhost", help="EMQX 地址 (默认 localhost)")
    parser.add_argument("--port", type=int, default=None,
                        help="EMQX 端口 (默认 1883；--tls 时默认 8883)")
    parser.add_argument("--tenant", default="default", help="租户 ID (默认 default)")
    parser.add_argument("--count", type=int, default=5, help="匿名模拟设备数量 (默认 5)")
    parser.add_argument("--interval", type=float, default=5.0, help="上报间隔秒 (默认 5)")
    parser.add_argument("--prefix", default="dev", help="匿名设备 ID 前缀 (默认 dev)")
    parser.add_argument("--credentials", default=None,
                        help="设备凭证 JSON 文件（认证模式）")
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

    if args.credentials:
        with open(args.credentials, encoding="utf-8") as f:
            credentials = json.load(f)
        devices = [
            VirtualDevice(args.host, port, args.tenant, item["deviceId"], args.interval,
                          tls=args.tls, ca_file=ca_file, insecure=args.insecure,
                          device_secret=item["secret"])
            for item in credentials
        ]
        mode = "认证设备"
    else:
        devices = [
            VirtualDevice(args.host, port, args.tenant, f"{args.prefix}-{i:04d}", args.interval,
                          tls=args.tls, ca_file=ca_file, insecure=args.insecure)
            for i in range(1, args.count + 1)
        ]
        mode = "匿名设备"

    signal.signal(signal.SIGINT, lambda *_: STOP.set())
    transport = "TLS" if args.tls else "TCP"
    print(f"启动 {len(devices)} 台{mode} → {transport} {args.host}:{port} (Ctrl+C 退出)")

    for device in devices:
        device.start()
    for device in devices:
        device.join()
    print("已全部退出")


if __name__ == "__main__":
    main()
