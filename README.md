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
| 监控 | Prometheus + Grafana |

## 快速开始（M1 骨架）

前置要求：Docker、JDK 21、Maven、Python 3.10+。

```bash
# 0. 生成本地开发证书（自签 CA + 服务端证书，仅首次执行）
cd deploy/certs && ./gen-certs.sh && cd ../..

# 1. 启动基础设施（EMQX + PostgreSQL + Redis）
cd deploy && docker compose up -d

# 2. 启动后端
cd ../backend && mvn spring-boot:run
# 健康检查: http://localhost:8081/actuator/health

# 3. 注册演示设备并生成凭证（EMQX 已开启签名认证，匿名连接会被拒绝）
cd ../simulator
pip install -r requirements.txt
python register_devices.py --count 5

# 4. 启动设备模拟器（TLS + HMAC 签名认证）
python simulator.py --credentials creds.json --tls

# 5. 查看数据
#    遥测查询: http://localhost:8081/api/devices/<deviceId>/telemetry?limit=5
#    EMQX 控制台: http://localhost:18083 (admin / public) 查看连接与流量

# 6. 前端看板（另开终端）
cd frontend
npm install
npm run dev
# 打开 http://localhost:5180 （Vite 已代理 /api 到 8081）
```

> ⚠️ EMQX 已开启 **HTTP 签名认证 + 主题级 ACL**：设备只能访问自身 tenant/deviceId 主题；浏览器实时订阅使用后端签发的短时凭证；Dashboard 的 WebSocket 测试客户端因不具备设备签名会被拒绝。

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
| M2 | 物模型、设备影子、LWT 在线状态、TDengine、前端看板、告警 v1、mqtt.js 实时推送、设备 ACL | ✅ 已完成 |
| M3 | 微服务化（Nacos+Gateway）、Kafka 削峰、批量写入、共享订阅 | ⬜ |
| M4 | OTA、场景联动、mTLS 加固、多租户 | ⬜ |
| M5 | K8s+Helm、Prometheus+Grafana、10 万连接压测报告 | ⬜ |

## 文档

| 文档 | 内容 |
|---|---|
| [docs/requirements.md](docs/requirements.md) | 需求规格：角色权限、功能需求（FR 编号）、用户故事与验收标准、非功能指标 |
| [docs/design.md](docs/design.md) | 设计图：架构图、时序图、状态机、ER 图、Redis 键设计、接口设计、部署图 |
| [docs/architecture.md](docs/architecture.md) | 技术方案：选型理由、MQTT 协议设计、里程碑、简历卖点与面试预演 |
