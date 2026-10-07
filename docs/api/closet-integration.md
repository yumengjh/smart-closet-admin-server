# 鱼搭管理集成

此模块将本管理平台适配为鱼搭 `/api/manage/v1` 的 HMAC 客户端。开关默认为关闭；关闭时控制器、权限/菜单种子、RestClient 与运行检查均不注册。

上游契约与接入说明由鱼搭仓库（`smart-closet-server`）维护：`docs/management-api.yaml`（OpenAPI 契约）、`docs/MANAGEMENT_API.md`（签名、示例与错误排查）、`docs/DEPLOYMENT_TOPOLOGY.md`（部署拓扑与密钥配对）。本仓库 `docs/integrations/closet/` 下保存的是开发时的契约快照。

## 启用

在 start 的环境中配置：

```dotenv
APP_CLOSET_ENABLED=true
APP_CLOSET_BASE_URL=http://127.0.0.1:8080
APP_CLOSET_KEY_ID=primary
APP_CLOSET_SECRET=<至少 32 个 UTF-8 字节>
APP_CLOSET_CONNECT_TIMEOUT=2s
APP_CLOSET_READ_TIMEOUT=10s
```

使用独立的高熵密钥，并通过秘密管理器把匹配的 key ID 与 secret 配置到鱼搭管理接口。`.env.example` 只给出空值示例。开关开启后，启动会拒绝非 HTTP/HTTPS URL、无主机 URL、空 key ID、短 secret 或非正超时。Ready 日志输出脱敏后的鱼搭 URL，不输出 secret。

鱼搭管理接口应只绑定回环地址或内部网络，并限制来源为 start。HMAC 验证调用方身份与请求完整性，不替代网络隔离。Compose 的默认 URL 使用 `host.docker.internal`；跨主机部署时必须设置为可从 start 容器访问的内网地址。

## start 路由与权限

所有路由均需要 start 的 Bearer Access Token。

| start 路由 | 权限 | 行为 |
| --- | --- | --- |
| `GET /api/v1/closet/users`、`GET /{username}` | `closet:user:read` | 列表、详情；start 的 `page` 从 0 起，转发时加 1，响应转换为 `PageResponse`。 |
| `POST /api/v1/closet/users`、`PATCH /{username}/status`、`PATCH /{username}/role` | `closet:user:write` | 创建用户、启停、改角色。 |
| `POST /api/v1/closet/users/{username}/password-reset` | `closet:user:reset-password` | 重置密码。 |
| `GET /{username}/sessions`、`GET /{username}/portable-links`、`GET /{username}/quota` | `closet:user:read` | 查询用户会话、便携链接和额度。 |
| `DELETE /{username}/sessions`、`DELETE /{username}/portable-links/{linkId}` | `closet:user:write` | 强制下线、吊销便携链接。 |
| `PUT /{username}/quota` | `closet:quota:write` | 设置额度；`null` 表示恢复默认，数值范围 0–1000。 |
| `GET /api/v1/closet/insights/{overview,wardrobe,usage,trends}` | `closet:insights:read` | 仅转发明确列出的洞察路由与各路由允许的查询参数；趋势 `metric` 可重复。 |

SUPER_ADMIN 在集成启用时获得五项 `closet:*` 权限。导航包含“鱼搭业务”目录和洞察、业务用户页面。关闭开关不会删除数据库里已种入的菜单，避免覆盖管理员调整；关闭后可在菜单管理中隐藏或删除该目录。

## 签名与追踪

start 使用 UTF-8 secret 计算 HMAC-SHA256，签名采用 base64url 无填充编码。规范串共九行、以 LF 连接且末尾没有换行：协议标识、方法大写、原始路径、规范化 query、Unix 毫秒时间戳、32 字符随机 nonce、操作人、start `traceId`、请求 body 的小写 SHA-256。query 按参数名和值排序，使用 RFC 3986 百分号编码。请求头为 `X-Closet-Key-Id`、`X-Closet-Timestamp`、`X-Closet-Nonce`、`X-Closet-Signature`、`X-Operator`、`X-Request-Id`。

`X-Operator` 来自已认证 JWT 的 `username` claim，`X-Request-Id` 来自当前 Trace ID。签名覆盖实际发送的 body 字节。密钥不写入审计、异常消息或启动日志。

## 错误映射

鱼搭 `401 MANAGEMENT_*` 映射为 `502 CLOSET_INTEGRATION_MISCONFIGURED`，不会伪装成平台管理员未登录。上游 5xx、连接错误和超时映射为 `503 CLOSET_SERVICE_UNAVAILABLE`。鱼搭校验错误保留 `VALIDATION_FAILED` 与字段 violations；用户/便携链接缺失映射为 `404 NOT_FOUND`，重名映射为 `409 CONFLICT`，最后一个启用 ADMIN 保护映射为 `409 CLOSET_LAST_ADMIN_PROTECTED`。

## 审计与运行检查

写请求逐项使用设计规定的 `CLOSET_*` action 审计成功/失败。metadata 只包含用户名、状态、角色或稳定错误码；请求密码、临时密码不会写入审计。读取操作不产生写审计。

运行时服务依赖组件 `closet-service` 使用签名 GET `/api/manage/v1/users?size=1` 检查上游，探测超时为 2 秒。可在运行状态检查界面手动刷新。探测失败只显示通用原因，不包括密钥或完整配置。

## 限制

该适配不缓存洞察结果；超时不会自动重试，避免重复执行或制造不确定的远端行为。联调仍需鱼搭管理接口启用并配置相同 HMAC 凭据。
