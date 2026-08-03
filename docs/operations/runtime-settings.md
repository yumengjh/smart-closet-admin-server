# 运行时服务配置

运行时配置只管理后端服务治理，不管理业务开关。数据库连接、JWT 密钥、可信代理边界和初始管理员密码属于启动配置，修改后必须重启。CORS 来源和允许方法属于本模块的热更新策略。

## 已实现接口

- `GET /api/v1/system/runtime-config`：读取当前配置。
- `PUT /api/v1/system/runtime-config/{key}`：更新配置，请求体为 `{"value":"..."}`。
- `GET /api/v1/system/runtime-caches`：读取全部配置缓存的 revision、同步状态和最近加载结果。
- `POST /api/v1/system/runtime-caches/refresh`：从数据库强制重新加载全部配置缓存。
- `POST /api/v1/system/runtime-caches/{code}/refresh`：强制重新加载指定配置缓存。
- `GET /api/v1/system/runtime-checks`：读取服务依赖和本地资源的运行状态快照。
- `POST /api/v1/system/runtime-checks/refresh`：执行全部运行状态检查。
- `POST /api/v1/system/runtime-checks/{code}/refresh`：重新执行单项运行状态检查。
- `GET /api/v1/system/audit-events?page=0&size=20`：分页读取系统审计事件。

旧 `/api/v1/system/dependencies/**` 路径仅作为已废弃的兼容别名保留，新接入统一使用 `/api/v1/system/runtime-checks/**`。

读取接口需要 `system:config:read`，更新接口需要 `system:config:write`；审计查询需要 `system:audit:read`。运行时配置更新会立即记入审计事件。

## 内置键

- `security.rate-limit.enabled`
- `security.rate-limit.capacity`
- `security.rate-limit.window-seconds`
- `security.endpoint.rate-limit.patterns`
- `security.endpoint.rate-limit.capacity`
- `security.endpoint.rate-limit.window-seconds`
- `security.brute-force.enabled`
- `security.brute-force.failure-threshold`
- `security.brute-force.window-seconds`
- `security.brute-force.lock-seconds`
- `security.request-log.enabled`
- `security.audit.enabled`
- `security.cors.allowed-origins`
- `security.cors.allowed-methods`
- `security.endpoint.disabled-patterns`
- `security.ip.allow-list`
- `security.ip.deny-list`
- `identity.registration.enabled`
- `runtime.config-refresh.enabled`
- `runtime.config-refresh.interval-seconds`
- `runtime.health-check.enabled`
- `runtime.health-check.interval-seconds`
- `avatar.generator.enabled`
- `maintenance.gc.enabled`
- `maintenance.gc.schedule.enabled`
- `maintenance.gc.interval-minutes`
- `maintenance.gc.max-run-seconds`

服务启动时会为缺失项写入默认值，并将配置缓存为内存快照。业务请求只读取该快照，不会逐次查询配置表。配置写事务同时递增 `runtime_cache_revisions` 中对应缓存的 revision；事务成功提交后才会原子切换本机快照，回滚不会产生仅在当前进程生效的“幽灵配置”。自动任务只查询各缓存的一行 revision，发生变化才全量加载；管理员也可手动刷新全部或单个缓存。首批统一管理 `RUNTIME_SETTINGS` 与 `IP_ACCESS_RULES`。

`runtime.config-refresh.enabled` 控制自动 revision 对账，`runtime.config-refresh.interval-seconds` 可设为 10–3600 秒。关闭它不影响写接口提交后立即发布本机快照，也不影响手动刷新，但其它实例不会自动发现数据库变化。直接绕过应用修改业务表不会递增 revision，应在修改后手动刷新，或同步维护对应 revision。通用 API 限流按客户端 IP 使用一个全局桶，超过阈值返回 `429 RATE_LIMITED`；匹配接口级模式的请求还会进入独立的“IP + 路径”桶，因此同时受全局和端点容量约束。例如可对 `/api/v1/auth/login` 配置更严格的窗口。

字符串配置用于 CORS、Ant 路径模式和 IP 策略。空字符串表示清空该字符串策略，例如撤销全部接口禁用规则；请求体仍必须包含 `value` 字段。IP 白名单非空时仅匹配的来源可访问；黑名单优先级更高。两者支持逗号分隔的 IPv4、IPv6 或 CIDR，例如 `192.168.10.0/24`。运行时配置和配置缓存恢复接口不会被接口禁用策略或 IP 策略锁死，但仍要求对应 RBAC 权限。

登录防爆破已实现：在 `security.brute-force.window-seconds` 观察窗口内达到失败阈值会锁定账户，锁定时长由 `security.brute-force.lock-seconds` 控制；锁定会撤销该用户 Refresh Token，到期后下次登录自动恢复。`security.request-log.enabled` 控制 `/api/**` 的结构化请求日志（方法、路径、状态、耗时、Trace ID、已认证用户 ID），绝不记录密码、令牌或请求体。限流计数和防爆破状态目前是单实例内存状态；多实例部署时应以 Redis 等共享存储替换，不能把当前实现当作分布式限流。

