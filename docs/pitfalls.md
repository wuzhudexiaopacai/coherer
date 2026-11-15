# Coherer 踩坑记录

> 记录开发与整改过程中真实遇到、且容易重复踩的问题。每条包含：现象、原因、修复方式、验证方式。
> 按主题分类，最后附「本次整改提交索引」。

## 1. 构建与工具链

### 1.1 Git Bash 把 openssl `-subj` 当成 Windows 路径

**现象**：`openssl req -subj "/C=CN/O=..."` 报错或生成异常证书主体。  
**原因**：MSYS2 会把以 `/` 开头的参数转换为 `C:/Program Files/...`。  
**修复**：脚本开头 `export MSYS_NO_PATHCONV=1`。  
**验证**：`openssl x509 -in server.crt -noout -subject` 输出 `C=CN, O=Coherer Dev, CN=localhost`。

### 1.2 Maven 3.8+ 阻止 HTTP 镜像

**现象**：`mvn dependency:get` 报 `Blocked mirror for repositories: [nexus-aliyun (http://...)]`。  
**原因**：Maven 3.8 默认启用 `maven-default-http-blocker`，全局 `settings.xml` 配的是 HTTP 阿里云镜像。  
**修复**：临时用 HTTPS 镜像配置：

```bash
mvn -s /tmp/mvn-https-settings.xml ...
# settings.xml 中 mirror url 用 https://maven.aliyun.com/repository/public
```

**经验**：新增依赖时优先确认本地仓库是否已有；CI 用 Maven Central 不受影响。

### 1.3 Windows 下运行中的 jar 无法被覆盖

**现象**：`mvn package` 报 `Unable to rename ...jar to ...jar.original`。  
**原因**：后端进程占用 `target/coherer-backend-*.jar`。  
**修复**：先 `taskkill //PID <pid> //F` 再打包；或输出到不同目录。  
**验证**：`netstat -ano | grep :8081` 确认无监听进程。

### 1.4 Bash 中 `UID` 是只读变量

**现象**：脚本里 `UID=$(...)` 报 `readonly variable`。  
**修复**：改用 `USERID`、`OPID` 等变量名。

### 1.5 Docker 构建容器内无法解析 Maven 仓库域名

**现象**：`docker compose build backend` 在 `dependency:go-offline` 阶段报 `Unknown host maven.aliyun.com`。  
**原因**：Docker build 容器网络/DNS 与宿主机代理不一致，构建期联网不可靠。  
**修复**：后端镜像改为「运行时镜像」，jar 由 CI 或本地 `mvn -DskipTests package` 预构建：

```dockerfile
FROM eclipse-temurin:21-jre
COPY target/coherer-backend-*.jar app.jar
```

**经验**：部署镜像不应依赖构建期访问外网仓库；CI 负责构建产物。

### 1.6 EMQX 的 HOCON 不展开任意环境变量

**现象**：把回调地址写成 `url = "${COHERER_BACKEND_URL}/internal/auth/mqtt"`，EMQX 启动报 `invalid_url {no_scheme}`。  
**原因**：EMQX 只展开它自己的模板变量（如 `$${clientid}`），不会展开自定义环境变量。  
**修复**：基础 Compose 用宿主机地址；整栈容器化用 `docker-compose.app.yml` 覆盖完整的认证/ACL 配置块，写死 `http://backend:8081`。  
**验证**：模拟器经内部回调成功接入，Prometheus target `backend:8081` 为 up。

### 1.7 镜像里跑了旧 jar

**现象**：容器启动仍连接 `localhost:5432`，环境变量没生效。  
**原因**：修改 `application.yml` 后只重建了镜像，没有重新打包 jar，`COPY target/*.jar` 复制的是旧包。  
**修复**：先 `mvn package`，再 `docker compose build`。  
**验证**：容器日志显示 `jdbc:postgresql://postgres:5432/...`。

### 1.8 Nginx 配置打进镜像，改完必须 rebuild

