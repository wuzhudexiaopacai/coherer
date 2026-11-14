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

### 1.8 Git Bash 里 curl 发送中文 JSON 变成非法 UTF-8

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

### 4.3 Docker 内 taosAdapter 认证

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

## 9. 提交索引

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

## 10. 仍建议优先处理

1. 写入前校验遥测 payload 长度，避免 TDengine 截断
2. 命令 identifier 按物模型 SERVICE 校验，并加幂等键/发送限流
3. 调度线程池显式配置，遥测 flush 与巡检隔离
4. ~~后端/前端镜像与 Nginx 同源部署~~（已完成并验证）
5. ~~CI 镜像构建与推送~~（已加入 GHCR job，需推送到 GitHub 后验证）
5. Testcontainers 集成测试与 E2E
