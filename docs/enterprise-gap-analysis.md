# Coherer 企业级差距分析（Gap Analysis）

| 项目 | 内容 |
|---|---|
| 审计版本 | `705d67d`（B+ 整改完成：数据权限、端口收敛、幂等批量、告警 Redis、可观测、CI） |
| 审计日期 | 2026-11-14 |
| 审计范围 | 后端、前端、模拟器、部署编排、测试与文档 |
| 结论 | **可作为生产基线**：P0 安全与可靠性阻断项已整改；剩余为高可用、物理隔离与压测、部分功能完整性（最新状态见第 9 节） |

> 企业级判定维度：安全合规、可靠性/可扩展、可观测、可维护、测试与交付工程化、功能完整性。

## 1. 评分总览

| 维度 | 评分 | 主要依据 |
|---|:-:|---|
| 架构设计 | 6/10 | 分层清晰、文档齐全、MQTT/平台边界正确；但单体、单点、扩展性未验证 |
| 安全 | 7/10 | JWT+RBAC、内部令牌、密钥外置、REST 数据权限、端口收敛、Redis/TDengine 口令；剩余 WSS 默认与 PG/TD TLS |
| 可靠性/性能 | 6/10 | 幂等去重、有界队列批量写入、停机冲刷、告警状态 Redis 一致、在线 TTL 兜底；剩余无 HA 与压测 |
| 可观测性 | 9/10 | Prometheus+Grafana+Alertmanager、Zipkin 链路、ECS 结构化日志、审计日志、探针分组 |
| 测试质量 | 6/10 | 125 个后端单测 + 11 个前端 Vitest；仍缺 Controller/Testcontainers/端到端 |
| 功能完整度 | 6/10 | 核心链路完整；需求文档中多项 M2 功能未实现 |
| 交付工程化 | 5/10 | GitHub Actions（测试/构建/配置校验）、环境分层、Compose 一键启动；剩余镜像发布与 OpenAPI |

**一句话结论：核心链路已具备生产基线，横向扩展与合规运营仍需补齐。**

## 2. P0：生产阻断项

### 2.1 安全

| 问题 | 现状与证据 | 风险 |
|---|---|---|
| 无统一认证与 RBAC | 未引入 Spring Security；所有 `/api/**` 接口匿名可访问 | 任何访问者均可读写设备、空间、告警与规则 |
| MQTT Web 凭证接口开放 | `MqttCredentialController` 无鉴权；ACL 允许 Web 订阅 `default/+/thing/property/report` | 匿名获取凭证即可监听全平台遥测，数据泄露 |
| 内部接口开放 | `/internal/auth/mqtt`、`/internal/auth/acl` 无共享密钥/mTLS | 可在线爆破设备密钥（allow/deny oracle）；ACL 可被探测 |
| 密钥硬编码 | `application.yml` 中数据库密码、AES 主密钥、ingest/web 口令、TDengine root 密码 | 仓库泄露即全量失陷；无法按环境轮换 |
| 内部链路明文 | 后端→EMQX 1883；PG/Redis/TDengine 无 TLS；REST HTTP；前端 `ws://` | 内网嗅探可窃取数据与凭证 |
| Dashboard 暴露 | Compose 将 18083 映射到 `0.0.0.0`，默认 `admin/public` | 未授权访问管理面 |
| 无限流/锁定/审计 | 认证与 REST 无速率限制；ACL 结果缓存 60s；无操作审计 | 暴力破解、权限吊销延迟、事后不可追溯 |
| 其他 | 无依赖漏洞扫描、无 CSP、无密钥轮换、无 Web 凭证刷新策略（TTL 300s） | 供应链与前端安全缺口 |

### 2.2 可靠性 / 扩展性

