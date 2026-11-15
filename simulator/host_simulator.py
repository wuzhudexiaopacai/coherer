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
    parser.add_argument("--child-device", action="append", default=[],
                        help="代理上报的子设备 deviceId，可重复")
    parser.add_argument("--child-interval", type=float, default=10.0,
                        help="子设备遥测上报间隔秒")
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
    ota_filter = f"{tenant}/{host_id}/sub/+/ota/upgrade"
    client.will_set(status_topic, "offline", qos=1, retain=False)

    def on_connect(c, userdata, flags, reason_code, properties=None):
        if reason_code.is_failure:
            print(f"[{host_id}] 连接失败: {reason_code}")
            return
        print(f"[{host_id}] 已连接，订阅 {command_filter} 与 OTA 指令")
        c.publish(status_topic, "online", qos=1)
        c.subscribe(command_filter, qos=1)
        c.subscribe(ota_filter, qos=1)
        for child in args.child_device:
            c.publish(f"{tenant}/{host_id}/sub/{child}/status", "online", qos=1)

    def forward_ota(c, levels, payload):
        """模拟网关把 OTA 指令转发给子设备，并代传进度。"""
        device_id = levels[3]
        task_id = payload.get("taskId")
        if task_id is None:
            print(f"[{host_id}] OTA 指令缺少 taskId")
            return
        print(f"[{host_id}] 转发 OTA -> {device_id} taskId={task_id}"
              f" version={payload.get('version')}")
        progress_topic = f"{tenant}/{host_id}/sub/{device_id}/ota/progress"
        attempt = payload.get("attempt", 1)
        for progress in (20, 60, 100):
            c.publish(progress_topic, json.dumps({
                "taskId": task_id,
                "status": "SUCCESS" if progress == 100 else "DOWNLOADING",
                "progress": progress,
                "attempt": attempt,
                "ts": int(time.time() * 1000),
            }), qos=1)
            time.sleep(0.5)
        print(f"[{host_id}] 子设备 {device_id} OTA 完成")

    def on_message(c, userdata, message):
        levels = message.topic.split("/")
        if len(levels) == 6 and levels[2] == "sub" and levels[4] == "ota" \
                and levels[5] == "upgrade":
            try:
                forward_ota(c, levels, json.loads(message.payload.decode()))
            except Exception as error:
                print(f"[{host_id}] OTA 指令处理失败: {error}")
            return
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
    last_child_report = 0.0
    child_seq = 0
    try:
        while True:
            now = time.time()
            # 心跳：每 60 秒续期一次在线 TTL，避免只靠 LWT
            if now - last_heartbeat >= 60:
                client.publish(status_topic, "online", qos=1)
                last_heartbeat = now
            # 代理子设备遥测：主机代为上报，验证 sub 数据链路
            if args.child_device and now - last_child_report >= args.child_interval:
                child_seq += 1
                for child in args.child_device:
                    payload = {
                        "id": f"host-{child}-{child_seq}",
                        "ts": int(time.time() * 1000),
                        "params": {"temperature": 24.5, "humidity": 50.0},
                    }
                    client.publish(
                        f"{tenant}/{host_id}/sub/{child}/thing/property/report",
                        json.dumps(payload), qos=1)
                    print(f"[{host_id}] 代理遥测 {child} seq={child_seq}")
                last_child_report = now
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
            for child in args.child_device:
                client.publish(f"{tenant}/{host_id}/sub/{child}/status", "offline", qos=1)
            time.sleep(0.2)
        except Exception:
            pass
        client.loop_stop()
        client.disconnect()


if __name__ == "__main__":
    main()
