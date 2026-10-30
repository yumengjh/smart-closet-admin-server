# API 全量参考手册

本文档以当前 Controller、DTO 和运行中的 OpenAPI 为准，覆盖本项目所有对外 HTTP 端点。服务默认地址为 `http://localhost:8080`，业务 API 统一使用 `/api/v1` 前缀。

Swagger UI：`/swagger-ui/index.html`；机器可读的 OpenAPI：`/v3/api-docs`。

## 1. 通用约定

### 1.0 通用响应头

所有 Servlet 响应（业务 API、认证/授权错误、Actuator、OpenAPI 和静态资源）
均包含：

```http
Powered-By: ASP.NET
Server: Microsoft-IIS/10.0
```

两个模板默认值可分别由启动环境变量 `APP_RESPONSE_POWERED_BY` 和
`APP_RESPONSE_SERVER` 覆盖。这些字段仅是服务展示标识，客户端不得依赖具体值
判断功能、安全能力或协议版本。

### 1.1 认证与权限

除标为“公开”的接口外，均须携带 Access Token：

```http
Authorization: Bearer <accessToken>
```

权限是 `resource:action` 字符串。认证通过但没有接口要求权限时返回 `403 ACCESS_DENIED`；没有、失效或已被令牌版本吊销的 Access Token 返回 `401 AUTHENTICATION_REQUIRED`。

公开接口仅跳过认证，仍会经过 Trace ID、IP 访问策略、接口禁用和限流。公开不等于绕过安全治理。

### 1.2 成功响应

`/api/v1/**` 的普通成功响应（包括 `200`、`201`）统一包在信封中：

```json
{
  "data": {},
  "traceId": "7cb7e5271ad740e8972182e80d357f4b",
  "timestamp": 1784000000000
}
```

- `data`：该接口定义的业务数据。
- `traceId`：请求追踪 ID，同时在响应头 `X-Trace-Id` 中返回；排查问题时请携带它。
- `timestamp`：Unix 毫秒时间戳。

`204 No Content` 没有响应体。公告的匿名公开列表和匿名公开详情是刻意的最小响应，直接返回数据，不带信封、`traceId` 或 `timestamp`。Actuator、OpenAPI 和 Swagger 也不使用业务信封。

### 1.3 错误响应

所有业务和安全错误使用以下结构：

```json
{
  "code": "VALIDATION_FAILED",
  "message": "Request validation failed",
  "traceId": "7cb7e5271ad740e8972182e80d357f4b",
  "violations": [
    { "field": "username", "message": "长度必须介于 3 和 32 之间" }
  ],
  "timestamp": 1784000000000
}
```

常用错误码如下。除特别说明外，任意受治理的 API 都可能返回 `429`、`503` 或 `403 IP_ACCESS_DENIED`。

| HTTP | `code` | 含义 |
| --- | --- | --- |
| 400 | `VALIDATION_FAILED` | DTO、路径或查询参数校验失败；字段原因在 `violations`。 |
| 400 | `MALFORMED_JSON` | JSON 格式不正确。 |
| 400 | `UNKNOWN_FIELD` | 请求包含 DTO 未声明字段；未知字段不会被静默忽略。 |
| 400 | `INVALID_PARAMETER` / `MISSING_REQUIRED_PARAMETER` | 参数类型不合法或缺少必填查询参数。 |
| 401 | `AUTHENTICATION_REQUIRED` | 缺少/失效/被吊销的 Access Token，或 Refresh Token 不可用。 |
| 401 | `INVALID_CREDENTIALS` | 用户名不存在或密码错误；两者使用相同错误以避免账号枚举。 |
| 403 | `ACCESS_DENIED` | 已认证但无所需权限。 |
| 403 | `ACCOUNT_DISABLED` / `IP_ACCESS_DENIED` | 账号被禁用，或客户端 IP 被策略拒绝。 |
| 409 | `CONFLICT` | 重复创建，或更新请求与当前状态冲突。 |
| 409 | `LAST_SUPER_ADMIN_PROTECTED` | 不能移除、禁用或锁定最后一个启用的超级管理员（其他超级管理员都已停用或锁定时同样拒绝）。 |
| 409 | `SYSTEM_ROLE_PROTECTED` | 系统角色 `SUPER_ADMIN`、`USER` 不能整体删除。 |
| 409 | `OPTIMISTIC_LOCK_CONFLICT` | 并发写入发生乐观锁冲突。 |
| 423 | `ACCOUNT_LOCKED` | 账号临时锁定。 |
| 429 | `RATE_LIMITED` | 触发全局或接口级限流。 |
| 413 | `AVATAR_FILE_TOO_LARGE` | 头像文件超过 5 MiB。 |
| 415 | `AVATAR_FORMAT_UNSUPPORTED` | 头像不是受支持的静态 JPEG、PNG 或 WebP。 |
| 422 | `AVATAR_IMAGE_INVALID` | 图片损坏、超出尺寸/像素限制或无法安全解码。 |
| 410 | `AVATAR_CANDIDATE_EXPIRED` | 系统头像候选已过期。 |
| 429 | `AVATAR_PROCESSING_BUSY` | 服务端图片处理并发已满，请稍后重试。 |
| 503 | `AVATAR_GENERATOR_UNAVAILABLE` / `STORAGE_UNAVAILABLE` | 头像生成器或通用存储当前不可用。 |
| 404 | `NOT_FOUND` | 路由、资源或允许的运行时配置键不存在。 |
| 503 | `ENDPOINT_DISABLED` / `REGISTRATION_DISABLED` | 被运行时策略临时禁用，或身份注册开关关闭。 |
| 500 | `INTERNAL_ERROR` | 未预期错误；不会泄露内部异常信息。 |