| 问题 | 现状与证据 | 影响 |
|---|---|---|
| 单消费者逐条处理 | `MqttGateway` 单 Paho 客户端；`TelemetryTdStore.insert` 每条消息一次写入 | 无法支撑 5 万 msg/s 目标；无背压 |
| 告警评估 N+1 | `AlarmRecordService.evaluate` 每条消息对每条规则执行 `hasActive` 数据库查询 | 消息量 × 规则数放大 DB 压力 |
| 告警状态在内存 | 持续计时与滞回状态存 Caffeine；多实例不一致、重启丢失 | 多副本部署时重复触发/误恢复 |
| 无保序水位 | 未实现每设备 `lastTs` 水位；乱序消息可直接改变告警状态 | 误触发、误恢复（已在架构讨论中确认） |
| 无幂等 | QoS1 重投递会在 TDengine 写入重复行；无 messageId 去重 | 数据质量下降、统计失真 |
| 单点架构 | EMQX/PG/Redis/TDengine 均为单节点；认证回调强依赖后端可用性 | 任一组件故障导致平台不可用；无备份/恢复演练 |
| 无死信/熔断 | 处理失败仅日志丢弃 | 数据静默丢失，无法补偿 |

### 2.3 HTTP 语义与防护

- `GlobalExceptionHandler` 对 `BizException(404/409)` 统一返回 HTTP 400，与 REST 语义不符。
- 设备/告警/规则分页 `size` 无上限（`DeviceService.page`、`AlarmRecordService.page`、`AlarmRuleService.page`）→ 可被用于拖库/DoS。
- 无 API 版本化、无 OpenAPI 契约、无集中错误码目录。

## 3. P1：工程规范差距

| 领域 | 现状 | 企业要求 |
|---|---|---|
| 测试 | 32 个单测（条件树/告警/认证/ACL/空间）；无 Controller 测试、无 Testcontainers 集成测试、无前端测试 | `@WebMvcTest` + MockMvc；Testcontainers（PG/EMQX/TDengine）；Vitest + Playwright |
| 覆盖率门禁 | 无 JaCoCo/Failsafe | 新增代码行 ≥80%、分支 ≥70%，CI 强制 |
| CI/CD | 无 `.github/workflows`，无镜像构建/扫描/发布 | PR 门禁：编译 + 单测 + 集成测试 + 覆盖率 + 镜像扫描 |
| 可观测 | 仅 Actuator `health/info/metrics` 暴露，无 Prometheus registry | Micrometer + Prometheus + Grafana；结构化日志 + trace-id 贯穿 MQTT→消费者→HTTP |
| 配置管理 | 单份 `application.yml`，密钥硬编码 | dev/prod profile、环境变量/配置中心、启动期校验 |
| 数据生命周期 | Flyway 已用；TDengine 无保留策略、无批量写入；PG 缺少部分查询索引；告警记录无限增长 | 数据保留/归档、批量写入、慢查询治理、分区/清理任务 |
| 前端质量 | 无登录与路由守卫、无错误边界、无单测；vendor 包约 1.9MB | 权限路由、全局错误上报、性能预算、可访问性审计 |
| 文档治理 | 需求/设计/架构齐备，但与实现已有偏差 | OpenAPI、ADR、CHANGELOG、接口契约测试 |

## 4. P2：功能完整度（需求 vs 实现）

### 4.1 需求文档中未实现的 FR

| 需求 | 说明 | 当前状态 |
|---|---|---|
| FR-DEV-05 | 设备启用/禁用接口与 UI | ❌ 未实现（仅注册/查询） |
| FR-DEV-07 | 设备批量导入/导出 | ❌ 未实现 |
| FR-DATA-02 | 事件上报与查询 | ❌ 未实现 |
| FR-DATA-04 | 时间范围 + 聚合/降采样查询 | ❌ 仅支持最近 N 条，无范围/聚合 |
| FR-DATA-05 | Redis 最新值快照 | ❌ 未实现（直接查 TDengine） |
| FR-DATA-07 | 数据导出 CSV | ❌ 未实现 |
| FR-AUTH-04 | 用户登录 JWT / 接口鉴权 | ❌ 未实现 |
| FR-AUTH-05 | 设备密钥轮换/吊销 | ❌ 未实现（仅注册时生成） |
| FR-PROD-03 | 物模型版本化 | ❌ 未实现 |
| FR-PROD-04 | 一型一密策略 | ❌ 仅一机一密 |
| FR-SYS-02 | 用户/角色/权限点 | ❌ 未实现 |
| FR-VIZ-01 | 总览中的告警统计 | ⚠️ 部分（无告警 KPI） |

### 4.2 告警规则能力缺口

