# 鱼搭管理接口 1.2：账号控制代理契约

上游：鱼搭 `/api/manage/v1`。唯一事实源为鱼搭仓库 `docs/management-api.yaml`（OpenAPI 3.1，接口版本 1.2.0），说明见该仓库 `docs/MANAGEMENT_API.md` 第 1、5、15 节。本目录的 [management-api.yaml](management-api.yaml) 是只读契约快照（2026-10-31 起按来源提交同步，见 [集成说明](../../api/closet-integration.md)）；本次未修改鱼搭仓库。

本文整理账号控制代理契约及上游字段约束，全部路由均省略后台前缀 `/api/v1/closet`。1.2 新增 13 个操作，总计 39 个（CSV 为 daily 同一路由的另一响应格式）。部署与签名见 [集成说明](../../api/closet-integration.md)。

后台后端（smart-closet-admin-server）在 `/api/v1/closet` 下代理，沿用现有模式：本平台 JWT + 逐接口 `@PreAuthorize("hasAuthority('closet:...')")`；分页对外 0-based `PageResponse`（items/page/size/totalElements/totalPages），转发时 +1；写操作经 `AuditService` 记审计（成功/失败都记），审计元数据不得包含临时密码、邀请码明文；上游 Problem Details 由现有 `ClosetProblemTranslator` 转换；请求体用 DTO + Bean Validation 校验后再转发。

## 1. 用户（已有路由的增量）

| 后台路由 | 权限 | 上游 | 说明 |
| --- | --- | --- | --- |
| `GET /users` | closet:user:read | `GET /users` | 新增查询参数 `registeredVia`(ADMIN/INVITE/OPEN)、`deactivated`(true/false)，`sort` 新增 `lastLoginAt` |
| `POST /users` | closet:user:write | `POST /users` | 请求体新增可选 `displayName`（≤40） |
| `PATCH /users/{username}/profile` | closet:user:write | 同名 | 体 `{displayName}` 必填，空串清除；审计 `CLOSET_USER_PROFILE_UPDATED` |
| `DELETE /users/{username}/sessions/{sessionRef}` | closet:user:write | 同名 | `sessionRef` `^[0-9a-f]{16}$`；204；审计 `CLOSET_USER_SESSION_REVOKED` |
| `GET /users/{username}/login-lock` | closet:user:read | 同名 | `{locked, recentFailures, retryAfterSeconds}` |
| `DELETE /users/{username}/login-lock` | closet:user:write | 同名 | 返回解除后状态；审计 `CLOSET_USER_LOGIN_UNLOCKED` |
| `GET /users/{username}/events` | closet:user:read | 同名 | 数组 `[{occurredAtMs, actor, action, detail}]`，最近 50 条 |

用户列表与详情 JSON 新增字段（原样透传）：`displayName`、`registeredVia`、`lastLoginAtMs`、`deactivatedAtMs`；详情另有 `inviteId`、`loginLock`。

## 2. 注册与邀请码（新增）

| 后台路由 | 权限 | 上游 | 说明 |
| --- | --- | --- | --- |
| `GET /registration` | closet:registration:read | 同名 | `{mode, configuredMode, overridden, updatedBy, updatedAtMs, activeInviteCount}` |
| `PUT /registration` | closet:registration:write | 同名 | 体 `{mode: "CLOSED"|"INVITE"|"OPEN"|null}`，null 恢复部署默认；审计 `CLOSET_REGISTRATION_MODE_CHANGED` |
| `GET /invites` | closet:registration:read | 同名 | 查询 `status`(ACTIVE/USED_UP/EXPIRED/REVOKED)、`page`(0-based)、`size`；返回 `PageResponse<Invite>` |
| `POST /invites` | closet:registration:write | 同名 | 体 `{note?, maxUses?(1–100,默认1), expiresInDays?(1–90,默认7)}`；201，响应含一次性 `code`；审计 `CLOSET_INVITE_CREATED`（只记 id，不记 code） |
| `GET /invites/{id}` | closet:registration:read | 同名 | `{invite, registeredUsers:[{username, createdAtMs, enabled}]}` |
| `PATCH /invites/{id}` | closet:registration:write | 同名 | 体 `{note?, maxUses?, expiresAtMs?}` 至少一个；409 `INVITE_REVOKED`；审计 `CLOSET_INVITE_UPDATED` |
| `DELETE /invites/{id}` | closet:registration:write | 同名 | 204；审计 `CLOSET_INVITE_REVOKED` |

Invite：`{id, note, maxUses, usedCount, createdBy, createdAtMs, expiresAtMs, revokedAtMs, status}`。`{id}` 最长 36。

## 3. 账号审计（新增）

| 后台路由 | 权限 | 上游 | 说明 |
| --- | --- | --- | --- |
| `GET /account-events` | closet:user:read | 同名 | 查询 `from`、`to`(YYYY-MM-DD)、`username`、`action`(`^[A-Z_]{1,48}$`)、`actor`、`page`(0-based)、`size`；返回 `PageResponse<{id, occurredAtMs, username, actor, action, detail}>` |

## 4. 权限与菜单

- 新增权限种子：`closet:registration:read`（查看鱼搭注册模式与邀请码）、`closet:registration:write`（修改注册模式、管理邀请码），授予 SUPER_ADMIN。
- 新增菜单（鱼搭业务目录下）：`closet-registration` “注册与邀请” `/closet/registration` 组件 `closet-registration` 图标 `ri/coupon-3-line` 排序 25 权限 closet:registration:read；`closet-account-events` “账号审计” `/closet/account-events` 组件 `closet-account-events` 图标 `ri/file-list-3-line` 排序 27 权限 closet:user:read。

