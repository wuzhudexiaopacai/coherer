# Coherer 设计文档（设计图）

> 本文所有图使用 **Mermaid** 绘制：GitHub 原生渲染；VS Code 安装 *Markdown Preview Mermaid Support* 插件即可预览。
> 关联文档：[requirements.md](requirements.md)（需求规格） · [architecture.md](architecture.md)（技术选型与协议设计）

## 1. 总体架构图

```mermaid
flowchart TB
    subgraph DEV["设备层"]
        SIM["Python 设备模拟器 ×N"]
        ESP["ESP32 真机（可选）"]
    end

    subgraph ACCESS["接入层"]
        EMQX["EMQX 5.x 集群<br/>MQTT :1883 / TLS :8883<br/>WebSocket :8083"]
    end

    subgraph MSG["消息层"]
        KAFKA["Kafka<br/>遥测主题 / 事件主题"]
    end

    subgraph SVC["服务层（Spring Cloud）"]
        GW["API 网关"]
        AUTH["认证服务"]
        DEVSVC["设备管理服务"]
        INGEST["数据接入服务"]
        RULE["规则告警服务"]
        OTASVC["OTA 服务"]
        NOTIFY["通知服务"]
    end

    subgraph STORE["存储层"]
        PG[("PostgreSQL<br/>业务数据")]
        TD[("TDengine<br/>时序数据")]
        REDIS[("Redis<br/>缓存 / 在线状态")]
    end

    subgraph UI["展示层"]
        WEB["Vue 3 管理后台 + 实时看板"]
    end

    subgraph OBS["可观测"]
        PROM["Prometheus + Grafana"]
    end

    SIM -->|"MQTT QoS1"| EMQX
    ESP -->|"MQTT QoS1"| EMQX
    EMQX -->|"HTTP 认证 / ACL 回调"| AUTH
    EMQX -->|"规则引擎桥接"| KAFKA
    EMQX -->|"status 主题（共享订阅）"| DEVSVC

    KAFKA --> INGEST
    INGEST -->|"批量写入"| TD
    INGEST -->|"最新值 / 在线状态"| REDIS
    INGEST -->|"异常数据"| KAFKA

    RULE -->|"告警记录"| PG
    RULE --> NOTIFY

    GW --> AUTH
    GW --> DEVSVC
    GW --> RULE
    GW --> OTASVC

    WEB -->|"REST / JWT"| GW
    WEB -->|"MQTT over WebSocket 实时订阅"| EMQX

    AUTH --> REDIS
    AUTH --> PG
    DEVSVC --> PG
    DEVSVC --> REDIS
    DEVSVC -->|"命令下发"| EMQX
    OTASVC --> PG
    OTASVC -->|"OTA 任务下发"| EMQX

    PROM -.-> EMQX
    PROM -.-> GW
    PROM -.-> PG
```

## 2. 功能模块划分

```mermaid
flowchart LR
    P["Coherer 平台"] --> DD["设备域"]
    P --> TDOM["数据域"]
    P --> OD["运营域"]
    P --> SD["系统域"]

    DD --> D1["产品与物模型"]
    DD --> D2["设备注册 / 生命周期"]
    DD --> D3["设备影子"]
    DD --> D4["接入认证 / ACL"]

    TDOM --> T1["遥测采集 / 物模型校验"]
    TDOM --> T2["时序存储 / 聚合查询"]
    TDOM --> T3["实时推送"]
    TDOM --> T4["命令下发 / 状态追踪"]

    OD --> O1["规则与告警"]
    OD --> O2["通知渠道"]
    OD --> O3["OTA 升级"]
    OD --> O4["看板 / 大屏"]

    SD --> S1["租户"]
    SD --> S2["用户 / 角色 / 权限"]
    SD --> S3["审计日志"]
    SD --> S4["监控 / 健康检查"]
```

## 3. 核心流程时序图

### 3.1 设备注册（一机一密）

```mermaid
sequenceDiagram
    autonumber
    participant U as 开发者
    participant GW as API 网关
    participant DS as 设备管理服务
    participant DB as PostgreSQL

    U->>GW: POST /api/devices 设备信息 + 数量
    GW->>DS: 转发请求（JWT 鉴权）
    DS->>DB: 校验产品存在、读取鉴权方式
    DS->>DS: 生成 deviceId + 随机密钥
    DS->>DB: INSERT device（仅存密钥密文）
    DS-->>GW: 返回 deviceId + 明文密钥
    GW-->>U: 密钥仅本次可见，可导出
    Note over U,DS: 密钥丢失只能重置轮换
```

### 3.2 设备连接认证

