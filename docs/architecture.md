# Coherer 架构与技术方案

> 项目定位：对标阿里云 IoT 平台 / ThingsBoard 的自研企业级物联网设备管理平台。
> 设备接入 → 物模型 → 时序数据存储 → 规则告警 → 可视化看板 → OTA 升级。

## 1. 总体架构

```
┌─────────────┐   模拟器 ×N (Python) / ESP32 (可选)
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

其余服务：认证服务（用户 JWT + 设备认证）、设备管理服务（产品/物模型/设备/影子）、OTA 服务、监控（Prometheus + Grafana）。

## 2. 技术选型

| 层 | 选型 | 理由 |
|---|---|---|
| MQTT Broker | EMQX 5.x 开源版（Docker） | 国产、中文文档全、集群/规则引擎/数据桥接/HTTP 认证钩子/WebSocket，国内 IoT 事实标准 |
| 后端 | Java 21 + Spring Boot 3.x | 岗位匹配度最高；虚拟线程处理高并发连接 |
| 微服务 | Spring Cloud Alibaba（Nacos / Gateway / Sentinel） | 国内企业标准 |
| ORM | MyBatis-Plus | 国内主流 |
| 消息队列 | Kafka（EMQX 规则引擎直接桥接） | 削峰、解耦、多消费者 |
| 时序数据库 | TDengine 3.x | 超级表=产品、子表=设备，天然映射物模型；IoT 场景常用 |
| 业务库 | PostgreSQL（物模型 JSONB） | 设备/产品/用户/OTA 等业务数据 |
| 缓存 | Redis | 在线状态、认证缓存、分布式锁 |
| 前端 | Vue 3 + TS + Vite + Pinia + Element Plus + ECharts + mqtt.js | 实时曲线走 MQTT over WebSocket 直连 EMQX |
| 设备模拟器 | Python + paho-mqtt | 万级设备并发模拟 |
| 压测 | emqtt-bench | 10 万级连接压测 |
| 部署 | Docker Compose（开发）→ K8s + Helm（生产） | |
| 监控 | Prometheus + Grafana | EMQX/JVM/中间件均有现成 exporter |
| CI/CD | GitHub Actions | |

## 3. MQTT 协议设计

### 3.1 主题规划

```
{tenantId}/{deviceId}/thing/property/report     # 属性上报（QoS1）
{tenantId}/{deviceId}/thing/event/{id}          # 事件上报（QoS1）
{tenantId}/{deviceId}/thing/service/{id}        # 服务调用下发（QoS1）
{tenantId}/{deviceId}/thing/service/{id}/reply  # 设备回复
{tenantId}/{deviceId}/shadow/update             # 影子更新（保留消息）
{tenantId}/{deviceId}/ota/progress              # 升级进度
```

### 3.2 关键协议细节

1. **QoS 策略**：遥测 QoS1，消费端按 messageId 幂等去重；不滥用 QoS2。
2. **遗嘱消息（LWT）**：设备异常断连由 Broker 代发下线消息；配合 EMQX 上下线事件 Webhook 更新 Redis 在线状态并推送前端。
3. **共享订阅**：`$share/group/topic`，消费端多实例水平扩展、负载均衡。
4. **设备认证**：clientId=deviceId，password=HMAC-SHA256(设备密钥, username+timestamp)；EMQX HTTP 认证器回调认证服务（结果缓存 Redis）。对标阿里云 IoT 签名认证。
5. **ACL**：EMQX HTTP ACL 回调，设备只能收发自己主题子树。
6. **TLS :8883**（服务端证书起步，进阶双向 mTLS）。
7. **Payload**：JSON 起步（按物模型校验），预留 Protobuf 演进方向。
8. **MQTT 5.0 特性**：命令响应用 Response Topic / Correlation Data（3.1.1 设备回退 replyTopic 字段）、用户属性带 trace-id 做全链路追踪、消息过期时间、会话过期间隔。

## 4. 领域功能

- **物模型（Thing Model）**：产品下定义属性/事件/服务（JSONB 存储），上报数据按物模型校验后入库。
- **设备影子**：desired/reported + 版本号 + 乐观锁，解决设备离线时下发命令的问题（参考 AWS IoT Shadow）。
- **规则引擎/场景联动**：阈值告警 + 跨设备联动（如"温度>80℃ 则下发停机指令"），表达式引擎用 Aviator。
- **OTA 升级**：固件管理、批量灰度升级、进度跟踪、失败回滚。
- **告警中心**：级别/记录/恢复、邮件/钉钉通知。
- **多租户**：主题隔离 + 数据逻辑隔离。

## 5. 里程碑

| 阶段 | 时间 | 交付 |
|---|---|---|
| M1 垂直跑通 | 第 1~4 周 | Docker Compose 起 EMQX/PG/Redis；Spring Boot 单体：设备注册→模拟器上报→数据入库（PG 简表）→简版看板 |
| M2 领域核心 | 第 5~10 周 | 签名认证 + ACL、物模型、设备影子、LWT+Webhook 在线状态、TDengine、mqtt.js 实时曲线、告警规则 v1、前端后台成形 |
| M3 高并发改造 | 第 11~16 周 | 微服务拆分（Nacos+Gateway）、Kafka 削峰、批量写 TDengine、共享订阅多实例、Sentinel 限流、命令下发 |
| M4 企业特性 | 第 17~21 周 | OTA、场景联动、TLS/mTLS 加固、审计日志 |
| M5 生产化 | 第 22~26 周 | K8s+Helm、Prometheus+Grafana、emqtt-bench 压测（10 万连接）+ 压测报告、文档、演示视频、（可选）ESP32 实物 |

## 6. 简历卖点与面试预演

- 硬指标（须真实压测得出）：单节点 X 万 MQTT 并发连接 / X 万条每秒吞吐 / 端到端延迟 P99，配 Grafana 截图。
- 面试必问，提前准备：
  - QoS1 重复/乱序消息如何处理（消费端幂等去重）
  - 设备在线状态如何判定（LWT + Webhook + Redis）
  - 命令下发的可靠性保证（QoS1 + 去重 + reply + 超时重试 + 状态机）
  - 为什么加 Kafka 而不是直接消费（削峰、解耦、多消费组复用）
  - 为什么用时序数据库、TDengine 超级表建模
  - 共享订阅原理与负载均衡
  - 设备认证与 ACL 全流程
  - 设备影子设计（desired/reported/版本号/乐观锁）

参考资料：ThingsBoard 源码（Java 开源 IoT 平台，影子/规则引擎设计值得研究）、AWS IoT Shadow 规范、EMQX 官方文档。