| 能力 | 状态 |
|---|---|
| 多条件 AND/OR、嵌套、恢复阈值（滞回） | ✅ 已实现 |
| 条件摘要与告警快照 | ✅ 已实现 |
| 规则作用域（空间/设备组） | ❌ 目前仅按产品 |
| 规则版本 / 草稿-发布 / 审批 | ❌ 未实现 |
| 规则预览（历史数据 dry-run） | ❌ 未实现 |
| 通知渠道（邮件/钉钉/Webhook）与升级策略 | ❌ 计划 M4 |
| 静默期 / 维护窗口 / 告警抑制 | ❌ 计划 M4 |
| 布尔/字符串/事件/离线/变化率规则 | ❌ 条件树已预留扩展，尚未实现 |
| 跨属性比较与表达式 DSL（如 Aviator） | ❌ 未实现 |

### 4.3 其他边界问题

- **空间/项目/租户数据权限已落地**：服务端按用户空间范围收窄，项目可见性由空间推导；租户由 `TenantContext` 与 MyBatis 拦截器强制隔离。平台管理员刻意不能读取其他租户业务数据。
- **租户隔离是逻辑隔离**：共享库表 + `tenant_id`，未做独立库/独立 schema 的物理隔离；超大规模租户需评估分库。
- **MQTT 既有连接不会因租户禁用被 Broker 主动断开**：新连接/新 ACL 校验会拒绝，连接保留到客户端重连；生产可接 EMQX 管理 API 主动踢线。
- OTA、命令下发、通知、场景联动均在 M3/M4 计划内，当前不可宣称全部完成。

## 5. 整改路线图

### 阶段 A：安全基线（建议 1~2 周，最高优先级）

| 任务 | 验收标准 |
|---|---|
| A1 密钥外置与环境分层 | 所有密码/密钥来自环境变量或 `.env`；提交 `.env.example`；启动期缺配置快速失败 |
| A2 用户认证 + RBAC | Spring Security + JWT；登录/刷新接口；角色权限点；所有 `/api/**` 需要鉴权 |
| A3 内部接口与 Web 凭证保护 | `/internal/auth/**` 需要共享密钥头或 mTLS；`/api/mqtt/credentials` 需登录并绑定用户数据范围 |
| A4 限流与 HTTP 语义 | 登录/认证接口限流；分页 size 上限；404/409 返回正确 HTTP 状态；错误码目录 |
| A5 传输安全 | REST HTTPS；前端 WSS；后端→EMQX TLS；PG/Redis/TDengine 内网隔离或 TLS；EMQX Dashboard 改密并限制来源 |

### 阶段 B：可观测性（2~3 天）

- Micrometer + Prometheus + Grafana（JVM、HTTP、MQTT 消费、入库速率、告警触发数）。
- 结构化 JSON 日志，trace-id 贯穿 MQTT 消息、消费者与 HTTP 请求。
- readiness/liveness 分离；关键路径慢查询/错误率看板。
- 审计日志：规则变更、告警确认/关闭、设备启停。

**B 已完成（2026-11-14）**

- Micrometer Prometheus registry；`/actuator/prometheus` 暴露 JVM/HTTP/自定义指标；Compose 内置 Prometheus 抓取任务。
- 自定义指标：`coherer.telemetry.ingested{result=ok|rejected|error}`、`coherer.alarm.triggered{level}`、`coherer.auth.login.failures`、`coherer.mqtt.connected`。
- ECS 结构化 JSON 控制台日志；Brave + Micrometer Tracing，MQTT 入口为每条消息创建 `mqtt.message` span，traceId/spanId 贯穿消费、校验、入库与告警日志。
- `audit_log`（V10）+ 仅 ADMIN 可查的 `GET /api/audit-logs`；已覆盖登录成功/失败/限流、设备注册、产品与物模型变更、告警确认/关闭、告警规则增删改、空间增删改、影子期望值下发。
- Grafana 11 自动装配 Prometheus 数据源与「Coherer IoT 平台总览」看板（12 个面板：服务状态、MQTT 连接、遥测速率/拒绝率、REST QPS/P95/P99、JVM 堆与 CPU、告警触发、登录失败、GC 暂停）。
- Prometheus 9 条告警规则（可用性、遥测拒绝率/静默、告警风暴、登录爆破、5xx、P95、堆内存），Alertmanager 已在 Compose 中，默认仅展示告警。
- 待补：`readiness/liveness` 探针分离、TDengine/PG 慢查询面板、Alertmanager 通知渠道（webhook/邮件/钉钉）。