```mermaid
sequenceDiagram
    autonumber
    participant D as 设备（模拟器）
    participant EMQX as EMQX
    participant AUTH as 认证服务
    participant R as Redis
    participant DB as PostgreSQL

    D->>EMQX: CONNECT clientId=deviceId<br/>username=毫秒时间戳<br/>password=hex(HMAC-SHA256(secret, clientId|username))
    EMQX->>AUTH: HTTP POST /internal/auth/mqtt
    AUTH->>R: 查询认证缓存

    alt 缓存命中
        R-->>AUTH: secretCipher + 设备状态
    else 缓存未命中
        AUTH->>DB: 查询设备
        AUTH->>R: 写入缓存 TTL 5 分钟
    end

    AUTH->>AUTH: 校验签名、时间戳（±5 分钟）、设备状态

    alt 校验通过
        AUTH-->>EMQX: 200 {"result":"allow"}
        EMQX-->>D: CONNACK 成功
        D->>EMQX: PUBLISH {tenant}/{deviceId}/status "online"（下线由 LWT 代发）
        Note over EMQX,R: 平台网关消费 status 主题<br/>写入 DB + Redis
    else 校验失败
        AUTH-->>EMQX: 200 {"result":"deny"}
        EMQX-->>D: CONNACK Not authorized
    end
```

### 3.3 遥测数据链路

```mermaid
sequenceDiagram
    autonumber
    participant D as 设备
    participant EMQX as EMQX
    participant K as Kafka
    participant ING as 数据接入服务
    participant TD as TDengine
    participant R as Redis
    participant WEB as 浏览器看板

    D->>EMQX: PUBLISH {tenant}/{deviceId}/thing/property/report (QoS1)
    EMQX-->>D: PUBACK
    EMQX->>K: 规则引擎桥接（保留设备元数据）

    par 实时推送
        EMQX-->>WEB: mqtt.js 直接订阅同一主题
    and 异步入库
        K->>ING: 批量消费（500 条 / 500ms 触发）
        ING->>ING: 物模型校验 + 幂等去重
        ING->>TD: 批量写入设备子表
        ING->>R: 更新最新值快照
        ING->>K: 非法数据写入死信主题
    end
```

> M2 实现说明：后端通过 Paho 共享订阅直接消费 EMQX 遥测（暂无 Kafka）；Kafka 桥接在 M3 引入，上下游链路不变。

### 3.4 命令下发

```mermaid
sequenceDiagram
    autonumber
    participant U as 用户
    participant DS as 设备管理服务
    participant DB as PostgreSQL
    participant EMQX as EMQX
    participant D as 设备

    U->>DS: POST /api/devices/{id}/commands
    DS->>DB: INSERT command (PENDING)
    DS->>EMQX: PUBLISH {tenant}/{deviceId}/thing/service/{svc} (QoS1)<br/>MQTT5 属性 Response Topic + Correlation Data<br/>3.1.1 兼容 消息体 replyTopic 字段
    EMQX->>D: 投递命令

    alt 设备在超时内回复
        D->>EMQX: PUBLISH Response Topic {code, data}<br/>带回 Correlation Data = 命令 messageId
        EMQX->>DS: 共享订阅组 cmd-reply 收到回复
        DS->>DB: UPDATE command (SUCCESS)
        DS-->>U: WebSocket 推送执行结果
    else 超时（默认 10s，最多重试 2 次）
        DS->>EMQX: 重发（同一 messageId，设备侧幂等）
        Note over DS,D: 重试耗尽 → FAILED 并记录告警
    end
```

### 3.5 告警处理

```mermaid
sequenceDiagram
    autonumber
    participant K as Kafka 遥测流
    participant RS as 规则告警服务
    participant DB as PostgreSQL
    participant N as 通知服务
    participant U as 运维

    K->>RS: 遥测消息
    RS->>RS: 匹配规则（属性超阈值且持续 N 秒）
    RS->>DB: INSERT alarm_record (TRIGGERED)
    RS->>N: 触发通知
    N->>U: 邮件 / 钉钉 / Webhook

    U->>RS: 确认告警
    RS->>DB: 状态改为 ACKNOWLEDGED

    K->>RS: 遥测恢复正常
    RS->>DB: 状态改为 RECOVERED
```

### 3.6 OTA 升级

