# 认证与会话说明

所有认证接口都在 `/api/v1/auth` 下：

- `POST /register`：注册，传入 `username`、`displayName`、`password`。
- `POST /login`：登录，传入 `username`、`password` 与可选的 `rememberMe`。
- `POST /refresh`：从 HttpOnly Cookie 读取并轮换 Refresh Token。
- `POST /logout`：撤销 HttpOnly Cookie 中的当前设备会话并清除 Cookie。

用户名必须匹配 `[A-Za-z0-9_.-]{3,32}`，服务端会转换为小写。显示名最长 80 个字符；密码长度为 10–128。密码通过 Spring Security 的 DelegatingPasswordEncoder 加密，当前实际使用 BCrypt，响应永远不会返回密码哈希。

## Token

登录和刷新成功后，业务数据位于统一响应的 `data` 内：

```json
{
  "data": {
    "accessToken": "RSA 签名的 JWT",
    "tokenType": "Bearer",
    "expiresIn": 900
  },
  "traceId": "...",
  "timestamp": 1783814400000
}
```

Access Token 有效期为 15 分钟。浏览器客户端只在内存中保留它，并调用受保护接口时使用：

```http
Authorization: Bearer <access-token>
```

JWT 包含用户 ID、用户名、令牌版本和有效权限列表。Refresh Token 有效期为 30 天，数据库只保存它的 SHA-256 哈希，绝不保存原文，也不将原文返回给浏览器 JavaScript。

登录响应会设置路径为 `/api/v1/auth`、`HttpOnly`、`SameSite=Strict` 的 `refresh_token` Cookie。勾选 `rememberMe` 时 Cookie 的 `Max-Age` 为 30 天；未勾选时为会话 Cookie。生产 HTTPS 环境必须设置 `APP_AUTH_REFRESH_COOKIE_SECURE=true`。

`timestamp` 为 Unix 毫秒时间戳。认证接口是公开端点，但仍受 IP 策略、接口禁用与限流等服务级安全策略约束。

## 刷新、复用与登出

每次刷新都会消耗旧 Refresh Token，并返回新的 Access Token 与轮换后的 HttpOnly Cookie；客户端无需、也不能读取 Refresh Token。旧 Token 被再次使用时，服务会撤销该 Token 家族并返回 `401 AUTHENTICATION_REQUIRED`。每个 Refresh Session 还保存签发时（即本次密码校验时）的 `token_version`；刷新时与用户当前版本不一致就撤销该家族、记 `REFRESH_TOKEN_STALE_VERSION` 审计并返回 401，因此与改密并发、在撤销之后才落库的旧密码登录会话也无法刷新出新令牌。

登出会撤销 Cookie 中的 Refresh Token。修改密码会撤销该用户所有 Refresh Token；管理员可撤销指定用户的全部 Refresh Session，禁用/锁定账户也会撤销其 Refresh Session。

当前登录用户还可以使用以下受保护接口：

- `GET /api/v1/users/me/sessions`：按当前有效的登录会话列出该账号的 Refresh Session。每个会话族只返回一项，不会因令牌轮换而产生重复会话；返回首次登录、最近活动、过期时间和状态（Unix 毫秒），不保存或返回令牌原文。
- `POST /api/v1/users/me/sessions/revoke`：撤销该账号所有 Refresh Session，并递增令牌版本使现有 Access Token 立即失效，返回 `204`。调用后应清除客户端保存的 Access/Refresh Token 并重新登录。

自助注册由运行时键 `identity.registration.enabled` 控制；关闭时 `POST /register` 返回 `503 REGISTRATION_DISABLED`。这是 identity 模块自己的开关，仍由统一运行时配置存储与审计机制承载。

## 错误信息

- 用户名不存在或密码错误：`401 INVALID_CREDENTIALS`。两者共用错误，避免泄露账号是否存在。
- 账户处于锁定状态：`423 ACCOUNT_LOCKED`。
- 账户被管理员禁用：`403 ACCOUNT_DISABLED`。
- 缺少、失效或被撤销的 Token：`401 AUTHENTICATION_REQUIRED`。

其他未预期异常才返回通用 `INTERNAL_ERROR`，响应同时包含 Trace ID 供排查。

`POST /register` 返回 `201 Created` 与新用户数据，但不会返回 `Location`：当前模板没有公开的 `GET /api/v1/users/{id}` 资源路由，因此不伪造一个不存在的地址。`POST /logout` 仍返回 `204 No Content`。

## 密钥边界

`local` 默认可在启动时临时生成 RSA 密钥对，因此重启后原 Access Token 会失效。生产 `postgres` Profile 必须同时提供 `APP_JWT_PRIVATE_KEY_PEM` 与 `APP_JWT_PUBLIC_KEY_PEM`，或对应的 `*_PATH` 文件路径；PEM 环境变量中的换行使用 `\n`。不完整或缺失的生产密钥会使应用拒绝启动。

JWT 含 `token_version`。密码、用户状态、角色或权限变化后服务器会递增该版本；每个受保护请求都会比对数据库版本，因此旧 Access Token 不必等待 15 分钟自然过期便会返回 `401 AUTHENTICATION_REQUIRED`。