为避免唯一管理入口被误锁，`SUPER_ADMIN` 不会被自动防爆破策略锁定；普通管理员和用户仍受该策略约束。

## 运行状态检查与头像生成

第一版注册两项运行状态检查：`AVATAR_GENERATOR`（自托管 DiceBear）属于 `SERVICE_DEPENDENCY`，`LOCAL_STORAGE`（应用进程直接使用的本地通用存储）属于 `LOCAL_RESOURCE`。后者不是外部服务，其检查会真实执行目录创建、临时文件写入/读回/删除及磁盘容量读取。状态为 `UNKNOWN`、`UP`、`ERROR` 或 `DISABLED`，同时记录分类、上次检查/成功时间、耗时、连续失败数和脱敏消息。状态写入 `runtime_component_statuses`，请求路径读取统一的不可变内存快照；刷新成功后整体替换，避免一次请求混用新旧状态。该状态模型与配置缓存刷新相互独立。

`runtime.health-check.enabled` 控制定时检查，`runtime.health-check.interval-seconds` 可设为 10–3600 秒。关闭定时检查不删除已有状态，管理员仍可手动执行。`avatar.generator.enabled=false` 时头像生成依赖显示为 `DISABLED`，禁止新建系统头像候选；已有缓存头像、用户上传头像和内置最终回退仍可读取。

应用 Ready 后会执行全部运行状态检查，并在启动摘要中各显示一次状态。生成头像前以后端内存中的最新 DiceBear 状态为准；DiceBear 不可用时不调用公共互联网服务。默认读取会优先使用本地缓存，无法生成或存储时返回 classpath 内置 Lorelei SVG，因此登录和用户资料读取不会被可选生成器拖垮。

管理后台在 `/system/config` 以表格管理配置缓存；Actuator 应用健康、DiceBear 服务依赖和本地存储资源检查统一显示在 `/welcome` 的“服务健康”列表，避免在配置页重复展示运行状态。

启动期配置来自环境变量，修改后需重启：

| 环境变量 | 默认值 | 说明 |
| --- | --- | --- |
| `APP_AVATAR_GENERATOR_BASE_URL` | `http://localhost:3100` | 自托管 DiceBear 根地址；Compose 默认使用 `http://host.docker.internal:3100` |
| `APP_AVATAR_GENERATOR_CONNECT_TIMEOUT` | `500ms` | 连接超时 |
| `APP_AVATAR_GENERATOR_READ_TIMEOUT` | `1500ms` | 响应读取超时 |
| `APP_AVATAR_GENERATOR_MAX_RESPONSE_BYTES` | `262144` | SVG 最大响应字节数 |
| `APP_STORAGE_LOCAL_ROOT` | `${APP_DATA_DIR}/storage` | 通用本地存储根目录 |

DiceBear 客户端只访问固定 `/10.x/lorelei/svg` 路由，seed 由后端产生且不出现在公开 URL、响应或日志中。返回 SVG 会校验媒体类型、大小，并拒绝脚本、事件处理器和外部引用。

`security.cors.allowed-origins` 的新数据库默认值为空。`APP_SECURITY_CORS_ALLOWED_ORIGINS` 只负责初始化缺失项；数据库已有该键后，后续修改仍通过运行时配置 API 完成。`postgres` Profile 拒绝值为 `*` 的 credentialed CORS。

`identity.registration.enabled` 仅影响 `POST /api/v1/auth/register` 是否允许自助注册；它不控制任何具体业务模块的开关。数据库连接、JWT 密钥、可信代理和初始管理员密码依然只能通过启动配置或环境变量修改。

## 持久化 IP 规则

持久化规则为什么使用独立 `IP_ACCESS_RULES` 缓存、它与运行时字符串 IP 策略的区别，以及 revision/过期规则的处理方式，参见[《为什么 IP 访问规则使用独立缓存》](ip-access-rule-cache.md)。

- `GET /api/v1/system/ip-access-rules`：查看 API 范围规则，需 `system:config:read`。
- `POST /api/v1/system/ip-access-rules`：创建规则，需 `system:config:write`。
- `DELETE /api/v1/system/ip-access-rules/{id}`：删除规则，需 `system:config:write`。

创建示例：`{"type":"DENY","network":"203.0.113.0/24","expiresAt":1783814400000,"reason":"temporary abuse block"}`。`expiresAt` 可省略表示永久；`ALLOW` 规则存在时，只有命中任一有效 allow 规则的客户端可访问 API，deny 规则优先。规则变更会写审计记录。

服务默认使用直接 TCP 来源。只有直接来源命中启动期 `APP_SECURITY_TRUSTED_PROXIES`（逗号分隔 IP/CIDR）时，才解析 `X-Forwarded-For`；不可信客户端伪造该请求头不会影响 IP 策略或限流。