```mermaid
sequenceDiagram
    autonumber
    participant A as 管理员
    participant OS as OTA 服务
    participant DB as PostgreSQL
    participant EMQX as EMQX
    participant D as 设备

    A->>OS: 上传固件（服务端计算 SHA-256）
    OS->>DB: 保存 firmware 元数据（产品+版本唯一）
    A->>OS: 对单台设备创建 OTA 任务
    OS->>DB: ota_task (PENDING，同一设备仅一个进行中任务)
    OS->>OS: 生成 10 分钟 HMAC 签名下载 URL
    OS->>EMQX: 下发版本 + URL + SHA256 + size
    EMQX->>D: {tenant}/{deviceId}/ota/upgrade
    Note over OS,DB: 任务先提交再发 MQTT，避免设备进度早于任务可见

    D->>OS: HTTPS GET /api/ota/download/{id}?deviceId&expires&signature
    OS-->>D: 固件二进制（校验签名、设备与产品匹配、设备未禁用）
    D->>D: 下载 → SHA256 校验 → 安装 → 重启
    D->>EMQX: 上报进度 {tenant}/{deviceId}/ota/progress
    EMQX->>OS: 共享订阅 + 主机路径 {tenant}/{host}/sub/{device}/ota/progress
    OS->>DB: 更新 progress / status / finished_at
```

> 下载支持 HTTP Range：设备按 256KB 分片请求，服务端返回 206 + `Content-Range`；本地文件用
> FileChannel offset 读取，S3 用 GetObject offset/length，网络中断后从当前 offset 续传。
> 主机下的设备由平台把指令发到 `{tenant}/{hostId}/sub/{deviceId}/ota/upgrade`，主机负责转发并代传进度。

## 4. 状态机

### 4.1 设备生命周期

```mermaid
stateDiagram-v2
    state "未激活" as Inactive
    state "在线" as Online
    state "离线" as Offline
    state "禁用" as Disabled

    [*] --> Inactive: 注册
    Inactive --> Online: 首次认证连接成功
    Online --> Offline: 断连 / LWT 超时
    Offline --> Online: 重新连接
    Online --> Disabled: 管理员禁用
    Offline --> Disabled: 管理员禁用
    Inactive --> Disabled: 管理员禁用
    Disabled --> Offline: 启用（等待连接）
    Offline --> [*]: 删除
    Disabled --> [*]: 删除
```

### 4.2 命令状态

```mermaid
stateDiagram-v2
    state "待发送" as Pending
    state "已发送" as Sent
    state "成功" as Success
    state "失败" as Failed
    state "超时" as Timeout

    [*] --> Pending: 创建
    Pending --> Sent: MQTT 下发成功
    Sent --> Success: 收到 reply（code=0）
    Sent --> Failed: 收到 reply（code≠0）
    Sent --> Timeout: 超时未回复
    Timeout --> Sent: 重试（≤2 次）
    Timeout --> Failed: 重试耗尽
    Success --> [*]
    Failed --> [*]
```

### 4.3 OTA 任务状态

```mermaid
stateDiagram-v2
    state "待下发" as Pending
    state "已下发" as Sent
    state "下载中" as Downloading
    state "成功" as Success
    state "失败" as Failed
    state "超时" as Timeout

    [*] --> Pending: 创建任务
    Pending --> Sent: MQTT 下发成功
    Pending --> Failed: 主机不可用 / MQTT 未连接
    Pending --> Timeout: 超时仍未下发
    Sent --> Downloading: 设备开始下载
    Sent --> Success: 设备安装成功
    Sent --> Failed: 设备回报失败
    Downloading --> Success: progress=100 / SUCCESS
    Downloading --> Failed: SHA256 不符 / 安装失败
    Sent --> Timeout: 超时无进度且重试耗尽
    Downloading --> Timeout: 进度长时间未更新且重试耗尽
    Sent --> Pending: 失败/超时且 attempt < maxAttempts
    Downloading --> Pending: 失败/超时且 attempt < maxAttempts
    Pending --> Sent: 退避到期重新下发（attempt+1）
    Success --> [*]
    Failed --> [*]
    Timeout --> [*]
```

> 重试期间成员在灰度批次中保持 RUNNING；只有 attempt 耗尽进入 FAILED/TIMEOUT 才计入失败率。
> 设备进度携带 `attempt`，旧 attempt 的 QoS1 重复回执会被丢弃，避免重复推进重试次数。

### 4.3.1 OTA 灰度批次状态

```mermaid
stateDiagram-v2
    state "待启动" as Pending
    state "执行中" as Running
    state "已暂停" as Paused
    state "成功" as Success
    state "部分失败" as Partial
    state "失败" as Failed
    state "已取消" as Canceled

    [*] --> Pending: 创建批次快照
    Pending --> Running: 启动第 1 批
    Running --> Running: 当前批完成且未触发熔断
    Running --> Paused: 失败率达到阈值
    Running --> Paused: 人工暂停 / 非自动推进
    Paused --> Running: 人工恢复
    Running --> Success: 全部设备成功
    Running --> Partial: 存在失败且存在成功
    Running --> Failed: 全部失败
    Pending --> Canceled: 取消
    Running --> Canceled: 取消（已下发任务继续）
    Paused --> Canceled: 取消
    Success --> [*]
    Partial --> [*]
    Failed --> [*]
    Canceled --> [*]
```