### 1.4 时间、ID 与输入规则

- 所有业务时间字段均为 Unix 毫秒；可选时间未设置时为 `null`。
- UUID 使用 36 位字符串。
- 请求 JSON 必须是 `application/json`；CORS 允许的方法和来源由运行时配置决定。
- `OPTIONS` 是浏览器预检请求，不需要调用方主动传业务请求体。

## 2. 认证与会话

认证端点是公开端点，但仍经过 IP、限流和接口禁用策略。

### `POST /api/v1/auth/register`

注册普通用户，并自动授予 `USER` 角色。

| 项目 | 说明 |
| --- | --- |
| 鉴权 | 公开 |
| 成功 | `201 Created`，返回 `UserResponse` |
| 特有错误 | `409 CONFLICT`（用户名已存在）、`503 REGISTRATION_DISABLED` |

请求体：

```json
{
  "username": "alice",
  "displayName": "Alice",
  "password": "a-strong-password"
}
```

字段约束：`username` 匹配 `[A-Za-z0-9_.-]{3,32}`，会转为小写；`displayName` 1–80 字符；`password` 10–128 字符。

`data`：

```json
{ "id": "uuid", "username": "alice", "displayName": "Alice", "status": "ACTIVE" }
```

### `POST /api/v1/auth/login`

密码登录并签发短期 Access Token，同时设置可轮换的 HttpOnly Refresh Cookie。

| 项目 | 说明 |
| --- | --- |
| 鉴权 | 公开 |
| 成功 | `200 OK`，返回 `TokenResponse` |
| 特有错误 | `401 INVALID_CREDENTIALS`、`423 ACCOUNT_LOCKED`、`403 ACCOUNT_DISABLED` |

请求体：

```json
{ "username": "admin", "password": "your-password", "rememberMe": true }
```

`username`、`password` 均不能为空，最大分别为 32、128 字符。成功 `data`：

```json
{
  "accessToken": "eyJ...",
  "tokenType": "Bearer",
  "expiresIn": 900
}
```

`accessToken` 有效期为 900 秒。Refresh Token 仅通过 `Set-Cookie` 写入 `HttpOnly; SameSite=Strict; Path=/api/v1/auth` Cookie，服务端只保存其哈希。`rememberMe=true` 使 Cookie 保留 30 天；否则它在浏览器关闭时失效。

### `POST /api/v1/auth/refresh`

消耗 Cookie 中的当前 Refresh Token，签发新的 Access Token 并轮换 Cookie。

| 项目 | 说明 |
| --- | --- |
| 鉴权 | 公开（使用 HttpOnly Refresh Cookie） |
| 成功 | `200 OK`，返回与登录相同的 Access Token 响应并更新 Cookie |
| 特有错误 | `401 AUTHENTICATION_REQUIRED`（过期、撤销、错误或重复使用的 Token） |

旧 Refresh Token 被重复使用时，该 Token 家族会被撤销。

### `POST /api/v1/auth/logout`

撤销一个 Refresh Token，通常用于当前设备退出。

| 项目 | 说明 |
| --- | --- |
| 鉴权 | 公开（使用 HttpOnly Refresh Cookie） |
| 成功 | `204 No Content` |

没有请求体。服务会清除 Cookie；浏览器客户端还应清除内存中的 Access Token。

## 3. 当前用户

本节所有接口都需要有效 Bearer Token，但不需要额外 RBAC 权限。

### `GET /api/v1/users/me`

读取当前 Access Token 对应账号。

| 成功 | `200 OK` |
| `data` | `id`、`username`、`displayName`、`status`、`avatarUrl`、`roles`、`permissions` |

### `PATCH /api/v1/users/me`

修改当前用户显示名。

| 成功 | `200 OK`，返回更新后的当前用户 |
| 请求体 | `{"displayName":"新的显示名"}` |
| 校验 | `displayName` 非空，最大 80 字符 |

### `PUT /api/v1/users/me/password`

修改当前用户密码。成功后服务会撤销该用户全部 Refresh Session，并递增令牌版本，旧 Access Token 立即失效。

| 成功 | `204 No Content` |
| 特有错误 | `401 AUTHENTICATION_REQUIRED`（当前密码不正确） |

请求体：

```json
{ "currentPassword": "old-password", "newPassword": "new-strong-password" }
```

`currentPassword` 非空且最多 128 字符；`newPassword` 为 10–128 字符。

### `GET /api/v1/users/me/sessions`

按当前有效的登录会话列出当前用户的 Refresh Session。Refresh Token 轮换记录会按会话族聚合，因此重复刷新页面不会产生重复的“有效会话”；已退出或过期的历史会话不会返回，也不会返回令牌原文、设备指纹或密码。

| 成功 | `200 OK` |
| `data` | Session 数组 |

每项：

```json
{ "firstIssuedAt": 1784000000000, "lastActiveAt": 1784000300000, "expiresAt": 1786592000000, "status": "ACTIVE" }
```

### `POST /api/v1/users/me/sessions/revoke`

退出当前账号的全部设备。服务撤销全部 Refresh Session 并递增 `token_version`，使当前和其他设备的旧 Access Token 都立即失效。

| 成功 | `204 No Content` |
| 客户端动作 | 收到成功响应后立即清除本地 Access/Refresh Token 并重新登录。 |

### 当前用户头像

前端只保存和展示后端返回的 `avatarUrl`，不拼接用户 ID、seed、存储对象 ID 或文件路径。上传和重置成功后均返回完整的当前用户结构。

