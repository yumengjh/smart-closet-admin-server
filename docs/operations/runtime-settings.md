# 运行时服务配置

运行时配置只管理后端服务治理，不管理业务开关。数据库连接、JWT 密钥、可信代理边界和初始管理员密码属于启动配置，修改后必须重启。CORS 来源和允许方法属于本模块的热更新策略。

## 已实现接口

- `GET /api/v1/system/runtime-config`：读取当前配置。
- `PUT /api/v1/system/runtime-config/{key}`：更新配置，请求体为 `{"value":"..."}`。
- `GET /api/v1/system/audit-events?page=0&size=20`：分页读取系统审计事件。

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

服务启动时会为缺失项写入默认值，并将配置缓存为内存快照。数据库事务成功提交后才会原子切换快照，回滚不会产生仅在当前进程生效的“幽灵配置”。通用 API 限流按客户端 IP 使用一个全局桶，超过阈值返回 `429 RATE_LIMITED`；匹配接口级模式的请求还会进入独立的“IP + 路径”桶，因此同时受全局和端点容量约束。例如可对 `/api/v1/auth/login` 配置更严格的窗口。

字符串配置用于 CORS、Ant 路径模式和 IP 策略。空字符串表示清空该字符串策略，例如撤销全部接口禁用规则；请求体仍必须包含 `value` 字段。IP 白名单非空时仅匹配的来源可访问；黑名单优先级更高。两者支持逗号分隔的 IPv4、IPv6 或 CIDR，例如 `192.168.10.0/24`。运行时配置接口不会被接口禁用策略或 IP 策略锁死。

登录防爆破已实现：在 `security.brute-force.window-seconds` 观察窗口内达到失败阈值会锁定账户，锁定时长由 `security.brute-force.lock-seconds` 控制；锁定会撤销该用户 Refresh Token，到期后下次登录自动恢复。`security.request-log.enabled` 控制 `/api/**` 的结构化请求日志（方法、路径、状态、耗时、Trace ID、已认证用户 ID），绝不记录密码、令牌或请求体。限流计数和防爆破状态目前是单实例内存状态；多实例部署时应以 Redis 等共享存储替换，不能把当前实现当作分布式限流。

为避免唯一管理入口被误锁，`SUPER_ADMIN` 不会被自动防爆破策略锁定；普通管理员和用户仍受该策略约束。

`security.cors.allowed-origins` 的新数据库默认值为空。`APP_SECURITY_CORS_ALLOWED_ORIGINS` 只负责初始化缺失项；数据库已有该键后，后续修改仍通过运行时配置 API 完成。`postgres` Profile 拒绝值为 `*` 的 credentialed CORS。

`identity.registration.enabled` 仅影响 `POST /api/v1/auth/register` 是否允许自助注册；它不控制任何具体业务模块的开关。数据库连接、JWT 密钥、可信代理和初始管理员密码依然只能通过启动配置或环境变量修改。

## 持久化 IP 规则

- `GET /api/v1/system/ip-access-rules`：查看 API 范围规则，需 `system:config:read`。
- `POST /api/v1/system/ip-access-rules`：创建规则，需 `system:config:write`。
- `DELETE /api/v1/system/ip-access-rules/{id}`：删除规则，需 `system:config:write`。

创建示例：`{"type":"DENY","network":"203.0.113.0/24","expiresAt":1783814400000,"reason":"temporary abuse block"}`。`expiresAt` 可省略表示永久；`ALLOW` 规则存在时，只有命中任一有效 allow 规则的客户端可访问 API，deny 规则优先。规则变更会写审计记录。

服务默认使用直接 TCP 来源。只有直接来源命中启动期 `APP_SECURITY_TRUSTED_PROXIES`（逗号分隔 IP/CIDR）时，才解析 `X-Forwarded-For`；不可信客户端伪造该请求头不会影响 IP 策略或限流。