### 4.4 告警状态

```mermaid
stateDiagram-v2
    state "触发" as Triggered
    state "已确认" as Acked
    state "已恢复" as Recovered
    state "已关闭" as Closed

    [*] --> Triggered
    Triggered --> Acked: 人工确认
    Triggered --> Recovered: 条件恢复
    Acked --> Recovered: 条件恢复
    Triggered --> Closed: 人工关闭
    Acked --> Closed: 人工关闭
    Recovered --> [*]
    Closed --> [*]
```

## 5. 数据模型

### 5.1 业务库 ER 图（PostgreSQL）

> 物模型与配置类字段用 `jsonb` 存储；`*_hash` 字段只存哈希不存明文；时序数据不入 PG（M1 阶段使用临时表）。

```mermaid
erDiagram
    TENANT ||--o{ SYS_USER : "拥有"
    TENANT ||--o{ PROJECT : "拥有"
    PROJECT ||--o{ SPACE : "包含"
    TENANT ||--o{ PRODUCT : "拥有"
    SYS_USER }o--o{ SYS_ROLE : "分配"
    PRODUCT ||--o{ THING_MODEL : "定义"
    PRODUCT ||--o{ DEVICE : "包含"
    PRODUCT ||--o{ FIRMWARE : "发布"
    DEVICE ||--|| DEVICE_SHADOW : "拥有"
    DEVICE ||--o{ COMMAND : "接收"
    DEVICE ||--o{ ALARM_RECORD : "关联"
    ALARM_RULE ||--o{ ALARM_RECORD : "产生"
    FIRMWARE ||--o{ OTA_TASK : "关联"
    DEVICE ||--o{ OTA_TASK : "执行"
    FIRMWARE ||--o{ OTA_ROLLOUT : "灰度发布"
    OTA_ROLLOUT ||--o{ OTA_ROLLOUT_DEVICE : "包含"
    OTA_ROLLOUT ||--o{ OTA_TASK : "分批下发"

    TENANT {
        varchar id PK "租户代码，同时作为业务表 tenant_id"
        varchar name "名称"
        varchar status "ACTIVE/DISABLED"
        varchar plan "STANDARD/PRO/ENTERPRISE/TRIAL"
        varchar description
        bigint default_project_id "开通时创建的默认项目"
        bigint default_space_id "开通时创建的默认空间"
        timestamp created_at
    }
    PROJECT {
        bigint id PK
        varchar tenant_id FK
        varchar code "项目编码，租户内唯一"
        varchar name "项目名"
        varchar status "ACTIVE/DISABLED"
        varchar description
    }
    SPACE {
        bigint id PK
        bigint tenant_id FK
        bigint project_id FK "所属项目"
        bigint parent_id "父空间"
        varchar name "名称"
        varchar code "编码"
        varchar type "AREA/BUILDING/FLOOR/ROOM/GENERIC"
        int sort "排序"
        varchar status "状态"
        smalldatetime deleted "逻辑删除"
    }
    SYS_USER {
        bigint id PK
        varchar tenant_id FK
        varchar username "登录名，租户内唯一"
        varchar password_hash "bcrypt 哈希"
        varchar nickname "昵称"
        smallint platform_admin "1=平台管理员（仅 default 租户 admin）"
        varchar status "状态"
    }
    SYS_ROLE {
        bigint id PK
        bigint tenant_id FK
        varchar code "角色编码"
        varchar name "角色名"
    }
    PRODUCT {
        bigint id PK
        varchar tenant_id FK
        varchar product_key "产品标识，租户内唯一"
        varchar name "产品名"
        varchar category "品类"
        varchar node_type "直连/网关"
        varchar auth_type "一机一密"
        varchar status "状态"
    }
    THING_MODEL {
        bigint id PK
        bigint product_id FK
        varchar version "版本"
        varchar type "PROPERTY/EVENT/SERVICE"
        varchar identifier "标识符"
        varchar name "名称"
        varchar data_type "数据类型"
        varchar access_mode "读写"
        jsonb spec "定义细节"
    }
    DEVICE {
        bigint id PK
        bigint tenant_id FK
        bigint product_id FK
        bigint space_id FK "归属空间"
        varchar device_id UK "设备唯一ID"
        varchar name "设备名"
        varchar secret_cipher "AES-GCM 密文"
        varchar status "未激活/在线/离线/禁用"
        varchar firmware_version "当前固件版本"
        jsonb tags "标签"
        timestamp last_online_at
    }
    DEVICE_SHADOW {
        bigint id PK
        bigint device_id FK
        jsonb desired "期望状态"
        jsonb reported "上报状态"
        bigint version "乐观锁版本"
        timestamp updated_at
    }
    COMMAND {
        bigint id PK
        bigint device_id FK
        varchar service_identifier "服务标识"
        jsonb params "参数"
        varchar status "PENDING/SENT/SUCCESS/FAILED/TIMEOUT"
        int retry_count "重试次数"
        timestamp requested_at
        timestamp responded_at
        jsonb result "执行结果"
    }
    ALARM_RULE {
        bigint id PK
        bigint tenant_id FK
        bigint product_id FK
        varchar name "规则名"
        varchar property_identifier "属性标识"
        varchar operator "GT/GE/LT/LE/EQ/NE"
        double threshold "阈值"
        jsonb condition_tree "条件树 AND/OR + 恢复阈值"
        int duration_seconds "持续秒数"
        varchar level "级别"
        boolean enabled "是否启用"
    }
    ALARM_RECORD {
        bigint id PK
        bigint rule_id FK
        bigint device_id FK
        varchar level "级别"
        varchar status "TRIGGERED/ACKNOWLEDGED/RECOVERED/CLOSED"
        varchar message "告警内容"
        double value "触发值"
        timestamp triggered_at
        timestamp acked_at
        timestamp recovered_at
        timestamp closed_at
    }
    FIRMWARE {
        bigint id PK
        bigint tenant_id FK
        bigint product_id FK
        varchar version "版本（产品内唯一）"
        varchar file_name "原始文件名"
        varchar storage_path "本地/对象存储路径"
        varchar storage_type "LOCAL/S3"
        varchar sha256 "SHA-256"
        bigint size_bytes "大小"
        timestamp created_at
    }
    OTA_TASK {
        bigint id PK
        bigint tenant_id FK
        bigint firmware_id FK
        varchar device_id "目标设备"
        bigint rollout_id "所属灰度批次"
        int batch_no "批次号"
        varchar status "PENDING/SENT/DOWNLOADING/SUCCESS/FAILED/TIMEOUT"
        int progress "进度 0-100"
        varchar error "失败原因"
        int attempt "当前尝试次数"
        int max_attempts "最大尝试次数"
        timestamp next_attempt_at "下次重试时间"
        varchar from_version "升级前版本"
        varchar task_type "NORMAL/ROLLBACK"
        bigint rollback_of_task_id "回退的原任务"
        timestamp sent_at
        timestamp updated_at "超时巡检基准"
        timestamp finished_at
    }
    OTA_ROLLOUT {
        bigint id PK
        varchar tenant_id FK
        bigint firmware_id FK
        bigint product_id FK
        bigint project_id "批次所属项目"
        varchar target_type "DEVICE/SPACE/ALL"
        varchar status "PENDING/RUNNING/PAUSED/SUCCESS/PARTIAL_FAILED/FAILED/CANCELED"
        int batch_size "每批设备数"
        int failure_threshold_percent "熔断阈值"
        int current_batch
        int reviewed_batch "已评审批次"
        int total_count
    }
    OTA_ROLLOUT_DEVICE {
        bigint id PK
        varchar tenant_id FK
        bigint rollout_id FK
        varchar device_id
        int batch_no
        varchar status "PENDING/RUNNING/SUCCESS/FAILED/SKIPPED"
        bigint task_id FK
    }
    AUDIT_LOG {
        bigint id PK
        bigint tenant_id FK
        bigint user_id FK
        varchar action "操作"
        varchar resource "对象"
        jsonb detail "详情"
        varchar ip "来源 IP"
        timestamp created_at
    }
```

