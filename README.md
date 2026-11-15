# Coherer · 企业级 IoT 设备管理平台

> **Coherer（粉末检波器）**：1894 年问世的第一个能检测无线电波的器件，让人类第一次把不可见的电磁波动变成了可读的信号。本项目做同一件事——把物理世界的信号，变成可用的数据。

一个对标阿里云 IoT / ThingsBoard 的企业级物联网平台：设备接入 → 物模型 → 时序数据存储 → 规则告警 → 可视化看板 → OTA 升级。支持高并发接入、共享订阅水平扩展、设备影子、多租户。

## 总体架构

```
┌─────────────┐   设备模拟器 ×N / ESP32 真机(可选)
│   设备接入层  │──────────────────────────────┐
└─────────────┘   MQTT over TLS :8883         ▼
                                     ┌──────────────────┐
┌─────────────┐    REST API         │  EMQX 5.x 集群    │←─ HTTP 认证/ACL 回调
│  Vue 3 前端  │◄───Spring Cloud────│  (MQTT Broker)    │   ↓ 规则引擎
│  管理后台+看板│    Gateway         └────────┬─────────┘   数据桥接
└──────┬──────┘                              │
       │ MQTT over WebSocket (mqtt.js)       ▼
       └────────────────────────────►   Kafka（削峰/解耦）
                                        │
                    ┌───────────────────┼──────────────────┐
                    ▼                   ▼                  ▼
             数据接入服务          规则/告警服务        设备状态服务
          （Kafka 消费者,        （阈值/场景联动）   （上下线事件处理）
           批量写 TDengine）          │                  │
                    │                ▼                  │
                    ▼            通知服务(邮件/钉钉)      │
        ┌──────────────────────┐            │           │
        │ TDengine │PostgreSQL │◄───────────┴───────────┘
        │ (时序数据)│(业务数据)  │  Redis（在线状态/缓存/分布式锁）
        └──────────────────────┘
```

## 技术栈

| 层 | 选型 |
|---|---|
| MQTT Broker | EMQX 5.x（集群 / 规则引擎 / 数据桥接 / HTTP 认证钩子） |
| 后端 | Java 21 + Spring Boot 3.x（虚拟线程），Spring Cloud Alibaba（Nacos / Gateway / Sentinel） |
| MQTT 客户端 | Eclipse Paho（Java 后端）/ paho-mqtt（Python 模拟器） |
| ORM | MyBatis-Plus（分页插件 + 防全表更新） |
| 消息队列 | Kafka |
| 时序数据库 | TDengine 3.x（超级表=产品，子表=设备） |
| 业务库 | PostgreSQL（物模型 JSONB） |
| 缓存 | Redis |
| 前端 | Vue 3 + TypeScript + Element Plus + ECharts + mqtt.js（MQTT over WebSocket） |
| 设备模拟器 | Python + paho-mqtt（万级设备并发模拟） |
| 压测 | emqtt-bench |
| 部署 | Docker Compose（开发）→ Kubernetes + Helm（生产） |
| 监控 | Prometheus + Grafana + Alertmanager |

## 快速开始（M1 骨架）

前置要求：Docker、JDK 21、Maven、Python 3.10+。