### 阶段 C：可靠性与扩展（1~2 周，与 M3 合并）

- Kafka 按 `deviceId` 分区消费；TDengine 批量写入；幂等去重（deviceId+ts/messageId）。
- 每设备时间水位；告警状态与持续计时迁移 Redis/Flink 状态，多实例一致。
- 告警 N+1 优化：按产品批量加载规则与活动告警，或流式状态。
- 死信主题、重试策略、背压与熔断；认证回调降级策略。

### 阶段 D：测试与 CI（约 1 周）

- Controller 测试（`@WebMvcTest`）、Testcontainers 集成测试（PG/EMQX/TDengine）、MQTT 畸形/重复消息用例。
- 前端 Vitest + Playwright（登录、设备详情、规则创建）。
- JaCoCo 覆盖率门禁 + GitHub Actions PR 流水线 + 镜像扫描。

### 阶段 E：功能补齐（M3/M4）

设备启停、事件上报、命令下发、OTA、多租户**已完成**；历史区间聚合、最新值快照、通知渠道、规则版本/预览/作用域仍待做。

### 阶段 F：生产化（M5）

K8s/Helm、EMQX/PG/Redis/TDengine 高可用、备份恢复演练、mTLS、审计合规、10 万连接压测报告。

## 6. 整改验收清单

- [ ] 所有 `/api/**` 与 `/internal/**` 接口具备认证/鉴权与审计
- [ ] 无任何硬编码密码/密钥，`.env.example` 完整
- [ ] 分页有上限，404/409/401/403 状态码语义正确
- [ ] 内部链路 TLS 或网络隔离，Dashboard 无默认口令
- [x] Prometheus 指标与 trace-id 可用（Grafana 看板待补）
- [x] 关键操作审计日志可查询（ADMIN）
- [x] Grafana 看板与 Prometheus 告警规则
- [ ] 告警状态与水位可在多实例间一致
- [ ] TDengine 批量写入 + 幂等，压测达到目标吞吐
- [ ] CI 门禁：测试 + 覆盖率 + 镜像扫描通过
- [ ] 需求文档中 M2 的 FR 全部完成或明确降级
- [ ] 备份恢复演练通过，单节点故障可恢复

## 7. 值得肯定的企业级实践

- Flyway 版本化迁移；逻辑删除 + 部分唯一索引；乐观锁（设备影子）。
- 统一响应/异常、DTO/VO 分层、构造器注入、参数校验。
- MQTT 签名认证 + 主题级 ACL（HTTP 回调）、共享订阅、遗嘱消息、TLS 设备接入。
- Caffeine 热缓存、事件驱动解耦（影子下发）、TDengine 时序存储、空间树过滤。
- 前端设计系统（Apple + 企业风格）、路由懒加载、可访问性基础、响应式。
- 文档体系（需求/设计/架构/路线图）与 54 个单元测试。

## 8. 证据索引

| 问题 | 代码位置 |
|---|---|
| 硬编码密钥/口令 | `backend/src/main/resources/application.yml` |
| 开放 MQTT Web 凭证 | `backend/src/main/java/.../internal/auth/MqttCredentialController.java` |
| 内部认证/ACL 无保护 | `backend/src/main/java/.../internal/auth/MqttAuthController.java`、`MqttAclController.java` |
| HTTP 状态码语义 | `backend/src/main/java/.../common/GlobalExceptionHandler.java` |
| 告警状态在内存 / N+1 | `backend/src/main/java/.../alarm/service/AlarmRecordService.java` |
| 单条遥测入库 | `backend/src/main/java/.../telemetry/TelemetryTdStore.java` |
| 分页无上限 | `DeviceService.page`、`AlarmRecordService.page`、`AlarmRuleService.page` |
| 单点编排 / Dashboard 暴露 | `deploy/docker-compose.yml` |
| 无认证依赖 | `backend/pom.xml`（无 Spring Security） |
| 无 CI/覆盖率门禁 | 仓库根目录（无 `.github/`、无 JaCoCo/Testcontainers） |
| 功能缺口 | `docs/requirements.md` 中 FR-AUTH-04/05、FR-DEV-05/07、FR-DATA-02/04/05/07 等 |

