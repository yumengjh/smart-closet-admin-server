# 鱼搭管理集成

此模块将本管理平台适配为鱼搭 `/api/manage/v1` 的 HMAC 客户端。开关默认为关闭；关闭时控制器、权限/菜单种子、RestClient 与运行检查均不注册。

上游契约与接入说明由鱼搭仓库（`smart-closet-server`）维护：`docs/management-api.yaml`（OpenAPI 契约）、`docs/MANAGEMENT_API.md`（签名、示例与错误排查）、`docs/DEPLOYMENT_TOPOLOGY.md`（部署拓扑与密钥配对）。本仓库 `docs/integrations/closet/management-api.yaml` 是契约源的逐字快照（头部 4 行记录来源提交与 sha256），请勿手改：鱼搭契约变化后运行 `scripts/sync-closet-contract.sh [鱼搭仓库路径]` 同步，`--check` 只比对。`ClosetContractSnapshotTest` 随 `mvn verify` 运行：校验快照未被手改、本服务调用的上游操作都在快照里；能读到契约源（同级 `../smart-closet-server` 或 `CLOSET_CONTRACT_SOURCE`）时逐字节比对，CI 读不到私有鱼搭仓库时跳过这一项。新增上游调用须登记到该测试的 `USED` 列表。

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
| `POST /api/v1/closet/users/{username}/password-reset` | `closet:user:reset-password` | 重置密码。创建用户与重置密码的 `password` 可省略（生成临时密码），填写时与鱼搭一致：至少 12 个字符、UTF-8 编码后不超过 72 字节，在 start 侧先校验并返回鱼搭同款提示。 |
| `GET /{username}/sessions`、`GET /{username}/portable-links`、`GET /{username}/quota` | `closet:user:read` | 查询用户会话、便携链接和额度。 |
| `DELETE /{username}/sessions`、`DELETE /{username}/portable-links/{linkId}` | `closet:user:write` | 强制下线、吊销便携链接。 |
| `PUT /{username}/quota` | `closet:quota:write` | 设置额度；`null` 表示恢复默认，数值范围 0–1000。 |
| `GET /api/v1/closet/insights/{overview,wardrobe,usage,trends}` | `closet:insights:read` | 仅转发明确列出的洞察路由与各路由允许的查询参数；趋势 `metric` 可重复。 |

SUPER_ADMIN 在集成启用时获得十二项 `closet:*` 权限。导航包含“鱼搭业务”目录及衣柜洞察、业务用户、注册与邀请、账号审计、运行监控、数据导出、模板审核七个页面；业务大屏作为平台首页，不再有独立菜单（启动时会清理早期种入的 `closet-dashboard` 菜单）。关闭开关不会删除数据库里已种入的菜单，避免覆盖管理员调整；关闭后可在菜单管理中隐藏或删除该目录。

## 签名与追踪

start 使用 UTF-8 secret 计算 HMAC-SHA256，签名采用 base64url 无填充编码。规范串共九行、以 LF 连接且末尾没有换行：协议标识、方法大写、原始路径、规范化 query、Unix 毫秒时间戳、32 字符随机 nonce、操作人、start `traceId`、请求 body 的小写 SHA-256。query 按参数名和值排序，使用 RFC 3986 百分号编码。请求头为 `X-Closet-Key-Id`、`X-Closet-Timestamp`、`X-Closet-Nonce`、`X-Closet-Signature`、`X-Operator`、`X-Request-Id`。

`X-Operator` 来自已认证 JWT 的 `username` claim，`X-Request-Id` 来自当前 Trace ID。签名覆盖实际发送的 body 字节。密钥不写入审计、异常消息或启动日志。

## 错误映射

鱼搭 `401 MANAGEMENT_*` 映射为 `502 CLOSET_INTEGRATION_MISCONFIGURED`，不会伪装成平台管理员未登录。上游 5xx、连接错误和超时映射为 `503 CLOSET_SERVICE_UNAVAILABLE`。鱼搭校验错误保留 `VALIDATION_FAILED` 与字段 violations；用户/会话/便携链接/邀请码/模板缺失映射为 `404 NOT_FOUND`，重名或修改已撤销邀请码映射为 `409 CONFLICT`，最后一个启用 ADMIN 保护映射为 `409 CLOSET_LAST_ADMIN_PROTECTED`。