**现象**：修改 `frontend/nginx.conf` 后 `docker compose up -d frontend`，HTTPS 仍不生效、8080 仍返回 200。  
**原因**：nginx.conf 在镜像构建时 COPY，compose 只重建容器不会重建镜像。  
**修复**：`docker compose build frontend && docker compose up -d frontend`。

### 1.9 Windows curl 校验自签证书报吊销未知

**现象**：`curl --cacert ca.crt https://localhost:8443` 报证书验证失败。  
**原因**：Git Bash 的 curl 使用 Schannel，不认 PEM CA/吊销列表。  
**修复**：加 `--ssl-no-revoke`，或改用 OpenSSL 验证：`openssl s_client -CAfile ca.crt -connect localhost:8443`。  
**验证**：`Verify return code: 0 (ok)`。

### 1.10 Git Bash 里 curl 发送中文 JSON 变成非法 UTF-8

**现象**：后端报 `JSON parse error: Invalid UTF-8 middle byte 0xcb`。  
**原因**：Windows 终端按 GBK 传参，服务端按 UTF-8 解析。  
**修复**：测试数据用 ASCII；中文写入文件后 `--data-binary @file`；或显式 UTF-8 编码。  
**验证**：响应 `code=0`，数据库中文正常。

## 2. MyBatis-Plus

### 2.1 3.5.18 分页插件与 `ServiceImpl` 不在原包

**现象**：启动报 `PaginationInnerInterceptor` 找不到、`ServiceImpl` 找不到。  
**原因**：3.5.9+ 拆分了 `mybatis-plus-jsqlparser` 与 `mybatis-plus-spring`。  
**修复**：

```xml
<dependency>mybatis-plus-jsqlparser</dependency>
<dependency>mybatis-plus-spring</dependency>
```

`ServiceImpl` 改为 `com.baomidou.mybatisplus.spring.service.impl.ServiceImpl`。

### 2.2 纯单元测试报 `can not find lambda cache`

**现象**：`Device::getStatus` 之类的 Lambda 在单测里抛异常。  
**原因**：没有 Spring 容器，MyBatis-Plus 未初始化实体表元数据。  
**修复**：

```java
TableInfoHelper.initTableInfo(
    new MapperBuilderAssistant(new MybatisConfiguration(), ""), Device.class);
```

### 2.3 Mockito 不能 `when(...)` 桩 void 方法

**现象**：`when(valueOperations.set(...)).thenThrow(...)` 编译失败/行为异常。  
**修复**：`doThrow(new RuntimeException(...)).when(valueOperations).set(...)`。

### 2.4 `insert` / `updateById` 在 Mockito 中有重载歧义

**现象**：`when(mapper.insert(any(SysUser.class)))` 报“对 insert 的引用不明确”。  
**原因**：BaseMapper 同时有 `insert(T)` 与 `insert(Collection<T>)`。  
**修复**：`when(mapper.insert((SysUser) any()))`；`argThat` 的 lambda 参数显式写类型：`argThat((SysUser user) -> ...)`。

### 2.5 雪花 ID 到前端精度丢失

**现象**：设备详情/删除报“设备不存在”。  
**原因**：JS `Number` 最大安全整数 2^53-1，雪花 ID 超出后末位被改。  
**修复**：全局 Jackson 把 `Long` 序列化为字符串，前端 ID 类型统一用 `string`。

### 2.6 全局 Long→String 后 INT 属性也变字符串

**现象**：遥测 `temperature` 等数值在图表里变成字符串/为 0。  
**原因**：Java 三元表达式 `Integer/Long` 混合提升为 long，被全局序列化为字符串。  
**修复**：显式分支返回 `Integer.valueOf(...)` / `Long.valueOf(...)`，不要依赖三元表达式自动装箱。

### 2.7 影子 `updatedAt` 不刷新

**现象**：更新 desired 后更新时间不变。  
**原因**：`strictUpdateFill` 对 `updateById` 的字段填充策略。  
**修复**：在 `MetaObjectHandler` 中用 `setFieldValByName("updatedAt", now, metaObject)` 强制写入。

## 3. MQTT / EMQX

### 3.1 Paho `connect()` 成功不代表认证通过