## 5. 账号事件动作中文名（前端展示）

REGISTERED 注册；LOGIN_SUCCEEDED 登录；PASSWORD_CHANGED 修改密码；PROFILE_UPDATED 修改资料；SESSIONS_REVOKED 退出其他设备；DEACTIVATED 注销账号；ADMIN_USER_CREATED/ENABLED/DISABLED/ROLE_CHANGED/PASSWORD_RESET/SESSIONS_REVOKED/QUOTA_CHANGED 为“鱼搭管理员”+对应动作；MANAGE_* 为“管理平台”+对应动作（另有 MANAGE_PROFILE_UPDATED 改资料、MANAGE_LOGIN_UNLOCKED 解除登录锁定）。未知动作显示原码。


## 6. 输入、分页与透传细节

- 新增控制器、服务与种子继续受 `app.integrations.closet.enabled=true` 控制；关闭时不存在对应路由。
- 用户名匹配 `^[A-Za-z0-9_.-]{2,80}$`；新建账号另与鱼搭后端（smart-closet-server #164）一致，不能只由点组成或包含 `..`（`a.b` 允许，`..`、`a..b` 返回 400 且不转发）；寻址已有账号时只拒绝纯点用户名（无法作为路径段发送），旧的 `a..b` 账号仍可管理。所有动态路径段经过编码。邀请码 id 最多 36 字，不接受空白 id。
- 用户列表白名单为 `q/status/role/sort/order/registeredVia/deactivated/page/size`；`q` 最多 80 字。保留原 `status=ENABLED|DISABLED`、`role=USER|ADMIN`、`sort=createdAt|username|lastActiveAt`、`order=asc|desc`。
- 邀请码列表白名单为 `status/page/size`，账号事件列表白名单为 `from/to/username/action/actor/page/size`。三个列表均拒绝未知和重复参数；`page` 为 0–2147483646，默认 0；`size` 为 1–100，默认 20。转发页码加 1，回包使用 `PageResponse` 的 `items/page/size/totalElements/totalPages`。
- `from/to` 校验为实际存在的 `YYYY-MM-DD` 日期，按上游 `Asia/Shanghai` 解释，`to` 含当天；不传日期由上游默认近 30 天。`actor` 最多 200 字，`username/action` 采用上述格式约束。
- 创建用户的 `displayName` 可选、可 null、最多 40 字；PATCH profile 的该字段必填且非 null，空串清除。用户相关新字段、注册模式、登录锁定、账号事件及邀请码对象均按上游 JSON 透传。
- 注册模式 PUT 接受 `{mode:null}` 或 `{}` 恢复部署默认；其余仅接受 CLOSED/INVITE/OPEN。
- 邀请码 `note` 可选、可 null、最多 100 字；空串清除备注。创建的 `maxUses` 为 1–100、`expiresInDays` 为 1–90，省略或 null 交给上游使用默认值 1 和 7。
- 邀请码 PATCH 至少有一个非 null 字段，仅转发提供的非 null 字段。`maxUses` 为 1–100，不能低于已用次数（由上游判断）；`expiresAtMs` 为整数毫秒且须在现在之后 90 天内。未知 JSON 字段由平台统一拒绝，校验失败在调用上游前返回 400。
- 所有成功 JSON 使用平台 `data` 信封；创建邀请码为 201，删除单会话、撤销邀请码为 204 且无响应体。

## 7. 审计与错误转换

| 操作 | action | 成功审计元数据 |
| --- | --- | --- |
| 修改昵称 | `CLOSET_USER_PROFILE_UPDATED` | username |
| 退出单会话 | `CLOSET_USER_SESSION_REVOKED` | username、sessionRef（不可逆引用） |
| 解除登录锁定 | `CLOSET_USER_LOGIN_UNLOCKED` | username |
| 修改注册模式 | `CLOSET_REGISTRATION_MODE_CHANGED` | id=registration |
| 创建邀请码 | `CLOSET_INVITE_CREATED` | 仅返回的 id |
| 修改邀请码 | `CLOSET_INVITE_UPDATED` | 仅 id |
| 撤销邀请码 | `CLOSET_INVITE_REVOKED` | 仅 id |

写操作上游失败也记录 FAILURE，仅保存目标标识与稳定错误码。审计不保存密码、临时密码、邀请码明文、备注、昵称、完整请求/响应或上游 detail；只读查询不产生写审计。上游创建响应的 `code` 只透传本次响应，不在代理持久化或缓存。

| 上游 | 平台 |
| --- | --- |
| 404 `SESSION_NOT_FOUND` / `INVITE_NOT_FOUND` | 404 `NOT_FOUND` |
| 409 `INVITE_REVOKED` | 409 `CONFLICT` |
| 400 `VALIDATION_FAILED` | 400 `VALIDATION_FAILED`，保留字段 violations |
| 401 / `MANAGEMENT_*`（5xx 除外） | 502 `CLOSET_INTEGRATION_MISCONFIGURED` |
| 5xx / 连接失败 / 超时 | 503 `CLOSET_SERVICE_UNAVAILABLE` |

保留原用户不存在、重名与最后一个管理员保护转换。平台稳定错误码不同于上游业务错误码，页面可使用中文 message 展示原因。

本文中的菜单组件键与动作中文名供前端接入；本次范围仅管理后端，不包含前端页面实现。未知账号事件动作显示原始 action。