## 审计与运行检查

写请求逐项使用设计规定的 `CLOSET_*` action 审计成功/失败。metadata 只包含用户名、状态、角色或稳定错误码；请求密码、临时密码、邀请码明文不会写入审计。读取操作不产生写审计。

运行时服务依赖组件 `closet-service` 使用签名 GET `/api/manage/v1/users?size=1` 检查上游，探测超时为 2 秒。可在运行状态检查界面手动刷新。探测失败只显示通用原因，不包括密钥或完整配置。

## 限制

该适配不缓存洞察结果；超时不会自动重试，避免重复执行或制造不确定的远端行为。联调仍需鱼搭管理接口启用并配置相同 HMAC 凭据。


## 二期完整接入（2026-10-10）

二期接入时鱼搭 `feat/image-search` 已包含 `feat/management-ops-kpi`、全身穿搭、模型内存和图像检索能力。以 `../smart-closet-server/docs/management-api.yaml` 与当前 Controller 为准，二期共 26 个 HTTP 操作；本适配全部支持，1.2 增量见下节。上游路径前缀 `/api/manage/v1` 对应本平台 `/api/v1/closet`，CSV 是同一操作的另一种格式。

| 新增路由（省略 `/api/v1/closet`） | 权限 | 页面及说明 |
| --- | --- | --- |
| `GET /insights/recommendations`、`GET /insights/ai` | `closet:insights:read` | 衣柜洞察中的推荐效果和 AI 用量，支持 username/from/to。 |
| `GET /ops/status` | `closet:ops:read` | 运行监控：健康问题、JVM/物理内存、磁盘、模型、索引、队列、服务商、会话。 |
| `GET /ops/tasks`、`GET /ops/operation-log` | `closet:ops:read` | 参数白名单；页码从 0 起，响应统一 `PageResponse`；status 可重复。无远程重试/取消/改配置入口。 |
| `GET /dashboard/snapshot` | `closet:dashboard:read` | 大屏汇总、24 小时/30 天趋势和分布，不接收 username 筛选。 |
| `GET /exports/daily?format=json` | `closet:exports:read` | 按天宽表封装在 `data` 中，支持 from/to。 |
| `GET /exports/daily?format=csv` | `closet:exports:read` | 保持认证，直接返回 UTF-8 CSV 与 Content-Disposition，不加 JSON 信封。 |
| `GET /exports/events` | `closet:exports:read` | cursor/since/limit/types，types 可重复，游标不改写。 |
| `GET /templates` | `closet:templates:read` | 查看内置和样本模板。 |
| `PATCH /templates/{id}` | `closet:templates:write` | reviewStatus/enabled/bonus 三选至少一项，bonus 0–5，部分更新省略 null；成功/失败记录 `CLOSET_TEMPLATE_REVIEW`，鱼搭同步记录 TEMPLATE_REVIEW。 |

趋势新增 active_users、recommendations_shown、recommendation_likes/dislikes、wear_logs、model_calls/tokens、ootd_samples，粒度新增 month。

大屏与数仓出口保持鱼搭的隐私契约，只给汇总或 userKey；AI topUsers、任务所属用户仅在具备对应洞察/运维权限的管理页面展示。大屏不会复用这类明细接口。网关只允许已知路径和参数，不提供任意 URL 代理。

前端新增组件键为 `closet-ops`、`closet-exports`、`closet-templates`，和原有两个键一起注册到路由及菜单编辑器。大屏按 refreshAfterMs 刷新，最少 30 秒；页面隐藏/退出时暂停，读取失败后由管理员点击重试。模板审核查看与写入权限分离。

## 风格词审核（2026-10-31，鱼搭 #173）

助手从用户对话学到的风格词先只对本人生效，管理员批准后才进入鱼搭全站词表。本平台提供审核入口；鱼搭只返回人数，不返回用户名。上游路径 `/api/manage/v1/style-terms/pending*`。

