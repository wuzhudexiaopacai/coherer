import argparse
import hashlib
import hmac
import json
import os
import ssl
import time
from pathlib import Path

import paho.mqtt.client as mqtt

DEFAULT_CA = Path(__file__).resolve().parent.parent / "deploy" / "certs" / "ca.crt"


def main() -> None:
    parser = argparse.ArgumentParser(description="Coherer 边缘主机模拟器")
    parser.add_argument("--host", default="localhost")
    parser.add_argument("--port", type=int, default=8883)
    parser.add_argument("--credentials", required=True,
                        help="主机凭证 JSON：{hostId, hostSecret, tenant}")
    parser.add_argument("--ca-file", default=str(DEFAULT_CA))
    parser.add_argument("--insecure", action="store_true")
    args = parser.parse_args()

    data = json.loads(Path(args.credentials).read_text(encoding="utf-8"))
    host_id = data["hostId"]
    secret = data["hostSecret"]
    tenant = data.get("tenant", "default")

    client = mqtt.Client(
        callback_api_version=mqtt.CallbackAPIVersion.VERSION2,
        client_id=host_id,
    )
    username = str(int(time.time() * 1000))
    password = hmac.new(
        secret.encode(), f"{host_id}|{username}".encode(), hashlib.sha256
    ).hexdigest()
    client.username_pw_set(username, password)

    if args.insecure:
        client.tls_set(cert_reqs=ssl.CERT_NONE)
        client.tls_insecure_set(True)
    else:
        client.tls_set(ca_certs=args.ca_file)

    status_topic = f"{tenant}/{host_id}/host/status"
    command_filter = f"{tenant}/{host_id}/sub/+/thing/service/+"
    client.will_set(status_topic, "offline", qos=1, retain=False)

    def on_connect(c, userdata, flags, reason_code, properties=None):
        if reason_code.is_failure:
            print(f"[{host_id}] 连接失败: {reason_code}")
            return
        print(f"[{host_id}] 已连接，订阅 {command_filter}")
        c.publish(status_topic, "online", qos=1)
        c.subscribe(command_filter, qos=1)

    def on_message(c, userdata, message):
        levels = message.topic.split("/")
        if len(levels) != 7 or levels[2] != "sub":
            return
        device_id = levels[3]
        try:
            payload = json.loads(message.payload.decode())
        except Exception as error:
            print(f"[{host_id}] 命令解析失败: {error}")
            return
        request_id = payload.get("id")
        identifier = payload.get("identifier") or levels[5]
        params = payload.get("params") or {}
        result = {"deviceId": device_id, "identifier": identifier, "via": "host"}
        code = 0
        if identifier == "reboot":
            result["status"] = "rebooting"
        elif identifier == "setInterval":
            result["interval"] = params.get("seconds", 5)
        else:
            result["echo"] = params
        reply_topic = f"{tenant}/{host_id}/sub/{device_id}/thing/service/reply"
        c.publish(reply_topic, json.dumps({
            "id": request_id,
            "code": code,
            "result": result,
            "ts": int(time.time() * 1000),
        }), qos=1)
        print(f"[{host_id}] 转发命令 {identifier} -> {device_id}，已回执")

    client.on_connect = on_connect
    client.on_message = on_message
    client.connect(args.host, args.port, keepalive=30)
    client.loop_start()
    last_heartbeat = 0.0
    try:
        while True:
            now = time.time()
            # 心跳：每 60 秒续期一次在线 TTL，避免只靠 LWT
            if now - last_heartbeat >= 60:
                c = client
                c.publish(status_topic, "online", qos=1)
                last_heartbeat = now
            time.sleep(1)
            # 时间戳签名 5 分钟有效，定期刷新以便断线重连
            username = str(int(time.time() * 1000))
            password = hmac.new(
                secret.encode(), f"{host_id}|{username}".encode(), hashlib.sha256
            ).hexdigest()
            client.username_pw_set(username, password)
    except KeyboardInterrupt:
        pass
    finally:
        try:
            client.publish(status_topic, "offline", qos=1)
            time.sleep(0.2)
        except Exception:
            pass
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    main()