### 5.2 时序模型（TDengine）

M2 通用模型：超级表 `stb_telemetry` + 每设备子表，`payload` 存物模型参数 JSON（M3 按属性生成列式超级表，优化压缩与查询）：

```sql
CREATE STABLE IF NOT EXISTS coherer.stb_telemetry (
    ts      TIMESTAMP,
    payload NCHAR(2048)          -- 物模型参数 JSON
) TAGS (
    device_id  NCHAR(64),
    tenant_id  NCHAR(32),
    product_id BIGINT
);

-- 首次写入自动建子表
INSERT INTO coherer.d_dev_0001 USING coherer.stb_telemetry
TAGS ('dev-0001', 'default', 1234567890) (ts, payload)
VALUES (1730000000000, '{"temperature":23.5,"humidity":55.0}');
```

| 项目 | 规则 |
|---|---|
| 超级表 | `stb_telemetry`（M3 演进为按产品属性的列式超级表） |
| 子表命名 | `d_{deviceId}`（非标识符字符替换为下划线） |
| 标签 | `device_id`、`tenant_id`、`product_id` |
| 数据保留 | 原始数据 90 天（可配置），聚合结果长期 |
| 写入方式 | 物模型校验通过后写入；M3 改为消费端批量写入 |

### 5.3 Redis 键设计