**现象**：禁用设备的连接测试打印 “CONNECTED”，误以为鉴权失效。  
**原因**：Paho 的连接是异步的，`connect()` 返回时 CONNACK 可能还没到。  
**修复**：用 `on_connect` 回调或等待 `is_connected()`；正确结果是 `rc=5`（Not authorized）。  
**验证**：

```python
client.on_connect = lambda c,u,f,rc,p=None: result.setdefault('rc', str(rc))
```

### 3.2 后端启动时 MQTT 首次连接失败

**现象**：启动日志 `接入 EMQX 失败: 无权连接`，10 秒后自动恢复。  
**原因**：后端尚未完全启动时 EMQX 的 HTTP 认证回调不可用，属于启动顺序问题。  
**修复**：`MqttGateway` 已有 10 秒兜底重连；不需要人为干预。  
**经验**：健康检查应看 `/actuator/health`，不要因为一条首次连接警告判定失败。

### 3.3 共享订阅不保证同一设备落到同一实例

**影响**：告警持续计时、活动告警去重不能放本地内存。  
**修复**：计时与认领迁 Redis（`AlarmStateService`），数据库活动告警作为最终事实源。

## 4. TDengine

### 4.1 批量 INSERT 语法不是逗号分隔

**正确写法**：

```sql
INSERT INTO coherer.d_dev1 USING coherer.stb_telemetry TAGS (?, ?, ?)
(ts, payload) VALUES (?, ?) (?, ?)
```

多个值元组之间用空格分隔。  
**验证**：`affected_rows` 与 `select count(*)` 一致。

### 4.2 `payload NCHAR(2048)` 会截断大报文

**影响**：属性很多或字符串很长时静默丢数据。  
**当前状态**：已记录为待办，写入前应校验长度并拒绝超限。  
**经验**：时序库泛化 JSON 只适合 M2；生产应按物模型建属性列。

### 4.3 taosdump 输出目录必须预先存在

**现象**：`taosdump ... -o /tmp/tddump` 报 `/tmp/tddump/ is not exist!`。  
**修复**：先 `mkdir -p /tmp/tddump`，再执行 dump。  
**备份脚本**：`deploy/scripts/backup.sh` 已处理，并对目录做 `find -type f` 校验和，避免 `sha256sum` 对目录报错。

### 4.4 Docker 内 taosAdapter 认证

**现象**：REST 错误口令返回 HTTP 200，但 body 是 `{"code":855,"desc":"Authentication failure"}`。  
**修复**：判断 body 的 `code`，不要只看 HTTP 状态码。

## 5. 认证与权限

### 5.1 无状态 JWT 的“禁用不生效”

**现象**：禁用用户/改密/降级后，旧 token 仍可用到过期。  
**修复**：`sys_user.token_version` + JWT `ver` claim；每请求校验用户 ACTIVE 与版本（10 秒缓存）；登出/禁用/改密/角色变更都 +1。  
**验证**：登出、禁用、改密后旧 token 立即 401。

### 5.2 反向代理后审计 IP 全是网关地址

**现象**：审计 `ip=0:0:0:0:0:0:0:1` 或 Docker 网关。  
**修复**：统一 `RequestIps.resolve()` 读取 `X-Forwarded-For` 首个地址 / `X-Real-IP`。  
**注意**：这些头可伪造，只在可信代理覆盖后使用；不能当安全边界。

### 5.3 `/actuator/health/liveness` 被安全策略拦截

**现象**：探针子路径返回 401。  
**修复**：`permitAll` 增加 `/actuator/health/**`。  
**验证**：liveness/readiness 均返回 `{"status":"UP"}`。

## 6. 可观测性

### 6.1 Alertmanager 不支持环境变量展开

**现象**：配置里写 `${ALERT_WEBHOOK_URL}`，启动报 `unsupported scheme ""`。  
**修复**：`entrypoint.sh` 用 `sed` 把占位符替换成环境变量值，再启动 Alertmanager。

### 6.2 Prometheus 抓取需要内部令牌