```bash
# 首次使用：准备环境变量（.env 已 gitignore，按需修改密码/密钥）
cp backend/.env.example backend/.env
cp deploy/.env.example deploy/.env

# 0. 生成本地开发证书（自签 CA + 服务端证书，仅首次执行）
cd deploy/certs && ./gen-certs.sh && cd ../..

# 1. 启动基础设施（EMQX + PostgreSQL + Redis + TDengine + Prometheus + Grafana）
#    所有端口仅绑定 127.0.0.1；Redis/TDengine 口令来自 deploy/.env，
#    tdengine-init 任务会在首次启动时把 root 默认口令改为 COHERER_TDENGINE_PASSWORD
cd deploy && docker compose up -d

# 2. 启动后端
cd ../backend && mvn spring-boot:run
# 健康检查: http://localhost:8081/actuator/health（匿名可访问）
# 指标端点: http://localhost:8081/actuator/prometheus（需 X-Internal-Token）

# 3. 注册演示设备并生成凭证（EMQX 已开启签名认证，匿名连接会被拒绝）
cd ../simulator
pip install -r requirements.txt
python register_devices.py --count 5

# 4. 启动设备模拟器（TLS + HMAC 签名认证）
python simulator.py --credentials creds.json --tls
#    验证 OTA 失败分支：追加 --ota-fail，设备会下载并校验固件后回报安装失败

# 4.1 OTA：打开前端「固件升级」，选择产品上传固件并下发到同产品设备
#     设备经 10 分钟有效的签名 URL 下载，校验 SHA256 后安装并回报进度
#     断点续传演练：python simulator.py ... --ota-drop-once
#     下载第 2 个 256KB 分片时强制断网，模拟器会从当前 offset 继续 Range 请求

# 4.2 可选：S3/MinIO 固件对象存储
#     deploy/.env 配置 MINIO_ROOT_USER / MINIO_ROOT_PASSWORD 后：
#     cd deploy && docker compose -f docker-compose.yml -f docker-compose.s3.yml \
#       --profile s3 up -d minio
#     backend/.env 设置 COHERER_OTA_STORAGE_TYPE=s3 及
#       COHERER_OTA_S3_ENDPOINT=http://minio:9000 / ACCESS_KEY / SECRET_KEY / BUCKET
#     切回 local 后，历史 S3 固件仍按 firmware.storage_type 路由读取

# 5. 查看数据
#    遥测查询: http://localhost:8081/api/devices/<deviceId>/telemetry?limit=5
#    EMQX 控制台: http://localhost:18083 (admin / EMQX_DASHBOARD_PASSWORD)
#    Prometheus: http://localhost:9090 （抓取 /actuator/prometheus）
#    Grafana: http://localhost:3000 (admin / GRAFANA_ADMIN_PASSWORD)
#    Alertmanager: http://localhost:9093
#    Zipkin: http://localhost:9411 （按 traceId 查调用链）
#
# 可选：HTTPS Profile（本地自签 CA）
#    cd backend && mvn spring-boot:run -Dspring-boot.run.profiles=https
#    curl --ssl-no-revoke --cacert ../deploy/certs/ca.crt https://localhost:9443/api/ping

# 可选：整栈容器化（后端 + 前端 + Nginx 同源）
# 先打包 jar：cd backend && mvn -DskipTests package
# 再启动：cd ../deploy &&
#   docker compose -f docker-compose.yml -f docker-compose.app.yml --profile app up -d --build
# 打开 https://localhost:8443 （HTTP 8080 会 301 跳转；自签证书需信任 deploy/certs/ca.crt）

# 6. 前端看板（另开终端）
cd frontend
npm install
npm run dev
# 打开 http://localhost:5180 ，使用 admin / Admin@123 登录
# （初始口令来自 backend/.env 的 COHERER_ADMIN_PASSWORD）
```

> ⚠️ EMQX 已开启 **HTTP 签名认证 + 主题级 ACL**：设备只能访问自身 tenant/deviceId 主题；浏览器实时订阅使用后端签发的短时凭证；Dashboard 的 WebSocket 测试客户端因不具备设备签名会被拒绝。

## 可观测性

| 能力 | 入口 / 说明 |
|---|---|
| 指标 | `GET /actuator/prometheus`（需 `X-Internal-Token`，Prometheus 已自动带入）；抓取目标见 http://localhost:9090/targets |
| 看板 | Grafana 自动装配「Coherer IoT 平台总览」（JVM、HTTP QPS/P95、MQTT、遥测、告警、登录失败） |
| 告警规则 | Prometheus 内置 9 条规则（后端不可达、MQTT 断连、遥测拒绝率/静默、告警风暴、登录爆破、5xx、P95、堆内存）；critical 走独立路由，含 2 条抑制规则 |
| 业务指标 | `coherer_telemetry_ingested_total{result}`、`coherer_alarm_triggered_total{level}`、`coherer_auth_login_failures_total`、`coherer_mqtt_connected`、`coherer_telemetry_buffer_size` |
| 结构化日志 | ECS JSON 输出到控制台；每条日志带 `service.name`、`process.pid`、`traceId`、`spanId` |
| 链路追踪 | MQTT 消息入口创建 `mqtt.message` span，traceId 贯穿校验、入库与告警；span 上报 Zipkin http://localhost:9411 |
| 审计日志 | 登录、设备/产品/物模型、告警、规则、空间、用户与命令等关键操作；ADMIN 可在「审计日志」页面或 `GET /api/audit-logs` 按用户/动作/结果查询，含 traceId、User-Agent、真实客户端 IP |
| 探针 | `/actuator/health/liveness`（进程存活）、`/actuator/health/readiness`（db/redis/disk） |
| HTTPS/WSS | `https://localhost:8443`（HTTP 8080 跳转）；Nginx 443 终结 TLS，`/mqtt` 反代为 WSS |
| PostgreSQL TLS | 容器内 `sslmode=verify-full`，CA 为 `deploy/certs/ca.crt` |

```bash
# 查看审计日志（先登录拿 token）
curl -H "Authorization: Bearer <accessToken>" \
  "http://localhost:8081/api/audit-logs?current=1&size=20&action=LOGIN_SUCCESS"

# 只看失败操作
curl -H "Authorization: Bearer <accessToken>" \
  "http://localhost:8081/api/audit-logs?result=FAILURE"
```