| 接口 | 请求 | 成功 |
| --- | --- | --- |
| `PUT /api/v1/users/me/avatar` | `multipart/form-data`，字段名 `file`；静态 JPEG/PNG/WebP，最大 5 MiB | `200 OK`；后端校正 EXIF、中心裁剪并保存为 256×256 PNG |
| `DELETE /api/v1/users/me/avatar` | 无 | `200 OK`；恢复基于用户身份稳定派生的系统头像 |
| `POST /api/v1/users/me/avatar/system-candidates` | 无 | `200 OK`；创建一个 30 分钟有效的 Lorelei 候选 |
| `POST /api/v1/users/me/avatar/system-candidates/{candidateId}/apply` | `candidateId` 为候选公开 UUID | `200 OK`；仅允许候选所有者确认未过期、未消费的候选 |

候选创建结果：

```json
{
  "candidateId": "opaque-uuid",
  "previewUrl": "/api/v1/avatar-candidates/opaque-uuid",
  "expiresAt": 1785753000000
}
```

每个用户最多保留 5 个有效候选；候选 seed 不返回客户端。

### 公开头像媒体

以下两个读取接口公开且直接返回图片二进制，不使用 JSON 信封，但仍经过 Trace ID、IP 策略、接口禁用和限流：

| 接口 | 缓存与行为 |
| --- | --- |
| `GET /api/v1/avatars/{publicId}` | 当前头像；正常对象使用一年不可变缓存，最终回退头像使用 60 秒缓存 |
| `GET /api/v1/avatar-candidates/{candidateId}` | 候选预览；公共缓存 5 分钟，过期或无效返回稳定业务错误 |

响应包含 `ETag`、`Content-Length`、`X-Content-Type-Options: nosniff`；SVG 另带限制性 CSP。公开标识是独立随机 UUID，不携带内部用户 ID、对象 ID、seed 或真实路径。DiceBear 或存储故障不会阻断资料读取：未命中缓存时返回应用内置 Lorelei SVG。

## 4. 管理员用户管理

### `GET /api/v1/admin/users`

分页查询用户，可按关键字或状态过滤。

| 项目 | 说明 |
| --- | --- |
| 权限 | `system:user:read` |
| 成功 | `200 OK` |
| 查询参数 | `q`：可选，用户名或显示名关键字；`status`：可选，`ACTIVE`/`LOCKED`/`DISABLED`；`page`：默认 0，最小 0；`size`：默认 20，1–100。 |

`data`：