| 键模式 | 类型 | TTL | 用途 |
|---|---|---|---|
| `coherer:status:device:{deviceId}` | String | 在线 TTL（默认 120s） | 设备在线标记，上下线与心跳维护 |
| `coherer:status:host:{hostId}` | String | 在线 TTL（默认 180s） | 主机在线标记，LWT + 心跳维护 |
| `coherer:refresh:{jti}` | String | 7 d | 刷新令牌白名单；值为 `tenantId\|username`，防跨租户重放 |
| `coherer:telemetry:dedup:{deviceId}:{key}` | String | 10 min | 遥测幂等去重窗口 |
| `coherer:alarm:since:{ruleId}:{deviceId}` | String | 1 h | 告警条件首次成立时间（持续时长计时） |
| `coherer:alarm:active:dev:{deviceId}` | Set | 24 h | 该设备当前活动的规则集合 |
| `coherer:command:rate:{deviceId}` | String | 1 min | 单设备命令发送限流计数 |

> 登录失败计数（`LoginAttemptService`）目前是单实例 Caffeine 内存实现，不落 Redis；
> 设备/主机/命令等 ID 全局唯一，键不再叠加租户前缀，跨租户访问由服务层显式校验。

### 5.4 多租户上下文与隔离

租户是最外层边界，`tenant.id`（如 `default`、`acme`）直接作为各业务表的 `tenant_id`。

| 路径 | 租户来源 | 说明 |
|---|---|---|
| REST | JWT `tenant` 声明 | `JwtAuthFilter` 校验租户存在且 ACTIVE 后写入 `TenantContext`，请求结束清理 |
| 登录/刷新 | 请求 `tenantCode` / refresh token `tenant` | 校验租户状态后 `TenantContext.callAs` 执行用户查询与令牌签发 |
| MQTT 上行 | 主题第一段 `{tenant}` | 网关按主题租户 `runAs` 后再分发；ACL 同时校验设备/主机/用户所属租户 |
| 定时任务 | 无 | 在线巡检、命令超时、OTA 重试/超时/灰度、告警预热显式 `runBypass` |
| 租户开通 | 平台管理员租户 | 插入租户后 `runBypass` 创建默认项目/空间/管理员，再恢复平台租户上下文 |

隔离实现：

- MyBatis-Plus `TenantLineInnerInterceptor` 为所有业务表自动追加 `tenant_id = ?`；`tenant` 表自身忽略。
- 上下文缺失时 `TenantContext.requireTenant()` 抛异常（fail-closed），禁止“忘加条件即全量查询”。
- **本地缓存命中不走 SQL**，拦截器无法兜底：`DeviceLookupService` / `HostLookupService` 读取后
  显式比较实体租户与当前上下文，不一致按“不存在”返回；系统路径必须 `runBypass` 才能跨租户读取。
- TDengine 所有查询显式带 `tenant_id` tag；空间/项目/看板/物模型等 Caffeine 缓存键包含租户。
- 用户名与产品 key 使用 `(tenant_id, code) WHERE deleted=0` 部分唯一索引，跨租户可重复。
- 设备/主机 ID 仍全局唯一（MQTT clientId 直接作为主键使用），跨租户按 `tenant_id` 校验，不依赖 ID 碰撞。
- MQTT 上行入口先校验主题租户 ACTIVE，再建立上下文；租户禁用后既有连接的消息也会被丢弃。
- 用户空间/角色/状态变更后立即失效 `DataScopeService` 的 30 秒用户缓存。
- 平台管理员只是 `default` 租户内带 `ROLE_PLATFORM_ADMIN` 的用户，业务 API 仍只看 `default` 数据；
  租户管理接口仅允许查看租户元数据与聚合计数。

## 6. 接口设计

### 6.1 对外 REST API（概览）

