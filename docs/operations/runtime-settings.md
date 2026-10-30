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
- `security.control-plane.rate-limit.capacity`
- `security.control-plane.rate-limit.window-seconds`
- `security.ip-auto-ban.enabled`
- `security.ip-auto-ban.rejection-threshold`
- `security.ip-auto-ban.window-seconds`
- `security.ip-auto-ban.duration-seconds`
- `security.brute-force.enabled`
- `security.brute-force.failure-threshold`
- `security.brute-force.window-seconds`
- `security.brute-force.lock-seconds`
- `security.request-log.enabled`
- `security.audit.enabled`
- `security.cors.allowed-origins`
- `security.cors.allowed-methods`
- `security.endpoint.disabled-patterns`
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

`GET /runtime-config` 同时返回 `group`、整数上下界和 `dangerous` 元数据，后台不再维护一份容易遗漏的配置键白名单。`runtime.config-refresh.enabled` 控制全部 Provider 的自动 revision 对账，`runtime.config-refresh.interval-seconds` 可设为 10–3600 秒。即使把自动刷新关闭，调度器仍按心跳只检查 `RUNTIME_SETTINGS` 的 revision，以便其它实例能发现“重新开启自动刷新”这项修改；关闭期间不会自动重载其它 Provider。写接口提交后仍立即发布本机快照，管理员也始终可以手动刷新。直接绕过应用修改业务表不会递增 revision，应在修改后手动刷新，或同步维护对应 revision。

通用 API 限流按客户端 IP 使用一个全局桶。匹配端点模式的请求同时使用独立的“IP + 匹配模式”桶，同一动态路径不会因具体 ID 不同而绕过统计。运行时配置、缓存、运行检查和 IP 规则管理走容量更大的控制面桶，避免误配置后无法恢复。超过阈值返回 `429 RATE_LIMITED`，并携带 `Retry-After`、`X-RateLimit-Limit`、`X-RateLimit-Remaining` 与 `X-RateLimit-Reset`。

全局或端点连续收到 429 不会立刻永久拉黑。启用 `security.ip-auto-ban.enabled` 后，同一 IP 在观察窗口内累计达到 `security.ip-auto-ban.rejection-threshold` 个 429 才创建一条有期限的 `AUTO_RATE_LIMIT` 拒绝规则，时长由 `security.ip-auto-ban.duration-seconds` 决定。控制面 429 不参与自动升级。回环、任意本地和组播地址不会自动封禁，避免本机开发或同机反向代理把管理入口锁死；生产部署应正确配置可信代理，确保解析到真实客户端 IP。自动封禁持久化失败不会把原本的 429 替换成 500。

本地浏览器经 8849 的 Vite 代理访问 8081 时，后端通常只看到回环来源；配置页又使用不参与自动升级的控制面桶。因此本地出现 429 但规则页没有自动记录是预期行为。完整请求顺序、规则优先级、默认阈值、安全测试步骤和部署检查参见[《IP 访问控制与自动安全防护》](ip-security-protection.md)。

字符串配置只用于 CORS 和 Ant 路径模式。路径模式与 IP 规则、限流、CORS 路由一样，按与 Spring MVC 一致的规范化路径（百分号解码、去除分号内容与重复斜杠后的应用内路径）匹配，`/%61pi/v1/auth/login` 与 `/api/v1/auth/login` 视为同一路径；请求日志同时记录规范化路径和不同于它的原始路径。空字符串表示清空相应策略，例如撤销全部接口禁用规则；请求体仍必须包含 `value` 字段。旧 `security.ip.allow-list` / `security.ip.deny-list` 会在升级启动时迁移为结构化 IP 规则并删除，不再参与请求判断。运行时配置、缓存和 IP 规则恢复接口不会被 IP 规则锁死，但仍要求对应 RBAC 权限并受独立控制面限流保护。

登录防爆破已实现：在 `security.brute-force.window-seconds` 观察窗口内达到失败阈值会锁定账户，锁定时长由 `security.brute-force.lock-seconds` 控制；锁定会撤销该用户 Refresh Token，到期后下次登录自动恢复。超级管理员不改账号状态（避免锁死最后一名管理员），改用同样时长的账号级退避：达到阈值后在 `lock-seconds` 内直接返回 `423 ACCOUNT_LOCKED`、不再校验密码，并记录 `LOGIN_FAILED`（reason=throttled）审计；已有会话不受影响。恢复途径为等待到期、其他管理员把该账号状态设为 `ACTIVE`（会清除退避并留下审计），或重启后用 Bootstrap 管理员重置。该退避与失败计数同为单实例内存状态，重启即清空。`security.request-log.enabled` 控制 `/api/**` 的结构化请求日志（方法、路径、状态、耗时、Trace ID、客户端 IP、已认证用户 ID），绝不记录密码、令牌或请求体。限流、自动封禁触发计数和防爆破状态均使用有容量上限的 Caffeine 单实例缓存，避免恶意高基数 key 无界占用堆内存；真正的封禁规则会持久化。多实例部署仍应以 Redis 等共享存储替换计数器，否则每个实例分别计数，达到阈值的速度取决于流量分配。

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

完整安全行为与测试方式参见[《IP 访问控制与自动安全防护》](ip-security-protection.md)；结构化规则为什么使用独立 `IP_ACCESS_RULES` Provider，以及 revision、自动封禁和历史规则的处理方式，参见[《为什么 IP 访问规则使用独立缓存》](ip-access-rule-cache.md)。

- `GET /api/v1/system/ip-access-rules`：查看 API 范围规则，需 `system:config:read`。
- `POST /api/v1/system/ip-access-rules`：创建规则，需 `system:config:write`。
- `DELETE /api/v1/system/ip-access-rules/{id}`：撤销规则，需 `system:config:write`；记录仍保留用于审计和统计。

创建示例：`{"type":"DENY","network":"203.0.113.0/24","expiresAt":1783814400000,"reason":"temporary abuse block"}`。`expiresAt` 可省略表示永久。人工/迁移 deny 优先于 allow；显式 allow 可放行自动 deny；未显式放行时自动 deny 生效；存在任意有效 allow 时，未命中的地址默认拒绝。列表包含 `MANUAL`、`MIGRATED`、`AUTO_RATE_LIMIT` 来源以及 `ACTIVE`、`EXPIRED`、`REVOKED` 状态、违规次数和触发时间。规则变更会写审计记录。

服务默认使用直接 TCP 来源。只有直接来源命中启动期 `APP_SECURITY_TRUSTED_PROXIES`（逗号分隔 IP/CIDR）时，才解析 `X-Forwarded-For`；不可信客户端伪造该请求头不会影响 IP 策略或限流。
