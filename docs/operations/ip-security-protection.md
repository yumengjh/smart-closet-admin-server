# IP 访问控制与自动安全防护

本文是 IP 相关安全能力的统一运维说明。它回答三个问题：一次请求会经过哪些保护、什么时候会从 429 升级为自动封禁，以及为什么在本地 8849/8081 反复刷新通常看不到自动封禁记录。

## 请求处理顺序

对 `/api/**` 的非 `OPTIONS` 请求，核心链路是：

```text
解析客户端 IP
  → 接口启停策略
  → 结构化 IP ALLOW/DENY 规则
  → 全局/端点/控制面限流
  → JWT 认证与 RBAC
  → Controller
```

`@PublicApi` 只跳过身份认证，不会跳过 IP 规则、接口禁用或限流。非 `/api/**` 路径和 CORS `OPTIONS` 不进入 IP 规则与限流过滤器。

## 能力边界

| 能力 | 统计或判断维度 | 触发结果 | 是否持久化 |
| --- | --- | --- | --- |
| 结构化 IP 规则 | 解析后的客户端 IP/CIDR | `403 IP_ACCESS_DENIED` | 是 |
| 全局限流 | 客户端 IP | `429 RATE_LIMITED` | 计数否 |
| 端点限流 | 客户端 IP + 匹配到的路径模式 | `429 RATE_LIMITED` | 计数否 |
| 控制面限流 | 客户端 IP | `429 RATE_LIMITED` | 计数否 |
| 自动临时封禁 | 观察窗口内的全局/端点 429 次数 | 创建 `AUTO_RATE_LIMIT` DENY，后续普通请求 403 | 规则是 |
| 登录防爆破 | 用户名 | 临时锁定账号、撤销 Refresh Token | 账号锁定是 |

登录防爆破目前不是 IP 封禁。它按用户名统计失败，`SUPER_ADMIN` 不会被自动锁定；普通管理员和用户仍受保护。预留的 `AUTO_LOGIN_ABUSE` 规则来源尚未接入实际触发链路。

登录防爆破由以下键控制：`security.brute-force.enabled`、`security.brute-force.failure-threshold`、`security.brute-force.window-seconds` 和 `security.brute-force.lock-seconds`。

## 客户端 IP 与可信代理

默认使用 TCP 直接来源 `request.remoteAddr`。只有直接来源匹配启动变量 `APP_SECURITY_TRUSTED_PROXIES` 中的 IP/CIDR 时，系统才读取 `X-Forwarded-For`，并从右向左选择第一个不受信任的地址。

这条边界同时供 IP 规则、限流、自动封禁和请求日志使用。不要把 `0.0.0.0/0` 或 `::/0` 配成可信代理，否则客户端可以伪造来源地址。修改可信代理需要重启。

## 结构化 IP 规则

后台 `/system/ip-rules` 是唯一管理入口，旧 `security.ip.allow-list` 与 `security.ip.deny-list` 会在升级时迁移为结构化规则并删除。

规则来源：

- `MANUAL`：管理员创建；
- `MIGRATED`：由旧字符串名单迁移；
- `AUTO_RATE_LIMIT`：全局或端点持续触发 429 后自动创建；
- `AUTO_LOGIN_ABUSE`：仅预留，当前不会自动生成。

规则状态为 `ACTIVE`、`EXPIRED` 或 `REVOKED`。管理端的“删除”实际是撤销，历史记录仍保留。判断优先级：

```text
人工/迁移 DENY
  > 显式 ALLOW
  > 自动 DENY
  > 存在有效 ALLOW 时的默认拒绝
  > 默认允许
```

显式 ALLOW 可以保护可信运维地址免受自动封禁，但不能绕过人工或迁移 DENY。规则到期按请求时间实时判断，不需要等待缓存刷新。

为防止误配置后彻底失去管理入口，以下路径绕过 IP 规则，但仍要求认证、RBAC，并受控制面限流：

- `/api/v1/system/runtime-config/**`
- `/api/v1/system/runtime-caches/**`
- `/api/v1/system/ip-access-rules/**`

## 三种限流桶

### 全局桶

普通 API 首先进入 `IP + global` 固定窗口桶。默认每个 IP 在 60 秒内允许 120 次，由以下键控制：

- `security.rate-limit.enabled`
- `security.rate-limit.capacity`
- `security.rate-limit.window-seconds`

### 端点桶

当请求路径匹配 `security.endpoint.rate-limit.patterns` 时，还会进入 `IP + 匹配模式` 桶。默认容量 20、窗口 60 秒。桶使用匹配到的模式而不是具体 URI，因此轮换资源 ID 不能绕开同类端点容量。

### 控制面桶

以下管理接口不与普通业务 API 共用全局桶，而使用默认 600 次/60 秒的独立桶：

- 运行时配置；
- 配置缓存；
- 运行状态检查；
- IP 规则管理。

控制面 429 **不会进入自动封禁升级**，防止攻击流量或错误配置把恢复通道变成持久 IP 拒绝。它仍会返回 429，认证与 RBAC 也仍然生效。

控制面容量由以下键控制：

- `security.control-plane.rate-limit.capacity`
- `security.control-plane.rate-limit.window-seconds`

任一限流桶拒绝时响应包含：

```http
HTTP/1.1 429
Retry-After: 60
X-RateLimit-Limit: 20
X-RateLimit-Remaining: 0
X-RateLimit-Reset: 1786285433
```

`Retry-After` 是至少等待秒数，`X-RateLimit-Reset` 是 Unix 秒时间戳。

## 自动临时封禁

自动封禁统计的是已经返回的 **429 拒绝次数**，不是总请求次数。默认流程是：