**做法**：`/actuator/prometheus` 要求 `X-Internal-Token`；Prometheus 启动脚本把令牌注入临时配置，令牌不落仓库。  
**验证**：`/api/v1/targets` 中 `coherer-backend` 为 `up`。

### 6.3 Grafana 整页截图出现面板重叠假象

**现象**：`fullPage` 截图里面板互相覆盖。  
**原因**：整页截图改变视口高度，React Grid 重排尚未完成。  
**修复**：用视口截图；或截图前等待并检查 `.react-grid-layout` 的 `scrollWidth == clientWidth`。

### 6.4 Grafana Zipkin 数据源健康检查报 `Plugin unavailable`

**现象**：`/api/datasources/uid/coherer-zipkin/health` 返回 500。  
**实际**：通过 Grafana 代理查询 `/api/v2/services` 与 `/api/v2/trace/{id}` 正常。  
**结论**：这是 Zipkin 插件健康检查的已知限制，不影响 Explore 与调用链跳转。

## 7. 前端

### 7.1 表格新增操作列后横向溢出

**现象**：设备列表出现横向滚动，操作按钮被截断。  
**原因**：固定列宽总和超过容器宽度。  
**修复**：按容器预算收窄列宽、移除装饰性箭头列；用 `wrap.scrollWidth == clientWidth` 验证。

### 7.2 后端 Long 字段序列化成字符串，前端 `new Date` 报 Invalid Date

**现象**：事件记录接口有数据，但表格空白，控制台抛 `RangeError: Invalid time value`。  
**原因**：全局 Jackson 把 `Long` 序列化为字符串，事件 `ts` 是 `"1794655001109"`；`new Date("1794...")` 在浏览器里可能判为 Invalid Date。  
**修复**：前端先 `Number(row.ts)` 再构造 Date，类型声明改为 `ts: string`。

### 7.3 受限用户仍能选中“全部空间”

**现象**：空间受限账号切到全部空间，接口 403 或看到空数据。  
**修复**：空间树接口只返回可见子树；前端在 `ensureLoaded` 后校正失效选择，登录后拉 `/auth/me` 同步最新数据范围。

## 8. 命令下发

### 8.1 回执先到，状态被覆盖回 SENT

**原因**：`save(PENDING) → publish → updateById(SENT)`，设备回执可能在中间把状态改成 SUCCESS。  
**修复**：改为条件更新 `where status=PENDING`；影响 0 行时重新读取数据库，不覆盖回执结果。

### 8.3 命令缺少物模型校验/幂等/限流

**现象**：任意 identifier 都能下发，重复点击会重复执行，单设备可被刷。  
**修复**：按产品 `SERVICE` 定义校验 identifier 与参数类型；`clientRequestId` + 唯一索引实现幂等；Redis 计数 30 次/分钟限流。

### 8.4 TDengine 增加 tag 后旧子表写入失败

**现象**：`ALTER STABLE ADD TAG space_id` 后，旧子表标签数量不一致。  
**修复**：升级时按设备 `ALTER TABLE ... SET TAG space_id = ?` 回填；新写入统一带 4 个 TAG。  
**验证**：`DESCRIBE` 显示 space_id TAG，查询 `select distinct space_id` 有值。

### 8.2 PENDING 永久卡死

**原因**：进程在保存后、发布前崩溃；超时巡检只处理 SENT。  
**修复**：巡检同时处理超时 PENDING（按 `created_at`）。

## 9. 边缘主机

### 9.1 主机子设备主题层级数错一位

**现象**：主机模拟器能连上、能收到主机命令，但不回执，命令最终 TIMEOUT。  
**原因**：主题 `tenant/hostId/sub/deviceId/thing/service/reboot` 是 7 段，模拟器按 6 段判断直接 return。  
**修复**：长度改为 7，deviceId 取 `levels[3]`，identifier 取 `levels[6]` 或 payload。

### 9.2 离线标记没有 TTL，不能用“key 是否存在”判断在线

