#!/usr/bin/env bash
# Coherer 本地开发证书生成脚本
# 生成：自签 CA + EMQX 服务端证书（SAN: localhost / emqx / 127.0.0.1）
#
# 用法：
#   ./gen-certs.sh           # CA + 服务端证书
#   ./gen-certs.sh --mtls    # 额外生成设备客户端证书（双向认证联调用）
#
# 仅用于本地开发；生产环境请使用正规 CA 签发的证书并通过密钥管理注入。
set -euo pipefail
cd "$(dirname "$0")"

# Git Bash (MSYS2) 会把 "/C=CN/..." 风格的 -subj 参数误当成 Windows 路径转换，这里关闭
export MSYS_NO_PATHCONV=1

DAYS_CA=3650     # CA 有效期 10 年
DAYS_LEAF=825    # 叶子证书 825 天（浏览器信任上限）

if [ -f ca.crt ] && [ -f server.crt ]; then
  echo "[skip] 证书已存在（ca.crt / server.crt），无需重复生成"
  echo "       如需重新生成：删除 ca.* server.* client.* 后重试"
else
  echo "[1/4] 生成 CA 私钥（RSA 4096）"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:4096 -out ca.key

  echo "[2/4] 生成自签 CA 证书（10 年）"
  openssl req -x509 -new -key ca.key -sha256 -days "$DAYS_CA" \
    -subj "/C=CN/O=Coherer Dev/CN=Coherer Local Dev CA" \
    -addext "basicConstraints=critical,CA:TRUE" \
    -addext "keyUsage=critical,keyCertSign,cRLSign" \
    -out ca.crt

  echo "[3/4] 生成服务端私钥与 CSR"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out server.key
  openssl req -new -key server.key -subj "/C=CN/O=Coherer Dev/CN=localhost" -out server.csr

  cat > server.ext <<'EOF'
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:localhost, DNS:emqx, IP:127.0.0.1
EOF

  echo "[4/4] 用 CA 签发服务端证书（825 天）"
  openssl x509 -req -in server.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
    -days "$DAYS_LEAF" -sha256 -extfile server.ext -out server.crt

  rm -f server.csr server.ext
  echo "[ok] 已生成 ca.crt / ca.key / server.crt / server.key"
fi

# PostgreSQL 服务端证书（SAN: postgres / localhost / 127.0.0.1）
if [ ! -f pg.crt ]; then
  echo "[pg] 生成 PostgreSQL TLS 证书"
  openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out pg.key
  openssl req -new -key pg.key -subj "/C=CN/O=Coherer Dev/CN=postgres" -out pg.csr
  cat > pg.ext <<'EOF'
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = serverAuth
subjectAltName = DNS:postgres, DNS:localhost, IP:127.0.0.1
EOF
  openssl x509 -req -in pg.csr -CA ca.crt -CAkey ca.key -CAcreateserial     -days "$DAYS_LEAF" -sha256 -extfile pg.ext -out pg.crt
  rm -f pg.csr pg.ext
  echo "[ok] 已生成 pg.crt / pg.key"
else
  echo "[skip] pg.crt 已存在"
fi

if [ "${1:-}" = "--mtls" ]; then
  if [ -f client.crt ]; then
    echo "[skip] client.crt 已存在"
  else
    echo "[mtls] 生成设备客户端证书"
    openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048 -out client.key
    openssl req -new -key client.key -subj "/C=CN/O=Coherer Dev/CN=device-client" -out client.csr
    cat > client.ext <<'EOF'
basicConstraints = CA:FALSE
keyUsage = digitalSignature, keyEncipherment
extendedKeyUsage = clientAuth
EOF
    openssl x509 -req -in client.csr -CA ca.crt -CAkey ca.key -CAcreateserial \
      -days "$DAYS_LEAF" -sha256 -extfile client.ext -out client.crt
    rm -f client.csr client.ext
    echo "[ok] 已生成 client.crt / client.key"
  fi
fi

# 本地开发放宽读权限，保证容器内 emqx 用户可读；生产请使用独立的密钥管理
chmod 644 ./*.key ./*.crt 2>/dev/null || true

echo
echo "完成。下一步：cd .. && docker compose up -d"
echo "自检：openssl verify -CAfile ca.crt server.crt"