| 模块 | 方法与路径 | 说明 |
|---|---|---|
| 认证 | `POST /api/auth/login` | `{tenantCode, username, password}`；返回 access + refresh token 与 tenant/platform 信息 |
| 认证 | `POST /api/auth/refresh` | 刷新令牌；租户取自 refresh token 的 `tenant` 声明 |
| 认证 | `GET /api/auth/me` | 当前用户、所属租户、`platformAdmin` 标记 |
| 租户 | `GET /api/tenants` | 平台管理员查看租户列表与用户/项目/设备计数 |
| 租户 | `POST /api/tenants` | 平台管理员开通租户；自动创建默认项目、默认空间、租户管理员 |
| 租户 | `PUT /api/tenants/{id}` | 修改名称/套餐/状态；禁用后立即拒绝登录与既有令牌 |
| 实时 | `GET /api/mqtt/credentials` | 浏览器短时 MQTT 凭证（含 tenantId，WebSocket 直连 EMQX） |
| 产品 | `GET/POST /api/products` | 产品列表 / 创建 |
| 产品 | `GET/PUT/DELETE /api/products/{id}` | 产品详情 / 修改 / 删除 |
| 物模型 | `GET/PUT /api/products/{id}/thing-model` | 查询 / 保存物模型定义 |
| 项目 | `GET /api/projects` · `GET /api/projects/{id}` | 当前用户可见项目（含空间/主机/设备计数） |
| 项目 | `POST /api/projects` · `PUT/DELETE /api/projects/{id}` | 项目创建/修改/删除（仅 ADMIN；删除前必须清空空间） |
| 空间 | `GET /api/spaces/tree?projectId=` | 项目下的空间树（区域/楼栋/楼层/房间） |
| 空间 | `POST /api/spaces` · `PUT/DELETE /api/spaces/{id}` | 创建（带 projectId）/ 修改 / 删除空间 |
| 设备 | `GET/POST /api/devices` | 设备列表（分页/筛选/`projectId`/空间）/ 注册（支持批量） |
| 设备 | `GET/PUT/DELETE /api/devices/{id}` | 详情 / 修改 / 删除 |
| 设备 | `POST /api/devices/{id}/enable` | 启用 |
| 设备 | `POST /api/devices/{id}/disable` | 禁用 |
| 影子 | `GET/PUT /api/devices/{id}/shadow` | 查询影子 / 设置 desired |
| 遥测 | `GET /api/devices/{id}/telemetry` | 历史查询（时间范围 + 聚合粒度） |
| 命令 | `POST /api/devices/{id}/commands` | 服务调用 |
| 命令 | `GET /api/devices/{id}/commands` | 命令历史 |
| 告警 | `GET /api/alarms` | 告警列表（筛选，支持 `projectId`） |
| 看板 | `GET /api/dashboard/overview` · `/telemetry-trend` · `/recent-telemetry` | 总览 KPI、小时趋势、最近遥测（支持 `projectId`） |
| 告警 | `POST /api/alarms/{id}/ack` `.../close` | 确认 / 关闭 |
| 规则 | `GET/POST /api/alarm-rules` | 告警规则管理 |
| OTA | `GET/POST /api/ota/firmwares` · `DELETE /api/ota/firmwares/{id}` | 按产品列出 / 上传（multipart）/ 删除固件 |
| OTA | `GET /api/ota/download/{firmwareId}` | 设备侧签名下载（无需 JWT，10 分钟有效；支持 `Range` 206） |
| OTA | `POST /api/ota/tasks` | 对单设备创建升级任务并立即下发 |
| OTA | `GET /api/ota/tasks?deviceId=&limit=` | 设备最近升级任务与进度 |
| OTA | `POST /api/ota/tasks/{id}/rollback` | 按任务的 from_version 生成 ROLLBACK 任务 |
| OTA | `GET/POST /api/ota/rollouts` | 灰度批次列表 / 创建（设备、空间、全量目标） |
| OTA | `GET /api/ota/rollouts/{id}` | 批次详情与成员进度 |
| OTA | `POST /api/ota/rollouts/{id}/pause` · `/resume` · `/cancel` | 暂停 / 恢复 / 取消灰度 |

### 6.2 内部接口（EMQX 回调）

| 方法与路径 | 调用方 | 说明 |
|---|---|---|
| `POST /internal/auth/mqtt` | EMQX HTTP 认证器 | 设备连接/发布/订阅认证，返回 allow/deny |
| `POST /internal/auth/acl` | EMQX HTTP ACL | 主题级权限校验 |
| `POST /internal/events/connected` | EMQX Webhook | 设备上线事件 |
| `POST /internal/events/disconnected` | EMQX Webhook | 设备下线事件（含 LWT 原因） |

### 6.3 MQTT 主题与消息格式

主题规划详见 [architecture.md](architecture.md) 第 3 节。消息体统一信封：

**属性上报**（`{tenant}/{deviceId}/thing/property/report`）

```json
{
  "id": "msg-000123",
  "ts": 1762953600000,
  "version": "1.0",
  "params": {
    "temperature": 23.5,
    "humidity": 55.0
  }
}
```

**命令下发**（`{tenant}/{deviceId}/thing/service/{serviceIdentifier}`）

MQTT 5.0 设备使用原生属性：

| MQTT 5 属性 | 值 | 说明 |
|---|---|---|
| Response Topic | `{tenant}/{deviceId}/thing/service/{svc}/reply` | 设备回复地址 |
| Correlation Data | `cmd-8842` | 命令 messageId，设备回复时原样带回 |
| Message Expiry Interval | 10 秒 | 超时后 Broker 不再投递（即命令有效期） |