**现象**：设备/主机上报 offline 后 Redis 仍保留 `offline` key；后续心跳用 `expire` 或巡检用 `hasKey` 会误判为在线。  
**修复**：统一判断 key 的**值是否为 `online`**，只有 online 才续期；离线标记只作展示，不参与存活判断。  
**验证**：手动删除在线 key 后，主机巡检在下一轮把 ONLINE 置为 OFFLINE，并级联子设备离线。

### 9.3 主机代理子设备数据必须再校验绑定关系

**风险**：ACL 只限制主机主题前缀，主机仍可能替“未绑定”的设备上报数据。  
**修复**：进入遥测/事件/影子/状态处理前，校验 `device.host_id == host.id` 且租户一致；越权计入 `coherer.mqtt.hosted_rejected`。  
**验证**：主机替未绑定设备发遥测被拒，替已绑定设备发遥测/事件正常入库。

### 9.4 主机不能只绑定不接受 ACL 校验

**做法**：主机 clientId 以 `host_` 开头，认证查 `iot_host`；ACL 仅允许 `{tenant}/{hostId}/host/#` 与 `{tenant}/{hostId}/sub/#`。  
**验证**：主机可订阅子设备命令、发布回执；跨主机前缀被拒绝。

## 10. TLS 与备份

### 10.1 PostgreSQL 私钥权限

**现象**：Postgres 报 `private key file has group or world access`。  
**修复**：容器启动时把证书复制到 `/tmp`，`chown postgres:postgres` 后 `chmod 600`，再启动 postgres。  
**验证**：`pg_stat_ssl` 中后端连接 `ssl=t`。

### 10.2 备份校验和不能包含目录

**现象**：`sha256sum ./*` 对 `tdengine/` 目录报错，导致脚本中断。  
**修复**：`find . -maxdepth 1 -type f -exec sha256sum {} + | sort > SHA256SUMS`。

## 11. OTA 固件升级

### 11.1 Nginx 默认 1MB 请求体拦截固件上传

**现象**：3MB 固件上传返回 Nginx HTML 错误页，后端无日志；`curl` 报 HTTP 413/000。
**原因**：Nginx `client_max_body_size` 默认 `1m`，请求还没到 Spring Boot 就被拒绝。
**修复**：`frontend/nginx.conf` 的 HTTPS server 增加 `client_max_body_size 66m`，并把
`proxy_read_timeout/proxy_send_timeout` 提到 120s，与后端 `spring.servlet.multipart` 上限匹配。
**验证**：3MB 固件经 8443 上传返回 200，`firmware.sha256` 与 `sha256sum` 一致。

### 11.2 MQTT 副作用不能包在数据库事务里

**现象**：设备收到 OTA 指令后立刻回报 5%，平台却查不到任务；极端情况下 MQTT 已下发，接口事务回滚，
平台没有任务而设备已经开始升级。
**原因**：`ota_task` 插入未提交，设备进度先到；或发布 MQTT 成功后后续 DB 操作失败触发回滚。
**修复**：任务行先提交，再生成签名 URL 并发布 MQTT；下发/主机校验失败把任务置 `FAILED`。
`PENDING` 也纳入超时巡检，防止任务永久卡住。
**验证**：下载进度从 5% 开始完整落库；停止 EMQX 后任务稳定置 `FAILED`。

### 11.3 设备下载不能用用户 JWT

**现象**：设备端没有用户登录态，带 Bearer Token 下载不现实。
**修复**：平台生成 HMAC 签名 URL（`firmwareId|deviceId|expiresAt`），10 分钟有效；
下载时校验签名、过期时间、设备与固件产品匹配、设备未禁用，签名比较用 `MessageDigest.isEqual`。
**验证**：错误签名返回 403；过期 URL 返回 403；正确 URL 经 HTTPS 下载成功。

### 11.4 固件存储目录权限

**现象**：容器内上传固件报 `Permission denied`。
**原因**：宿主机 bind mount 目录属主是 root，而镜像以 `coherer`（uid 10001）运行。
**修复**：改用命名卷 `firmware-data:/app/firmware`，并在 Dockerfile 创建目录后 `chown coherer`。
**验证**：`docker exec coherer-backend ls -ld /app/firmware` 属主为 `coherer`，上传成功。