## 9. 整改进度

| 阶段 | 状态 | 说明 |
|---|---|---|
| A1 密钥外置与环境分层 | ✅ 2026-11-14 | `application.yml` 全部改为环境变量；新增 `backend/.env.example`、`deploy/.env.example`；缺少变量启动即失败；Compose 数据库密码从 `deploy/.env` 注入 |
| A2 用户认证 + RBAC | ✅ 2026-11-14 | Spring Security 无状态 JWT；ADMIN / OPERATOR / VIEWER RBAC；**token_version 支持登出/禁用/改密/降级即时吊销**；前端登录页、路由守卫、按角色隐藏写操作 |
| A3 内部接口与 Web 凭证保护 | ✅ 2026-11-14 | `/internal/**` 校验 `X-Internal-Token`；`/api/mqtt/credentials` 仅登录用户；ACL 按用户空间限制订阅；**REST 数据权限已补齐**（DataScope/DeviceAccessService） |
| A4 限流与 HTTP 语义 | ✅ 2026-11-14 | 登录失败 5 次/5 分钟限流（同用户名+IP，429）；分页 size 上限 100；`BizException` 按 401/403/404/409 返回 |
| A5 传输安全 | ✅ 2026-11-15 | REST HTTPS + WSS（Nginx 8443，本地 CA）；后端→EMQX TLS 8883；PostgreSQL `ssl=on` + 后端 `sslmode=verify-full`；所有基础设施端口收敛到 127.0.0.1；Redis requirepass + TDengine 强制口令；指标端点要求内部令牌；PG/EMQX/TDengine/Redis 备份恢复脚本已验证 |
| B 可观测性 | ✅ 2026-11-14 | Prometheus + Grafana 12 面板 + Alertmanager（critical 路由/webhook/2 条抑制规则）；Zipkin 链路追踪；ECS 日志带 traceId；审计日志含 traceId/结果/UA/真实 IP；liveness/readiness 分组 |
| C 可靠性/M3 | 🟡 部分完成 | **遥测幂等去重 + 有界队列批量写入 + 停机冲刷**；**告警状态/计时迁移 Redis**；**在线状态 TTL 兜底**；Kafka 分区与时间水位仍待做 |
| D 测试与 CI | 🟡 部分完成 | **GitHub Actions：后端 322 单元测试 + 9 个真实 PG/Redis 集成测试（Flyway/租户隔离/项目隔离/灰度/重试/回退/Range）+ JaCoCo 门禁（67.7% 行 / 53.5% 分支，基线 60%/45%）+ OpenAPI、前端 12 个 Vitest + type-check/build、Compose/E2E 脚本**；覆盖率目标 80%/70%、外部 EMQX 集成测试待补 |
| E 功能补齐 | 🟡 部分完成 | **多租户（租户上下文/数据隔离/租户管理/禁用即时生效）、项目层（项目 → 空间 → 主机/设备、项目切换/管理、项目级过滤）、设备启停/删除/密钥轮换、用户管理、命令下发与回执、事件上报、边缘主机/网关、OTA 全链路已完成**；区间聚合、通知渠道、规则版本待做 |
| F 生产化 | 🟡 部分完成 | **镜像、Nginx 同源部署、CI 镜像构建推送、备份恢复已完成**；K8s/Helm、HA、压测待做 |

> 复核说明（多租户落地后）：Compose 启动顺序/内部地址、命令物模型校验与幂等限流、Refresh 轮换、告警批量评估、TDengine space tag/保留期、CI 镜像推送、HTTPS/WSS + PG TLS、备份恢复、项目层与多租户均已落地，安全约 8.8/10、可靠性约 7/10、可观测约 9/10、交付约 8/10；剩余缺口为 **Kafka/水位/HA 与压测**、**跨租户物理隔离与 EMQX 踢线**、**外部 Broker 集成测试与覆盖率爬坡**。
