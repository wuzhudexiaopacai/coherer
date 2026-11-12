# 本地开发证书

由 `gen-certs.sh` 生成，仅供本地开发/联调使用，**不会提交到 git**（私钥不入库）。

| 文件 | 用途 |
|---|---|
| `ca.crt` / `ca.key` | 自签根 CA；设备/模拟器用它校验服务端身份 |
| `server.crt` / `server.key` | EMQX TLS 监听器证书（SAN: `localhost` / `emqx` / `127.0.0.1`） |
| `client.crt` / `client.key` | 可选，`./gen-certs.sh --mtls` 生成，供 M4 双向认证联调 |

## 常用命令

```bash
./gen-certs.sh            # 生成 CA + 服务端证书
./gen-certs.sh --mtls     # 额外生成设备客户端证书
openssl verify -CAfile ca.crt server.crt   # 校验链
```

重新生成：删除本目录下的证书文件后重跑脚本。

## 信任 CA（可选）

浏览器通过 WSS（8084）连接时，需要把 `ca.crt` 装入系统/浏览器受信任根（仅开发机）：

- Windows：双击 `ca.crt` → 安装证书 → 本地计算机 → **受信任的根证书颁发机构**

> 生产环境使用正规 CA / 云厂商证书，并通过密钥管理服务注入，切勿使用本目录生成的材料。