### 11.5 Long 序列化为字符串导致前端类型不符

**现象**：`sizeBytes` 实际返回 `"3145728"`，前端声明为 `number`。
**原因**：项目为保住雪花 ID 精度，把所有 `Long` 统一序列化为字符串，`sizeBytes` 也受影响。
**修复**：前端类型改 `number | string`，展示前 `Number(value)`；不要对 ID 做数值运算。
**验证**：上传 3MB 固件前端显示 `3.00 MB`。

### 11.6 MyBatis-Plus 3.5.18 的 `updateById` 重载歧义

**现象**：测试里 `verify(mapper, never()).updateById(any())` 编译报「引用不明确」。
**原因**：`BaseMapper` 同时有 `updateById(T)` 与 `updateById(Collection<T>)`。
**修复**：显式 `any(OtaTask.class)`。
**验证**：`OtaProgressServiceTest` 编译并通过。

### 11.7 `updateById` 不写 null，恢复后暂停原因残留

**现象**：灰度批次恢复为 RUNNING 后，接口仍返回上一次的 `pauseReason`。
**原因**：MyBatis-Plus `updateById` 默认只更新非 null 字段，`setPauseReason(null)` 不会生成 SQL。
**修复**：恢复时改用 `lambdaUpdate().set(OtaRollout::getPauseReason, null)` 显式清空。
**验证**：暂停 → 恢复后 `pauseReason` 为 null，批次继续推进到下一批。

### 11.8 灰度调度崩溃后不能按 currentBatch+1 推进

**现象**：调度器写入 `current_batch=1` 后、设备任务全部创建前进程退出，重启后可能直接跳到第 2 批。
**原因**：批次号只由 `current_batch + 1` 推导，已写入的批次状态与成员实际状态不一致。
**修复**：从 `ota_rollout_device` 查最小的 `status=PENDING` 批次号再补发；同一 `(rollout_id, device_id, batch_no)`
的进行中任务按幂等复用，靠设备任务表唯一约束兜底。
**验证**：单测覆盖批次推进；真实灰度中第 1/2 批按顺序完成。

## 12. 提交索引

| 提交 | 内容 |
|---|---|
| `d91d466` | 命令状态竞态与 PENDING 超时 |
| `6fc0771` | JWT 令牌版本与即时吊销 |
| `4ba8ab7` | Redis 故障不阻塞遥测 |
| `abfa066` | 平台级配置与空间写边界 |
| `8c01d8a` | 空间树与 Dashboard 缓存 |
| `0693bf3` | REST 数据权限 |
| `dd69002` | 端口收敛与基础设施口令 |
| `22e3074` | 在线状态 TTL |
| `b21c86a` | 遥测幂等与批量写入 |
| `0c97441` | 告警状态 Redis |
| `0eae769` | Zipkin / Alertmanager / 探针 |
| `705d67d` | 审计字段与 CI |
| `9f1f8a7` | 设备生命周期 |
| `d7b76f3` | 用户管理 |
| `b78489e` | 前端 Vitest |
| `4306579` | 命令下发 |
| `26afbaf` | 审计页面 |
| `a170681` | Compose 启动顺序/内部地址/健康检查 |
| `16f1de3` | 命令物模型校验/幂等/限流 |
| `e19db8d` | Refresh Token 轮换/重放拒绝 |
| `d493974` | 告警活动集合批量读取 |
| `f1f42a7` | TDengine space_id tag/保留期 |
| `042c79d` | 设备事件上报 |

## 13. 仍建议优先处理

1. ~~写入前校验遥测 payload 长度，避免 TDengine 截断~~（已完成）
2. ~~命令 identifier 按物模型 SERVICE 校验，并加幂等键/发送限流~~（已完成）
3. 调度线程池显式配置，遥测 flush 与巡检隔离
4. ~~后端/前端镜像与 Nginx 同源部署~~（已完成并验证）
5. ~~CI 镜像构建与推送~~（已加入 GHCR job，需推送到 GitHub 后验证）
6. Testcontainers 集成测试与 E2E
7. OTA 批量灰度、失败重试与版本回退