## 测试与接口文档

```bash
# 单元测试（272 个）
cd backend && mvn test

# 一键单元 + 集成测试 + 覆盖率门禁
# 自动启动 deploy/docker-compose.test.yml（真实 PG16/Redis7，独立端口 55432/56379）
./deploy/scripts/integration-test.sh

# OTA 端到端：建产品/设备 → 上传固件 → 启动模拟器 → 等待 SUCCESS
# 前置：compose app 栈已启动
./deploy/scripts/e2e-ota.sh

# OpenAPI / Swagger UI（后端仅绑定 127.0.0.1:8081，不经 Nginx 暴露）
open http://localhost:8081/swagger-ui.html
```

CI 在 `mvn clean verify` 中执行：单元测试 + 5 个真实 PG/Redis 集成测试 + JaCoCo
行/分支覆盖率门禁（当前基线 60%/45%，实测 62.6%/49.7%，后续按 ratchet 逐步提高到 80%/70%）。

## TLS 与备份恢复

```bash
# 证书（首次/缺失时生成）：CA、EMQX server、PostgreSQL server
cd deploy/certs && ./gen-certs.sh

# 信任本地 CA 后访问 HTTPS/WSS（Chrome/Firefox 需导入 ca.crt，或用 curl）
curl --ssl-no-revoke --cacert deploy/certs/ca.crt https://localhost:8443/api/ping

# 备份：PostgreSQL + Redis + EMQX + TDengine + OTA 固件，保留 7 天
cd deploy && ./scripts/backup.sh
# 恢复（会覆盖数据，必须显式确认）
FORCE=1 ./scripts/restore.sh backups/<timestamp>
```

> Redis/TDengine 官方社区镜像不提供可用的 TLS 监听；当前通过 127.0.0.1 绑定 + 容器网络隔离，
> 生产建议使用云厂商托管 TLS 实例或 stunnel/服务网格。PG、EMQX、REST/WSS 已完成 TLS。

## 目录结构

```
coherer/
├── backend/     # Spring Boot 后端（M1 单体，M3 拆微服务）
├── frontend/    # Vue 3 管理后台 + 实时看板（Vite :5180）
├── simulator/   # Python 设备模拟器
├── deploy/      # Docker Compose / Helm / 本地开发证书
└── docs/        # 架构与设计文档
```

## 路线图

| 阶段 | 内容 | 状态 |
|---|---|---|
| M1 | 垂直跑通：设备注册 → 模拟器上报 → 认证 → 入库 | ✅ 已完成 |
| M2 | 物模型、设备影子、在线状态（TTL 兜底）、TDengine、前端看板、告警 v1、mqtt.js 实时推送、设备 ACL、空间树、设备启停/删除/密钥轮换、用户管理、命令下发、事件上报、边缘主机/网关（命令转发 + 子设备数据代理） | ✅ 已完成 |
| M3 | 微服务化（Nacos+Gateway）、Kafka 削峰、时间水位 | 🟡 批量写入/幂等/共享订阅/命令下发/事件上报已完成，Kafka 与拆服务待做 |
| M4 | OTA、场景联动、通知渠道、mTLS 加固、多租户 | 🟡 **OTA 全链路已完成**（设备/空间/全量目标、分批、暂停/恢复/取消、失败率熔断、退避重试、from_version 回退、本地/S3 存储、签名下载、Range 断点续传、进度/超时）；场景联动、通知渠道、mTLS、多租户待做 |
| M5 | K8s+Helm、HA、10 万连接压测报告 | 🟡 Prometheus/Grafana/Alertmanager/Zipkin 已完成，HA 与压测待做 |

## 文档

| 文档 | 内容 |
|---|---|
| [docs/requirements.md](docs/requirements.md) | 需求规格：角色权限、功能需求（FR 编号）、用户故事与验收标准、非功能指标 |
| [docs/design.md](docs/design.md) | 设计图：架构图、时序图、状态机、ER 图、Redis 键设计、接口设计、部署图 |
| [docs/architecture.md](docs/architecture.md) | 技术方案：选型理由、MQTT 协议设计、里程碑、简历卖点与面试预演 |
| [docs/enterprise-gap-analysis.md](docs/enterprise-gap-analysis.md) | 企业级差距分析：安全/可靠性/测试/运维评分、P0 阻断项、整改路线图与验收清单 |
| [docs/pitfalls.md](docs/pitfalls.md) | 踩坑记录：构建、MyBatis-Plus、MQTT、TDengine、认证、可观测性、命令下发与 OTA |