| 路由（省略 `/api/v1/closet`） | 权限 | 说明 |
| --- | --- | --- |
| `GET /style-terms/pending` | `closet:styleterms:read` | `page`（0 起）、`size`（1–100）；按（词，映射）归并，字段 `term`、`styles`、`hits`、`proposers`、`legacy`、`firstProposedAtMs`、`lastProposedAtMs`、`sharedStyles`；响应另带 `vocabulary`（标准风格表）。 |
| `POST /style-terms/pending/approve` | `closet:styleterms:write` | `{term, proposedStyles, styles?}`；`styles` 修正映射（至多 2 个，须在标准风格内）。词立即对全站生效，替换已有映射。 |
| `POST /style-terms/pending/reject` | `closet:styleterms:write` | `{term, proposedStyles}`；驳回后对提出者本人也不再生效。 |
| `POST /style-terms/pending/batch` | `closet:styleterms:write` | `{action: APPROVE\|REJECT, items: [{term, proposedStyles}]}`，至多 50 项；逐项独立决定、独立审计，返回 `succeeded`、`failed`、`results[{term, ok, code}]`；批量批准沿用助手给出的映射，要改映射请单项批准。 |

审计动作 `CLOSET_STYLE_TERM_APPROVE`、`CLOSET_STYLE_TERM_REJECT`（成功与失败都记，详情含词与映射和错误码），鱼搭同步记 `STYLE_TERM_APPROVE`、`STYLE_TERM_REJECT`。菜单 `closet-style-terms`（组件键同名，权限 `closet:styleterms:read`）。上游 404 `STYLE_TERM_NOT_FOUND`（该词已被审核）转为平台 `NOT_FOUND`。

### 启动配置

鱼搭设置 `CLOSET_MANAGEMENT_ENABLED=true`、`CLOSET_MANAGEMENT_KEY_ID`、`CLOSET_MANAGEMENT_SECRET`（至少 32 UTF-8 字节）；管理后端设置 `APP_CLOSET_ENABLED=true`、`APP_CLOSET_BASE_URL`、`APP_CLOSET_KEY_ID`、`APP_CLOSET_SECRET`。两端 ID 和密钥必须一致。默认仍关闭，不在代码或前端写入真实密钥。

前端开发默认代理到 8081；非默认端口通过 `VITE_API_TARGET` 指向管理后端。浏览器来源需加入运行时配置 `security.cors.allowed-origins`；`APP_SECURITY_CORS_ALLOWED_ORIGINS` 只在新数据库初始化该值，已有数据库请在运行配置界面修改。

验证记录见 `docs/retrospectives/2026-10-10-closet-phase2.md`。

### 鱼搭内部管理页面的边界

鱼搭还存在使用本产品登录会话的 `/api/admin/ootd-samples`（含原图审核与模型提取）、`/api/admin/processing/config`、任务取消及生图能力配置等内部 API。它们不在中立管理契约中，不通过本平台代理；二期完整接入指 1.1 契约中的全部 26 个操作。原图和私密媒体不开放给中立管理接口，运维接口只读。内置 `/api/admin/outfit-templates` 的审核行为已由中立 `/templates` 覆盖并共用存储。


## 1.2 完整账号控制（2026-10-20）

新增 13 个 HTTP 操作，连同 1.1 共支持 39 个操作。新增用户昵称、注册来源/注销筛选、单会话退出、登录锁定查看/解除、用户账号事件，以及注册模式、邀请码和全站账号审计。全部新路由、请求/响应、权限/菜单种子与审计 action 见 [1.2 代理契约](../integrations/closet/accounts-1.2.md)；完整路由权限目录见 [API 参考第 15 节](../API_REFERENCE.md#15-鱼搭管理集成可选)。

新增 `closet:registration:read/write` 授予 SUPER_ADMIN；菜单 `closet-registration`（注册与邀请）与 `closet-account-events`（账号审计）位于鱼搭业务目录。菜单组件键是前端接入约定，本次仅修改管理后端，未实现前端页面。

邀请码创建仅在当前响应透传一次性 `code`，成功审计只保存返回的 `id`；失败审计只保存目标 id 和稳定错误码，不保存请求/响应内容。邀请码 PATCH 只转发非 null 字段，空备注仍保留以清除原值；至少提供一个字段，有效期须在现在之后 90 天内，`maxUses` 不低于已用次数由上游判断。所有路径参数按单个路径段编码。