Payload（两种协议版本共用）：

```json
{
  "id": "cmd-8842",
  "ts": 1762953600000,
  "service": "reboot",
  "params": { "delaySeconds": 5 },
  "replyTopic": "default/dev-0001/thing/service/reboot/reply"
}
```

**协议兼容策略**

| 设备能力 | 下发方式 | 回复方式 | 关联依据 |
|---|---|---|---|
| MQTT 5.0 | 设置 Response Topic + Correlation Data 属性 | 发布到 Response Topic，带回 Correlation Data | MQTT 5 属性（推荐） |
| MQTT 3.1.1 | 读取消息体 `replyTopic` 字段 | 发布到 `replyTopic`，`id` 原样带回 | messageId 字段 |

平台侧统一按 messageId 关联命令与回复；重试时 messageId 不变，设备侧必须幂等执行。

**命令回复**（Response Topic 或 `replyTopic`）

```json
{
  "id": "cmd-8842",
  "code": 0,
  "message": "ok",
  "data": {}
}
```

## 7. 部署架构

### 7.1 开发环境（Docker Compose + 本机进程）

```mermaid
flowchart LR
    subgraph HOST["开发机 Docker Compose"]
        EMQX["EMQX<br/>:1883 / :8883 (TLS) / :8083 / :18083"]
        PG["PostgreSQL<br/>:5432"]
        R["Redis<br/>:6379"]
    end

    BE["Spring Boot<br/>本机 :8081"]
    SIM["Python 模拟器<br/>本机进程"]
    WEB["Vue 3 dev server<br/>:5180"]

    SIM -->|"MQTT / TLS"| EMQX
    BE -->|"JDBC"| PG
    BE -->|"Lettuce"| R
    BE -->|"MQTT"| EMQX
    WEB -->|"REST"| BE
    WEB -->|"WebSocket"| EMQX
```

### 7.2 生产环境（Kubernetes）

```mermaid
flowchart TB
    LB["Ingress / LoadBalancer"]

    subgraph K8S["Kubernetes 集群"]
        subgraph ACCESS["接入层"]
            E1["EMQX-0"]
            E2["EMQX-1"]
            E3["EMQX-2"]
        end

        subgraph APP["应用层"]
            GW["gateway ×2"]
            DS["device-service ×2"]
            ING["ingest-service ×N"]
            RS["rule-service ×2"]
            OS["ota-service ×1"]
        end

        subgraph MID["中间件"]
            KF["Kafka 集群"]
            PGS["PostgreSQL 主从"]
            RDS["Redis 哨兵"]
            TDS["TDengine 集群"]
        end

        subgraph OBS["可观测"]
            PM["Prometheus"]
            GF["Grafana"]
        end
    end

    LB --> E1
    LB --> E2
    LB --> GW
    E1 -->|"认证回调"| GW
    GW --> DS
    GW --> RS
    GW --> OS
    ING -->|"批量写入"| TDS
    DS --> PGS
    RS --> PGS
    OS --> PGS
    ING --> RDS
    E1 -->|"规则引擎"| KF
    PM -.-> E1
    PM -.-> GW
    PM -.-> PGS
    GF --> PM
```

## 8. 关键设计决策

| 决策 | 结论 | 理由 |
|---|---|---|
| 接入协议 | MQTT 3.1.1/5.0，JSON 载荷起步 | 生态成熟；后续按需演进 Protobuf |
| 认证方式 | HMAC-SHA256 签名 + HTTP 认证回调 | 对齐阿里云 IoT；密钥不落明文；可缓存 |
| MQTT 消费模型 | 共享订阅 `$share/coherer/...` | 消费者水平扩展、无重复消费（同组内） |
| 削峰 | Kafka 桥接遥测流 | 入库解耦、可重放、多消费组复用 |
| 时序模型 | M2：通用超级表 + 每设备子表（payload JSON）；M3：按物模型属性生成列式超级表 | 先打通链路，再按物模型优化压缩与查询性能 |
| 设备影子 | desired/reported/version 乐观锁 | 解决离线设备命令问题；面试常考 |
| 命令可靠性 | QoS1 + messageId 幂等 + reply + 超时重试；MQTT 5 优先 Response Topic / Correlation Data，3.1.1 回退 replyTopic 字段 | 兼顾语义与成本，不做 QoS2；新旧协议双兼容 |
| 前端实时数据 | 浏览器 mqtt.js 直连 EMQX WS | 实时性最好，避免后端转发瓶颈 |
| 鉴权 | JWT（access+refresh）+ RBAC | 无状态、便于网关统一校验 |