1. 请求先消耗全局或端点容量；
2. 超过容量的请求返回 429；
3. 同一 IP 在 300 秒观察窗口内累计 10 次 429；
4. 系统创建有效期 900 秒的 `AUTO_RATE_LIMIT` DENY；
5. 该 IP 后续访问普通 API 返回 `403 IP_ACCESS_DENIED`；
6. 到期后规则自动停止参与判断，历史仍可在规则页查看。

相关配置：

- `security.ip-auto-ban.enabled`
- `security.ip-auto-ban.rejection-threshold`
- `security.ip-auto-ban.window-seconds`
- `security.ip-auto-ban.duration-seconds`

回环地址、任意本地地址、组播地址和无法解析的地址不会进入自动封禁状态机。自动规则写库失败时，原请求仍返回 429，不会被替换为 500。

## 为什么本地 8849/8081 始终没有自动封禁

本地链路通常是：

```text
浏览器 → localhost:8849（Vite）→ localhost:8081（Spring Boot）
```

这会同时遇到以下情况：

1. 后端看到的 TCP 来源通常是 `127.0.0.1` 或 `::1`。
2. 默认未配置 `APP_SECURITY_TRUSTED_PROXIES`，所以手工伪造 `X-Forwarded-For` 会被忽略。
3. 回环地址可以触发 429，但被明确排除在自动封禁之外，避免开发机自锁。
4. 配置页刷新调用的是控制面接口，默认阈值是 600 次/60 秒，不是端点阈值 20。
5. 即使控制面达到 429，其拒绝也不会升级成自动 IP 规则。
6. 普通端点达到容量只是开始产生 429；还必须在观察窗口内继续产生足够数量的 429，才会创建规则。

因此“接口已返回 429，但 IP 规则页没有自动记录”在本地是预期结果。不要通过删除回环保护来让演示看起来生效。

2026-08-09 对当前 8081 的实际验证结果：端点容量 20、自动阈值 10，连续 30 次请求得到 20 个 401 和 10 个 429，429 响应头完整，数据库自动规则数始终为 0；客户端解析结果是回环地址。

## 安全测试方法

### 自动化回归

```bash
./mvnw -Dtest=IpNetworkMatcherTest,IpAccessRuleServiceTest,RateLimitServiceTest,RateLimitFilterTest,IpAbuseTrackerTest test
```

这些测试覆盖 CIDR、规则优先级、固定窗口、端点模式桶、429 响应头、自动升级阈值和回环豁免。

### 隔离或预发布端到端验证

不要直接降低生产阈值或封禁当前管理地址。应使用隔离数据库/实例，并保留独立恢复通道：

1. 只在隔离实例把本地反向代理地址加入 `APP_SECURITY_TRUSTED_PROXIES`。
2. 把测试端点容量临时设为 2、429 拒绝阈值设为 2、封禁时长设为 60 秒。
3. 由受信任代理传入测试网段地址，例如 `203.0.113.88`。
4. 对同一端点连续请求 4 次，期望 `401, 401, 429, 429`。
5. 再访问普通 API，期望 `403 IP_ACCESS_DENIED`。
6. 在 IP 规则页确认 `source=AUTO_RATE_LIMIT`、违规次数 2、触发类型和到期时间。
7. 验证完成后恢复配置并撤销测试规则。

2026-08-09 已按上述方式在 18081 隔离实例验证成功：人工 DENY 返回 403；自动测试序列为 `401,401,429,429`，随后普通 API 返回 403；数据库生成 `AUTO_RATE_LIMIT`、`RATE_LIMIT_ENDPOINT`、违规次数 2 且带 60 秒到期时间。测试使用临时 SQLite 副本，未修改开发数据库，隔离进程和临时目录均已清理。

## 可观测性与后台排查

- `/system/ip-rules`：查看人工、迁移、自动、有效、过期和撤销规则；
- `/system/config`：查看并修改全部限流、自动封禁和缓存刷新设置；
- `/system/audit`：规则创建、撤销和自动封禁会产生 `IP_ACCESS_RULE_CREATED`、`IP_ACCESS_RULE_REVOKED`、`IP_AUTO_BLOCKED` 审计动作；
- 应用日志：自动封禁输出 `ip_auto_blocked` WARN，普通请求日志包含解析后的 `clientIp`；
- 429 响应：通过 Trace ID、重试和额度头定位具体请求。

IP 拒绝和 429 会在认证前短路，不应只依赖 Controller 访问日志判断是否触发。自动规则的 `violationCount` 表示触发自动封禁时累计的 429 次数，不是人工规则的历史命中总数。

## 缓存与多实例边界

规则保存在数据库，并通过 `IP_ACCESS_RULES` revision 在实例间对账；请求只读不可变内存快照。限流、自动封禁触发计数和登录失败计数使用最多 100,000 个状态的 Caffeine 单机缓存，避免无界占用堆内存。

多实例会分别统计请求，只共享已经落库的规则。因此第一版不提供严格的集群级限流阈值。需要一致的集群计数时，应把计数器替换为 Redis 等共享原子存储，结构化规则与 revision 缓存可以继续沿用。

## 部署检查清单

- 只信任实际负载均衡器或反向代理的固定 IP/CIDR；
- 从真实代理链验证解析出的 `clientIp`，不要只在应用宿主机 curl；
- 先创建可信运维 ALLOW，再启用白名单模式；
- 人工 DENY 当前管理地址前准备另一条恢复路径；
- 根据 NAT/公司出口共享人数调整容量，避免多人共用一个 IP 时误伤；
- 多实例生产环境评估 Redis 共享计数器；
- 监控 429、`ip_auto_blocked` 日志、自动规则数量及规则持久化失败。
