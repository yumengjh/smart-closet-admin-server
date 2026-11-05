# 鱼搭管理接口覆盖清单

2026-11-05 对照 `smart-closet-server/docs/management-api.yaml`：69 个上游 HTTP 操作全部接入，实时试穿占 22 个。后台另外保留风格词批量审核扩展；它逐项调用上游单项接口。页面只调用管理后端的 `/api/v1/closet`。

| 分组 | 操作数 | 后台入口 |
| --- | ---: | --- |
| `users` | 19 | 业务用户 |
| `registration` | 2 | 注册与邀请 |
| `invites` | 5 | 注册与邀请 |
| `account-events` | 1 | 账号审计 |
| `security-audit` | 2 | 安全审计 |
| `insights` | 6 | 衣柜洞察 |
| `ops` | 4 | 运行监控 |
| `dashboard` | 1 | 首页业务大屏 |
| `exports` | 2 | 数据导出 |
| `templates` | 2 | 模板审核 |
| `style-terms` | 3 | 风格词审核 |
| `tryon` | 22 | 实时试穿 |

## 试穿功能与权限

菜单 `/closet/tryon`，组件键 `closet-tryon`，需要 `closet:tryon:read`。五个页签为资源与用量、试穿设置、运营账户、会话诊断、用户策略。业务用户列表提供试穿策略直达入口。

| 权限 | 操作 |
| --- | --- |
| `closet:tryon:read` | 设置、资源池、参考图来源、用量、用户策略、账户、凭证脱敏列表及会话诊断 |
| `closet:tryon:write` | 设置更新与恢复、用户完整策略、当日失败保护重置、账户调度 |
| `closet:tryon:credentials` | 登记账户、轮换与停用凭证 |
| `closet:tryon:probe` | 主动探测供应商余额和健康 |
| `closet:tryon:budget` | 账户累计支出授权 |
| `closet:tryon:stop` | 单会话或账户全部会话紧急停止 |

集成默认关闭；全部新增控制器、服务和菜单权限初始化仍受原开关控制。超级管理员在开启集成后自动获得新增权限，其他角色需显式授权。每个写操作由网关独立校验权限，前端隐藏无权操作，并在提交前二次检查权限和确认。

- 设置携带当前 `revision` 与完整 `value`。冲突返回 409；页面保留编辑内容，提示重新读取，不自动覆盖重试。
- 用户策略为完整替换，始终携带 `accessMode`、`dailySeconds`、`startupFailureLimit`。`null` 继承，启动失败上限 `0` 关闭该用户的次数保护。解除重试保护不抹除费用、时长、预算或会话。
- API key 只经密码输入框提交，关闭或提交后清空，不保存到浏览器存储、URL、日志或平台审计。凭证 DTO 的 `toString` 固定脱敏。网关只审计动作、目标、结果和安全错误码，不记录请求体或供应商响应。
- 账户详情与凭证列表仅显示脱敏内容。H2 大写 JDBC 列名和 PostgreSQL 小写列名统一为小写；每日用量日期统一为 Asia/Shanghai 业务日。
- 会话分别展示 HTTP 创建、RTC 完成、首帧与各阶段耗时。未完成阶段显示“未完成或未上报”，不填零。UNKNOWN 仍保留预算，停止请求不等于已完成结算。
- 不新增图片、录像或私人媒体读取入口；大屏与数仓原有去标识边界保持。

## 版本入口

鱼搭后端版本在“运行监控”中显示包版本 `app.version`、发版版本 `app.buildVersion` 与提交号 `app.commit`。没有 CI 构建信息时显示源服务给出的 `dev/unknown` 或“未提供”。管理后端自己的版本在启动日志 `Version` 或受权限保护的 `/actuator/info` 构建信息中，默认来自 `pom.xml` 的 `revision`，CI 可在构建时覆盖。两者不混为同一个版本。

## 全量操作

下表为上游路径；浏览器访问时统一经管理后端 `/api/v1/closet`。

| 方法 | 路径 |
| --- | --- |
| GET | `/users` |
| POST | `/users` |
| GET | `/users/{username}` |
| PATCH | `/users/{username}/status` |
| PATCH | `/users/{username}/role` |
| POST | `/users/{username}/password-reset` |
| GET | `/users/{username}/sessions` |
| DELETE | `/users/{username}/sessions` |
| PATCH | `/users/{username}/profile` |
| DELETE | `/users/{username}/sessions/{sessionRef}` |
| GET | `/users/{username}/login-lock` |
| DELETE | `/users/{username}/login-lock` |
| GET | `/users/{username}/events` |
| GET | `/users/{username}/portable-links` |
| DELETE | `/users/{username}/portable-links/{linkId}` |
| GET | `/users/{username}/quota` |
| PUT | `/users/{username}/quota` |
| GET | `/users/{username}/token-budget` |
| PUT | `/users/{username}/token-budget` |
| GET | `/registration` |
| PUT | `/registration` |
| GET | `/invites` |
| POST | `/invites` |
| GET | `/invites/{id}` |
| PATCH | `/invites/{id}` |
| DELETE | `/invites/{id}` |
| GET | `/account-events` |
| GET | `/security-audit` |
| GET | `/security-audit/integrity` |
| GET | `/insights/overview` |
| GET | `/insights/wardrobe` |
| GET | `/insights/usage` |
| GET | `/insights/trends` |
| GET | `/insights/recommendations` |
| GET | `/insights/ai` |
| GET | `/ops/status` |
| GET | `/ops/assistant-token-budget` |
| GET | `/ops/tasks` |
| GET | `/ops/operation-log` |
| GET | `/dashboard/snapshot` |
| GET | `/exports/daily` |
| GET | `/exports/events` |
| GET | `/templates` |
| PATCH | `/templates/{id}` |
| GET | `/style-terms/pending` |
| POST | `/style-terms/pending/approve` |
| POST | `/style-terms/pending/reject` |
| GET | `/tryon/settings` |
| PUT | `/tryon/settings` |
| POST | `/tryon/settings/reset` |
| GET | `/tryon/users/{username}/policy` |
| PUT | `/tryon/users/{username}/policy` |
| GET | `/tryon/users/{username}/usage` |
| POST | `/tryon/users/{username}/startup-failures/reset` |
| GET | `/tryon/accounts` |
| POST | `/tryon/accounts` |
| GET | `/tryon/accounts/{id}` |
| PATCH | `/tryon/accounts/{id}` |
| POST | `/tryon/accounts/{id}/credentials` |
| DELETE | `/tryon/accounts/{id}/credentials/{credentialId}` |
| POST | `/tryon/accounts/{id}/probe` |
| POST | `/tryon/accounts/{id}/budget` |
| GET | `/tryon/pool` |
| GET | `/tryon/reference-source` |
| GET | `/tryon/usage` |
| GET | `/tryon/sessions` |
| GET | `/tryon/sessions/{id}` |
| POST | `/tryon/sessions/{id}/stop` |
| POST | `/tryon/accounts/{id}/stop-sessions` |