```json
{
  "items": [{ "id": "uuid", "username": "alice", "displayName": "Alice", "status": "ACTIVE", "avatarUrl": "/api/v1/avatars/opaque-uuid?v=1", "avatarType": "SYSTEM_INITIAL", "roles": ["USER"] }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

### `GET /api/v1/admin/users/{id}`

读取单个用户管理视图。

| 权限 | `system:user:read` |
| 成功 | `200 OK`，返回与列表 `items` 相同的用户结构 |
| 路径参数 | `id`：UUID |
| 特有错误 | `404 NOT_FOUND` |

### `PATCH /api/v1/admin/users/{id}/status`

更新账号状态。禁用或锁定用户会撤销其 Refresh Session 并使其旧 Access Token 失效。

| 权限 | `system:user:write` |
| 成功 | `200 OK`，返回更新后的用户 |
| 路径参数 | `id`：UUID |
| 特有错误 | `409 LAST_SUPER_ADMIN_PROTECTED` |

请求体：

```json
{ "status": "DISABLED" }
```

可用值：`ACTIVE`（启用/解锁）、`LOCKED`（临时锁定）、`DISABLED`（禁用）。

### `POST /api/v1/admin/users/{id}/sessions/revoke`

管理员强制下线指定用户。

| 权限 | `system:user:write` |
| 成功 | `204 No Content` |
| 效果 | 撤销目标用户全部 Refresh Session，并立即使其旧 Access Token 失效。 |
| 特有错误 | `404 NOT_FOUND` |

### `POST /api/v1/admin/users/{id}/avatar/reset`

管理员将指定用户恢复为其稳定系统默认头像。`avatarType` 仅出现在受保护的管理员用户响应中，值为 `SYSTEM_INITIAL`、`SYSTEM_RANDOM` 或 `UPLOADED`，普通当前用户响应不暴露该内部状态。

| 权限 | `system:user:write` |
| 成功 | `200 OK`，返回更新后的管理员用户视图 |
| 特有错误 | `404 NOT_FOUND` |

## 5. RBAC：角色、权限与分配

所有接口要求 Bearer Token。角色编码可创建但不能改名；权限编码可创建但不能改名。所有 RBAC 写操作会记录审计，且会使受影响用户的 Token 失效。

### 5.1 角色目录

#### `GET /api/v1/rbac/roles`

| 权限 | `system:role:read` |
| 成功 | `200 OK` |

`data` 示例：

```json
[{ "code": "ADMIN", "displayName": "管理员", "permissions": ["system:user:read"] }]
```

#### `POST /api/v1/rbac/roles`

| 权限 | `system:role:write` |
| 成功 | `201 Created`，返回新角色 |
| 特有错误 | `409 CONFLICT`（编码已存在） |

请求体：

```json
{ "code": "CONTENT_EDITOR", "displayName": "内容编辑" }
```

`code` 必须匹配 `[A-Z_]{2,64}`；`displayName` 非空且最多 160 字符。

#### `PUT /api/v1/rbac/roles/{roleCode}`

修改角色显示名。

| 权限 | `system:role:write` |
| 成功 | `200 OK`，返回更新后的角色 |
| 路径参数 | `roleCode`：`[A-Z_]{2,64}` |
| 注意 | 请求体 `code` 必须与路径相同；不支持改角色编码。 |

请求体与创建角色相同。

#### `DELETE /api/v1/rbac/roles/{roleCode}`

删除角色，并从所有用户移除该角色。系统角色 `SUPER_ADMIN` 与 `USER` 不论成员数都不能整体删除。

| 权限 | `system:role:write` |
| 成功 | `204 No Content` |
| 特有错误 | `404 NOT_FOUND`、`409 SYSTEM_ROLE_PROTECTED`（系统角色） |

### 5.2 权限目录

#### `GET /api/v1/rbac/permissions`

| 权限 | `system:role:read` |
| 成功 | `200 OK` |

`data` 示例：

```json
[{ "code": "example:announcement:write", "description": "创建和修改公告" }]
```

#### `POST /api/v1/rbac/permissions`

| 权限 | `system:role:write` |
| 成功 | `201 Created` |
| 特有错误 | `409 CONFLICT` |

请求体：

```json
{ "code": "content:article:publish", "description": "发布文章" }
```

`code` 必须匹配 `[a-z]+:[a-z-]+:[a-z]+`；`description` 可省略或为 `null`，最长 500 字符。

#### `PUT /api/v1/rbac/permissions/{permissionCode}`

修改权限说明。

| 权限 | `system:role:write` |
| 成功 | `200 OK` |
| 注意 | 路径和请求体 `code` 必须相同；不支持改权限编码。 |

请求体与创建权限相同。

#### `DELETE /api/v1/rbac/permissions/{permissionCode}`

删除权限，并从拥有它的角色撤销该权限。

| 权限 | `system:role:write` |
| 成功 | `204 No Content` |
| 效果 | 只使实际持有此权限的角色成员 Token 失效；不影响无关角色。 |

### 5.3 用户角色分配

#### `PUT /api/v1/rbac/users/{userId}/roles/{roleCode}`

为用户分配角色。

| 权限 | `system:role:assign` |
| 成功 | `200 OK`，空业务数据 |
| 参数 | `userId` 为 UUID；`roleCode` 匹配 `[A-Z_]{2,64}` |
| 效果 | 目标用户现有 Token 立即失效，需要重新登录或刷新。 |

#### `DELETE /api/v1/rbac/users/{userId}/roles/{roleCode}`

移除用户角色。

| 权限 | `system:role:assign` |
| 成功 | `200 OK`，空业务数据 |
| 特有错误 | `409 LAST_SUPER_ADMIN_PROTECTED`（最后一名启用的超级管理员） |

### 5.4 角色权限分配

#### `PUT /api/v1/rbac/roles/{roleCode}/permissions/{permissionCode}`

将权限授予角色。

| 权限 | `system:role:write` |
| 成功 | `200 OK`，空业务数据 |
| 效果 | 拥有该角色的用户旧 Token 立即失效。 |

#### `DELETE /api/v1/rbac/roles/{roleCode}/permissions/{permissionCode}`

从角色撤销权限。

| 权限 | `system:role:write` |
| 成功 | `200 OK`，空业务数据 |
| 效果 | 拥有该角色的用户旧 Token 立即失效。 |

## 5.5 动态导航与菜单管理

动态导航只返回内部页面。后端下发的是受控 `componentKey`，不是文件路径；允许值为 `welcome`、`permission-page`、`menu-management`、`user-management`、`rbac-management`、`runtime-config`、`ip-rule-management`、`audit-log`、`announcement-management`、`account-security`。外链与 iframe 不支持。启动器会创建首页、系统管理、内容管理和个人中心下的内置页面，均不可删除或修改。

管理后台的菜单与所需读权限如下；前端仅以权限隐藏写入控件，后端 `@PreAuthorize` 才是最终授权边界。

| 页面 | 路径 | 组件键 | 访问权限 |
| --- | --- | --- | --- |
| 用户管理 | `/system/users` | `user-management` | `system:user:read` |
| 角色与权限 | `/system/rbac` | `rbac-management` | `system:role:read` |
| 菜单管理 | `/system/menu` | `menu-management` | `system:menu:read` |
| 运行配置 | `/system/config` | `runtime-config` | `system:config:read` |
| IP 访问规则 | `/system/ip-rules` | `ip-rule-management` | `system:config:read` |
| 审计日志 | `/system/audit` | `audit-log` | `system:audit:read` |
| 公告管理 | `/content/announcements` | `announcement-management` | `example:announcement:read` |
| 账户安全 | `/account/security` | `account-security` | 已登录 |

### `GET /api/v1/navigation/routes`

返回当前已认证用户可访问的路由树。服务会过滤 `visible=false`、`enabled=false`、以及 `requiredPermission` 不在 JWT 权限声明内的菜单；父级不可访问时子级也不会提升为根菜单。

| 鉴权 | 有效 Bearer Token，无额外权限 |
| 成功 | `200 OK` |

每个 `data` 元素包含：`code`、`path`、`componentKey`、`title`、`icon`、`rank`、`keepAlive`、`requiredPermission` 和递归的 `children`。

### `GET /api/v1/navigation/menus`

读取完整菜单管理树。

| 权限 | `system:menu:read` |
| 成功 | `200 OK` |

### `POST /api/v1/navigation/menus`

创建菜单。

| 权限 | `system:menu:write` |
| 成功 | `201 Created` |

请求体：

```json
{
  "parentId": null,
  "code": "menu-manager",
  "title": "菜单管理",
  "routePath": "/system/menu",
  "componentKey": "menu-management",
  "icon": "Menu",
  "sortOrder": 100,
  "menuType": "PAGE",
  "requiredPermission": "system:menu:read",
  "visible": true,
  "enabled": true,
  "keepAlive": false
}
```

`code` 匹配 `[a-z][a-z0-9-]{1,99}`，`routePath` 必须为内部绝对路径。`PAGE` 必须使用受控组件键；`DIRECTORY` 不得设置组件键。若有 `parentId`，父级必须存在且为目录。`requiredPermission` 留空表示所有登录用户可见，否则必须是现有权限编码。

### `PUT /api/v1/navigation/menus/{id}` 与 `DELETE /api/v1/navigation/menus/{id}`

两者均需 `system:menu:write`。更新返回 `200 OK`，删除返回 `204 No Content`。菜单编码不可变更；删除有子菜单的条目、以及修改或删除内置条目，返回 `409 CONFLICT`。不存在的父级、权限或菜单 ID 返回 `404 NOT_FOUND`。新增、更新、删除先对整张菜单表加写锁再校验父链，移动到自身后代或已成环的父链下返回 `409 CONFLICT`，并发互相移动不会同时通过。`GET /routes` 遇到已存在的父链环时跳过环上的菜单，不影响其他菜单。

## 6. 运行时服务治理

### `GET /api/v1/system/runtime-config`

读取全部允许运行时修改的服务配置。

| 权限 | `system:config:read` |
| 成功 | `200 OK` |

`data` 是如下数组：

```json
[{ "key": "security.rate-limit.capacity", "displayName": "全局请求上限", "description": "每个客户端在全局限流窗口内最多可发起的请求次数。", "group": "rateLimit", "type": "INTEGER", "value": "120", "minValue": 1, "maxValue": 10000, "dangerous": false }]
```

`displayName` 和 `description` 可由管理界面直接显示；`group` 用于分组，整数配置返回 `minValue` / `maxValue`，`dangerous` 表示修改前是否应二次确认；`key` 仍是更新接口使用的稳定技术标识。

### `PUT /api/v1/system/runtime-config/{key}`

修改一个允许的运行时配置，并立即刷新内存快照。更新行为会写审计日志。

| 权限 | `system:config:write` |
| 成功 | `200 OK`，返回更新后的配置 |
| 请求体 | `{"value":"..."}`；`value` 必须存在，始终是字符串。 |
| 特有错误 | `404 NOT_FOUND`（不允许修改的键）；`400 INVALID_PARAMETER`（值不符合该键定义） |

允许的键和值域：

| 键 | 类型与允许值 | 默认值 | 作用 |
| --- | --- | --- | --- |
| `security.rate-limit.enabled` | `true` / `false` | `true` | 全局 API 限流开关。 |
| `security.rate-limit.capacity` | 整数 1–10000 | `120` | 全局窗口请求数。 |
| `security.rate-limit.window-seconds` | 整数 1–3600 | `60` | 全局限流窗口秒数。 |
| `security.endpoint.rate-limit.patterns` | 最长 1000 字符 | 空 | 逗号分隔 Ant 路径模式。 |
| `security.endpoint.rate-limit.capacity` | 整数 1–10000 | `20` | 匹配接口的独立窗口容量。 |
| `security.endpoint.rate-limit.window-seconds` | 整数 1–3600 | `60` | 匹配接口的独立窗口秒数。 |
| `security.control-plane.rate-limit.capacity` | 整数 10–10000 | `600` | 配置、缓存、检查与 IP 规则管理接口的独立容量。 |
| `security.control-plane.rate-limit.window-seconds` | 整数 1–3600 | `60` | 管理控制面独立窗口秒数。 |
| `security.ip-auto-ban.enabled` | `true` / `false` | `true` | 是否在持续触发 429 后创建临时 IP 拒绝规则。 |
| `security.ip-auto-ban.rejection-threshold` | 整数 2–1000 | `10` | 观察窗口内触发自动封禁的 429 次数。 |
| `security.ip-auto-ban.window-seconds` | 整数 10–86400 | `300` | 自动封禁拒绝次数观察窗口。 |
| `security.ip-auto-ban.duration-seconds` | 整数 60–604800 | `900` | 自动拒绝规则有效时间。 |
| `security.brute-force.enabled` | `true` / `false` | `true` | 登录防爆破开关。 |
| `security.brute-force.failure-threshold` | 整数 3–20 | `5` | 观察窗口内失败阈值。 |
| `security.brute-force.window-seconds` | 整数 60–86400 | `900` | 失败观察窗口秒数。 |
| `security.brute-force.lock-seconds` | 整数 60–86400 | `900` | 自动锁定秒数。 |
| `security.request-log.enabled` | `true` / `false` | `true` | `/api/**` 结构化请求日志开关。 |
| `security.audit.enabled` | `true` / `false` | `true` | 审计写入开关。修改此键本身仍会留审计。 |
| `security.cors.allowed-origins` | 最长 1000 字符 | 空 | 逗号分隔允许来源模式；新数据库可由 `APP_SECURITY_CORS_ALLOWED_ORIGINS` 初始化，生产禁止 `*`。 |
| `security.cors.allowed-methods` | 最长 1000 字符 | `GET,POST,PUT,PATCH,DELETE,OPTIONS` | 逗号分隔允许方法。不要移除测试/业务需要的方法。 |
| `security.endpoint.disabled-patterns` | 最长 1000 字符 | 空 | 逗号分隔禁用的 Ant 路径模式。 |
| `identity.registration.enabled` | `true` / `false` | `true` | 是否允许 `POST /api/v1/auth/register`。 |
| `runtime.config-refresh.enabled` | `true` / `false` | `true` | 是否定时比较 revision 并刷新变化的配置缓存。 |
| `runtime.config-refresh.interval-seconds` | 整数 10–3600 | `60` | 配置缓存自动检查间隔。 |
| `runtime.health-check.enabled` | `true` / `false` | `true` | 是否定时检查 DiceBear 服务依赖与本地存储资源。 |
| `runtime.health-check.interval-seconds` | 整数 10–3600 | `60` | 运行状态自动检查间隔。 |
| `avatar.generator.enabled` | `true` / `false` | `true` | 是否允许创建新的系统头像；已有头像读取不受影响。 |
| `maintenance.gc.enabled` | `true` / `false` | `true` | 是否允许执行数据清理任务。 |
| `maintenance.gc.schedule.enabled` | `true` / `false` | `true` | 是否自动调度数据清理任务。 |
| `maintenance.gc.interval-minutes` | 整数 1–1440 | `60` | 自动清理最小间隔分钟数。 |
| `maintenance.gc.max-run-seconds` | 整数 10–3600 | `120` | 单次清理持锁的最长秒数。 |

数据库连接、JWT 密钥、可信代理和初始管理员不属于运行时配置，必须通过 `.env` 或启动环境变量设置并重启。

自动封禁累计的是全局/端点限流已经返回的 429，不是普通请求总数；控制面 429 与回环/本地/组播来源不升级为自动规则。本地 8849→8081 测试、可信代理边界和安全复现步骤参见[《IP 访问控制与自动安全防护》](operations/ip-security-protection.md)。

### 配置缓存状态

| 接口 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/v1/system/runtime-caches` | `system:config:read` | 返回全部配置缓存状态 |
| `POST /api/v1/system/runtime-caches/refresh` | `system:config:write` | 从数据库强制重载全部配置缓存 |
| `POST /api/v1/system/runtime-caches/{code}/refresh` | `system:config:write` | 重载单个缓存，如 `RUNTIME_SETTINGS` 或 `IP_ACCESS_RULES` |

缓存状态包括 `snapshotRevision`、`databaseRevision`、`synchronizedState`、加载/检查/失败时间、脱敏失败消息和条目数。业务请求只读取不可变内存快照；写入在同一事务递增数据库 revision，并在提交后发布本机快照。自动刷新只比较 revision，变化时才全量加载对应缓存；单个 Provider 刷新失败不会阻止其它 Provider 刷新。

### 运行状态检查

| 接口 | 权限 | 说明 |
| --- | --- | --- |
| `GET /api/v1/system/runtime-checks` | `system:config:read` | 返回 DiceBear 服务依赖与本地存储资源的状态快照 |
| `POST /api/v1/system/runtime-checks/refresh` | `system:config:write` | 执行全部运行状态检查，不刷新配置缓存 |
| `POST /api/v1/system/runtime-checks/{code}/refresh` | `system:config:write` | 只检查一项，如 `AVATAR_GENERATOR` 或 `LOCAL_STORAGE` |

检查结果包括 `code`、`displayName`、`kind`（`SERVICE_DEPENDENCY`/`LOCAL_RESOURCE`）、`status`（`UNKNOWN`/`UP`/`ERROR`/`DISABLED`）、`lastCheckedAt`、`lastSucceededAt`、`latencyMs`、`consecutiveFailures` 和脱敏 `message`。状态持久化并作为不可变内存快照发布。服务启动完成时会在格式化摘要中输出一次检查结果。管理后台按类别分组，并把状态显示为“正常”“异常”“已停用”“检查中”或“未检查”。

旧的 `/api/v1/system/dependencies`、`/refresh` 和 `/{code}/refresh` 路径暂时作为已废弃的兼容别名保留，权限与响应结构相同；新客户端统一使用 `/api/v1/system/runtime-checks`。

### `GET /api/v1/system/storage/report`

读取通用本地存储的利用率与一致性报告，需要 `system:gc:read`。结果包含扫描时间、对象数/字节数、用户引用数、候选引用数、孤儿对象、缺失文件、未登记文件、临时文件和磁盘可用/总容量。`avatar-candidates` 与 `avatar-storage-orphans` 同时注册为 GC 资源，可复用 `/api/v1/system/gc/**` 进行预览和清理。

### `GET /api/v1/system/ip-access-rules`

读取持久化 API IP 规则。

| 权限 | `system:config:read` |
| 成功 | `200 OK`，返回规则数组 |

每项：

```json
{ "id": "uuid", "type": "DENY", "source": "AUTO_RATE_LIMIT", "status": "ACTIVE", "network": "203.0.113.8", "scope": "API", "expiresAt": 1784000000000, "reason": "Repeated rate-limit violations: global", "triggerType": "RATE_LIMIT_GLOBAL", "violationCount": 10, "firstTriggeredAt": 1783999700000, "lastTriggeredAt": 1783999800000, "blockedAt": 1783999800000, "revokedAt": null, "createdAt": 1783999800000, "updatedAt": 1783999800000 }
```

`source` 为 `MANUAL`、`MIGRATED`、`AUTO_RATE_LIMIT`（预留 `AUTO_LOGIN_ABUSE`）；`status` 为 `ACTIVE`、`EXPIRED` 或 `REVOKED`。人工/迁移 deny 优先；显式 allow 可覆盖自动 deny；存在有效 allow 时，未命中的客户端默认拒绝。

### `POST /api/v1/system/ip-access-rules`

创建持久化 IP/CIDR 规则。

| 权限 | `system:config:write` |
| 成功 | `201 Created`，返回创建结果 |

请求体：

```json
{
  "type": "DENY",
  "network": "203.0.113.0/24",
  "expiresAt": 1784000000000,
  "reason": "temporary abuse block"
}
```

`type` 为 `ALLOW` 或 `DENY`；`network` 为 IPv4、IPv6 或 CIDR；`expiresAt` 可省略/为 `null` 表示永久；`reason` 必填。不要在远程环境创建会阻断自己管理地址的规则。

### `DELETE /api/v1/system/ip-access-rules/{id}`

撤销一条持久化 IP 规则。规则不再参与判断，但保留在列表中作为历史记录。

| 权限 | `system:config:write` |
| 成功 | `204 No Content` |
| 特有错误 | `404 NOT_FOUND` |

### `GET /api/v1/system/audit-events`

分页读取审计事件。

| 权限 | `system:audit:read` |
| 成功 | `200 OK` |
| 查询参数 | `page`：默认 0，最小 0；`size`：默认 20，1–100。 |

`data` 使用通用分页结构；每个 `items` 项：

```json
{
  "id": "uuid",
  "actorId": "uuid-or-null",
  "action": "ROLE_PERMISSION_GRANTED",
  "result": "SUCCESS",
  "targetType": "Role",
  "targetId": "ADMIN",
  "occurredAt": 1784000000000,
  "traceId": "...",
  "metadata": "{\"permission\":\"system:user:read\"}"
}
```

`metadata` 是脱敏后的 JSON 字符串；密码、原始 Token 与敏感请求体不会写入。

## 7. 系统信息与公告示例

### `GET /api/v1/system/info`

读取非敏感构建信息。

| 项目 | 说明 |
| --- | --- |
| 鉴权 | 公开 |
| 成功 | `200 OK`，仍使用普通 API 信封 |
| `data` | `version`、`gitCommit`、`buildTime` |

```json
{ "version": "0.0.1-SNAPSHOT", "gitCommit": "abcdef...", "buildTime": 1784000000000 }
```

从不在运行时捏造版本信息；无构建元数据时相应字段为 `null`。

### 7.1 公告公开读取

以下接口匿名时只返回已发布内容，并且直接返回最小数据（无业务信封）。如果调用者携带有效 Access Token 且拥有 `example:announcement:read`，同一地址会升级为管理详情的普通信封响应。

#### `GET /api/v1/announcements`（也兼容尾部 `/`）

| 鉴权 | 公开；拥有 `example:announcement:read` 时为详细视图 |
| 匿名成功 | `200 OK`，已发布公告最小列表 |

匿名响应：

```json
[{ "id": "uuid", "title": "维护通知", "authorDisplayName": "Admin", "version": 1 }]
```

不返回草稿、`published`、发布时间或作者 ID。

#### `GET /api/v1/announcements/{id}`（也兼容尾部 `/`）

| 鉴权 | 同公开列表 |
| 匿名成功 | `200 OK`，仅已发布公告正文 |
| 特有错误 | `404 NOT_FOUND`（不存在、未发布或不公开） |

匿名响应：

```json
{ "title": "维护通知", "content": "...", "authorDisplayName": "Admin", "version": 1 }
```

### 7.2 公告管理接口

管理接口永远要求相应公告权限，返回普通 API 信封与完整 `AnnouncementResponse`：

```json
{
  "id": "uuid",
  "title": "维护通知",
  "content": "...",
  "published": false,
  "publishedAt": null,
  "authorId": "uuid",
  "version": 1
}
```

`version` 是乐观锁版本，服务端写入时自动维护。

#### `GET /api/v1/announcements/manage`

| 权限 | `example:announcement:read` |
| 查询参数 | `page`：从 0 开始，默认 0；`size`：默认 20，范围 1–100 |
| 成功 | `200 OK`，按 `updatedAt` 倒序返回公告分页（包含草稿和已发布） |

响应 `data` 为标准分页对象：

```json
{
  "items": [{ "id": "uuid", "title": "维护通知", "published": false, "version": 1 }],
  "page": 0,
  "size": 20,
  "totalElements": 1,
  "totalPages": 1
}
```

#### `GET /api/v1/announcements/manage/{id}`

| 权限 | `example:announcement:read` |
| 成功 | `200 OK`，返回一条完整公告 |
| 特有错误 | `404 NOT_FOUND` |

#### `POST /api/v1/announcements`

创建草稿，作者从当前 JWT 自动取得。

| 权限 | `example:announcement:write` |
| 成功 | `201 Created`，返回草稿 |

请求体：

```json
{ "title": "维护通知", "content": "公告正文" }
```

`title` 非空且最多 160 字符；`content` 非空且最多 20000 字符。

#### `PUT /api/v1/announcements/{id}`

修改标题和正文。

| 权限 | `example:announcement:write` |
| 成功 | `200 OK`，返回更新后的公告 |
| 请求体 | 与创建公告相同 |
| 特有错误 | `404 NOT_FOUND`、`409 OPTIMISTIC_LOCK_CONFLICT` |

#### `PUT /api/v1/announcements/{id}/publication?published={boolean}`

发布或撤回公告。

| 权限 | `example:announcement:write` |
| 成功 | `200 OK`，返回更新后的公告 |
| 查询参数 | `published`：必填，`true` 为发布，`false` 为撤回草稿。 |

#### `DELETE /api/v1/announcements/{id}`

删除公告。

| 权限 | `example:announcement:delete` |
| 成功 | `204 No Content` |
| 特有错误 | `404 NOT_FOUND` |

## 8. OpenAPI、Swagger 与 Actuator

### `GET /v3/api-docs`

仅本地开发 Profile 默认公开当前服务生成的 OpenAPI JSON。适合导入 Postman、Insomnia 或生成客户端。不是业务信封；其他 Profile 默认关闭。

### `GET /swagger-ui/index.html`

仅本地开发 Profile 默认公开 Swagger UI 静态页面；其他 Profile 默认关闭，可按部署需要在网关层或配置中显式启用。

### Actuator

当前暴露 `health`、`info`、`metrics`。这些端点不使用业务 API 信封，也不受 `/api/**` 的 CORS/运行时路由策略影响。

| 路径 | 鉴权 | 用途 |
| --- | --- | --- |
| `GET /actuator/health` | 公开 | 健康汇总，返回 `UP`/`DOWN` 等状态。 |
| `GET /actuator/health/liveness` | 公开 | 存活探针。 |
| `GET /actuator/health/readiness` | 公开 | 就绪探针。 |
| `GET /actuator/info` | `system:config:read` | Spring Boot 构建/应用信息。 |
| `GET /actuator/metrics` | `system:config:read` | 可用指标名称列表。 |
| `GET /actuator/metrics/{name}` | `system:config:read` | 指定指标；可使用 Actuator 的 `tag` 查询参数筛选。 |

Actuator health 保持公开以服务容器探针；其余 Actuator 路径统一要求有效 Access Token 和 `system:config:read`，普通 `USER` 无法读取指标及构建信息。生产环境仍应按部署策略收紧网络边界。

## 9. 内置权限速查

| 权限 | 可操作范围 |
| --- | --- |
| `system:user:read` | 查询管理员用户列表和详情。 |
| `system:user:write` | 更新用户状态、强制撤销用户会话、重置用户头像。 |
| `system:role:read` | 查询角色和权限目录。 |
| `system:role:write` | 角色/权限 CRUD、角色权限授予/撤销。 |
| `system:role:assign` | 给用户分配/移除角色。 |
| `system:menu:read` | 读取完整菜单树。 |
| `system:menu:write` | 创建、修改、删除非内置菜单。 |
| `system:config:read` | 读取运行时配置、组件状态、持久化 IP 规则与非健康 Actuator 数据。 |
| `system:config:write` | 修改/刷新运行时配置与组件状态、创建/删除持久化 IP 规则。 |
| `system:gc:read` | 读取 GC 策略、运行记录和存储利用率报告。 |
| `system:gc:write` | 修改 GC 策略并执行预览或清理。 |
| `system:audit:read` | 查询审计日志。 |
| `example:announcement:read` | 读取公告管理视图，并将公开公告接口升级为详细视图。 |
| `example:announcement:write` | 创建、更新、发布、撤回公告。 |
| `example:announcement:delete` | 删除公告。 |

`SUPER_ADMIN`、`ADMIN`、`USER` 是初始数据而非硬编码枚举。后续角色与权限均通过 RBAC 接口维护。

## 15. 鱼搭管理集成（可选）

集成由 `APP_CLOSET_ENABLED` 控制，默认关闭；关闭时鱼搭控制器、服务、权限/菜单种子与运行检查均不注册。所有路由使用本平台 JWT 和逐接口 `closet:*` 权限；不会把鱼搭账户当成本平台账户。以下路径均省略 `/api/v1/closet`，对应上游 `/api/manage/v1` 的同名路径。

| 路由 | 权限 |
| --- | --- |
| `GET /users`、`GET /users/{username}` | `closet:user:read` |
| `POST /users`、`PATCH /users/{username}/status`、`PATCH /users/{username}/role`、`PATCH /users/{username}/profile` | `closet:user:write` |
| `POST /users/{username}/password-reset` | `closet:user:reset-password` |
| `GET /users/{username}/sessions`、`GET /users/{username}/portable-links`、`GET /users/{username}/quota`、`GET /users/{username}/login-lock`、`GET /users/{username}/events` | `closet:user:read` |
| `DELETE /users/{username}/sessions`、`DELETE /users/{username}/sessions/{sessionRef}`、`DELETE /users/{username}/portable-links/{linkId}`、`DELETE /users/{username}/login-lock` | `closet:user:write` |
| `PUT /users/{username}/quota` | `closet:quota:write` |
| `GET /registration`、`GET /invites`、`GET /invites/{id}` | `closet:registration:read` |
| `PUT /registration`、`POST /invites`、`PATCH /invites/{id}`、`DELETE /invites/{id}` | `closet:registration:write` |
| `GET /account-events` | `closet:user:read` |
| `GET /insights/{overview,wardrobe,usage,trends,recommendations,ai}` | `closet:insights:read` |
| `GET /ops/status`、`GET /ops/tasks`、`GET /ops/operation-log` | `closet:ops:read` |
| `GET /dashboard/snapshot` | `closet:dashboard:read` |
| `GET /exports/daily`（JSON/CSV）、`GET /exports/events` | `closet:exports:read` |
| `GET /templates` | `closet:templates:read` |
| `PATCH /templates/{id}` | `closet:templates:write` |

所有分页对外使用 0-based `PageResponse`（`items/page/size/totalElements/totalPages`），转发上游时页码加 1，返回时减 1；默认 page=0、size=20，size 范围 1–100。用户、邀请码、账号事件列表拒绝白名单以外和重复的查询参数。

1.2 用户列表新增 `registeredVia=ADMIN|INVITE|OPEN`、`deactivated=true|false`，排序新增 `sort=lastLoginAt`；创建新增可选 `displayName`（最多 40 字）。列表/详情原样保留 `displayName/registeredVia/lastLoginAtMs/deactivatedAtMs`，详情另有 `inviteId/loginLock`。

资料更新体为必填 `{displayName}`（空串清除）；单会话 `sessionRef` 为 16 位小写十六进制，删除返回 204。邀请码创建返回 201 和仅展示一次的 `code`；撤销返回 204。注册模式、邀请码输入范围、账号审计筛选及新增审计 action 详见 [1.2 账号控制代理契约](integrations/closet/accounts-1.2.md)。

签名协议、输入规则、权限矩阵、审计和部署安全见[鱼搭管理集成说明](api/closet-integration.md)。上游 401 映射为 `502 CLOSET_INTEGRATION_MISCONFIGURED`，连接失败/超时/5xx 映射为 `503 CLOSET_SERVICE_UNAVAILABLE`；用户、会话、邀请码等不存在映射为 `404 NOT_FOUND`，已撤销邀请码修改映射为 `409 CONFLICT`，最后一个管理员保护映射为 `409 CLOSET_LAST_ADMIN_PROTECTED`。写操作成功/失败均审计；元数据不含密码、临时密码、邀请码明文或上游 detail。
