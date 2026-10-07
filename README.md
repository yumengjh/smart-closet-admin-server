# Spring Boot 后端启动模板

一个面向真实 REST API 项目的 Java 21 / Spring Boot 4.1 模板。它采用模块化单体结构，提供 SQLite 本地开发、PostgreSQL 部署、Liquibase、JWT、Refresh Token、RBAC、运行时服务配置、容器化与 CI 基础。

这不是完整 SaaS，也不包含前端；目标是以后新项目可以直接从这里开始。

## 适用边界

适合需要账号体系、管理端权限、审计与基础服务治理的中小型 REST API，或者作为 Spring Boot 业务项目的统一工程起点。它优先保持模块化单体和清晰事务边界，不默认引入 Redis、消息队列、Kubernetes、完整链路追踪等只有在特定部署规模下才成立的设施。

如果项目只是几个无状态公开接口，这个模板可能偏重；如果已经确定要独立拆分多个服务，则应复用其中的安全和 API 契约，而不是把整个单体原样复制多份。

## 环境要求

- JDK 21
- `./mvnw` 可自动下载 Maven；Windows 可使用 `mvnw.cmd`
- Docker 仅在 PostgreSQL、镜像构建和 Testcontainers 测试时需要

## SQLite 本地启动

默认使用 `./data/app.db`：

```bash
cp .env.example .env
# 编辑 .env，按需填写 APP_BOOTSTRAP_ADMIN_USERNAME 和 APP_BOOTSTRAP_ADMIN_PASSWORD
./mvnw spring-boot:run
```

服务默认监听 <http://localhost:8080>。检查健康状态：

```bash
curl http://localhost:8080/actuator/health
```

应用完成启动后会打印一个字符框摘要，集中显示应用名、版本、Profile、实际端口、
健康检查/OpenAPI 地址、数据库类型、响应标识和启动耗时。摘要只展示非敏感运行
信息，不会输出数据库密码、JWT 密钥或管理员凭据。

`.env` 会被 Spring Boot 直接加载，适合保存启动期配置；密码不会写入源码、示例文件或日志：

```bash
APP_BOOTSTRAP_ADMIN_USERNAME=admin
APP_BOOTSTRAP_ADMIN_PASSWORD=请替换为强密码
```

用户头像由后端统一托管。默认连接本机 `3100` 端口的自托管 DiceBear Lorelei，
生成结果和上传头像都写入本地通用存储；前端只消费 `avatarUrl`。本地开发可在
`.env` 调整：

```bash
APP_AVATAR_GENERATOR_BASE_URL=http://localhost:3100
APP_STORAGE_LOCAL_ROOT=./data/storage
```

DiceBear 未启动时，应用仍可正常注册、登录和读取资料，并返回内置回退头像；
此时不能创建新的随机系统头像。上传支持静态 JPEG、PNG、WebP，后端统一裁剪并
保存为 256×256 PNG。

所有 HTTP 响应默认包含以下展示标识：

```http
Powered-By: ASP.NET
Server: Microsoft-IIS/10.0
```

需要替换显示值时修改 `.env`：

```bash
APP_RESPONSE_POWERED_BY=PHP/8.4
APP_RESPONSE_SERVER=nginx
```

这些值在启动时读取，修改后需要重启；空值分别回退为 `ASP.NET` 和
`Microsoft-IIS/10.0`，控制字符或超过 128 字符的值会使配置校验失败，避免
响应头注入。它们只是可配置的展示标识，不能作为真实技术栈、安全能力或服务
版本的判断依据。

若用户名不存在，服务会创建该用户并授予 `SUPER_ADMIN`。该初始化是幂等的。

本地管理员已被锁定或忘记密码时，可临时设置
`APP_BOOTSTRAP_ADMIN_RESET_PASSWORD=true` 后重启服务；它会重置 `.env` 中指定账号的密码并解除锁定。恢复成功后应立即改回 `false`。

新建本地数据库默认不允许跨域来源，也不开启 SQL Console。需要连接相邻前端时，在第一次创建数据库前设置：

```bash
APP_SECURITY_CORS_ALLOWED_ORIGINS=http://localhost:5173
```

已有数据库通过 `security.cors.allowed-origins` 运行时配置修改，不会被环境变量覆盖。仅在明确需要本地 SQL 调试时设置 `APP_SQL_CONSOLE_ENABLED=true`；该能力还会校验 `local` Profile 和 `system:sql:execute` 权限，关闭时执行接口返回 404。

## 已实现功能

- 注册、登录、RSA JWT、Refresh Token 轮换、当前设备登出、本人会话列表与退出全部设备
- 用户查看本人资料、修改显示名、修改密码
- 后端托管头像：Lorelei 默认头像、随机候选预览/确认、JPEG/PNG/WebP 上传、管理员重置、公开不透明媒体 URL 和内置故障回退
- 数据库 RBAC：初始角色/权限、角色与权限目录 CRUD、用户角色分配、角色权限分配
- 运行时服务治理：动态 CORS、接口禁用、全局/端点/控制面限流、结构化 CIDR IP 规则、自动临时封禁、登录防爆破、请求日志开关、注册开关、DiceBear/本地存储组件探测及审计查询
- 可选的鱼搭管理集成：HMAC-SHA256 签名客户端、业务用户管理、额度调整、衣柜洞察、RBAC 权限与动态导航，默认关闭
- 统一错误响应、Trace ID、可配置 `Powered-By`/`Server` 响应标识、字符化启动摘要、SQLite/PostgreSQL Liquibase 迁移
- Docker、Compose、GitHub Actions 基础文件

## 架构设计

业务代码按模块组织，每个模块内部保持三层：

```text
HTTP request
  -> api（Controller、DTO、参数与权限校验）
  -> application（用例、事务、业务规则）
  -> infrastructure（JPA Repository）
  -> SQLite / PostgreSQL
```

`common` 只放跨模块的响应、异常、实体基类和 Web 基础设施，`config` 负责 Spring Security、JWT、Bootstrap 与生产启动校验。Controller 不直接访问其它模块的 Repository，ArchUnit 测试会守住基础边界。

请求在进入 Controller 前依次经过 Spring Security 链中的 Trace ID、接口策略、IP 规则、限流、JWT 认证与请求日志等能力。所有业务接口默认认证；`@PublicApi` 只显式放开身份认证，不绕过其它保护。

运行时配置、IP 规则等高频读取使用不可变内存快照，数据库仍是事实源。写入成功提交后才刷新快照，事务回滚不会把未提交配置暴露给请求线程。GC 等需要保留失败结果的任务将业务事务、运行状态和失败审计拆开，确保资源清理失败时业务写入回滚而失败记录仍可查询。

JPA 默认关闭 Open Session in View。DTO 需要关联集合时由应用服务显式批量加载：管理员用户分页固定按页加载角色，角色目录一次加载权限，避免在序列化阶段产生 N+1。

## Profile 与数据

| 场景 | Profile | 数据库 | JWT | OpenAPI |
| --- | --- | --- | --- | --- |
| 本地开发 | `local`（默认） | `./data/app.db` | 可临时生成 RSA 密钥 | 默认启用 |
| 部署 | `postgres` | PostgreSQL | 必须提供稳定私钥/公钥 | 默认关闭 |

两种数据库共用 Liquibase changeset；不要使用 Hibernate 自动更新 Schema。`local` 适合零依赖启动，PostgreSQL Profile 才是部署目标。测试数据库位于 `target` 或 JUnit 临时目录，与 `data/app.db` 完全隔离。

详细接口与边界见：

- [全量 API 参考手册](docs/API_REFERENCE.md)
- [认证与会话](docs/api/authentication.md)
- [RBAC 权限](docs/api/rbac.md)
- [运行时服务配置](docs/operations/runtime-settings.md)
- [IP 访问控制与自动安全防护](docs/operations/ip-security-protection.md)
- [公告示例模块](docs/api/announcements.md)
- [项目目标与进度](docs/PROJECT_STATUS.md)
- [部署说明](docs/operations/deployment.md)

## 接口测试页

管理后台位于相邻的 `springboot-admin` 项目：它通过 Vite 代理调用本服务，并使用 HttpOnly Refresh Cookie 实现登录、记住我、注册、自动刷新与退出。根目录的 `api-test.html` 用于其它 API 的手动调试；认证验证请使用管理后台，Refresh Token 不再暴露给浏览器 JavaScript。

配置受信任来源后，跨域预检支持 `GET`、`POST`、`PUT`、`PATCH`、`DELETE` 和 `OPTIONS`；因此用户状态更新、角色分配和角色权限更新都可以直接在测试页执行。生产环境必须使用明确来源，`postgres` Profile 会拒绝通配来源和不安全的 Refresh Cookie 配置。

## 主要路由

| 模块 | 路由 |
| --- | --- |
| 认证 | `/api/v1/auth/register`、`login`、`refresh`、`logout` |
| 当前用户 | `/api/v1/users/me` |
| 头像媒体 | `/api/v1/avatars/{publicId}`、`/api/v1/avatar-candidates/{candidateId}` |
| RBAC | `/api/v1/rbac/**` |
| 运行时配置 | `/api/v1/system/runtime-config` |
| 配置缓存 | `/api/v1/system/runtime-caches` |
| 运行状态检查 | `/api/v1/system/runtime-checks` |
| 系统审计 | `/api/v1/system/audit-events` |
| 公告示例 | `/api/v1/announcements` |
| 鱼搭业务用户与洞察（可选） | `/api/v1/closet/users/**`、`/api/v1/closet/insights/**` |
| 健康检查 | `/actuator/health` |
| OpenAPI | `/v3/api-docs`、`/swagger-ui/index.html` |

## API 响应约定

`/api/v1/**` 的成功响应（`204 No Content` 除外）默认统一为：

```json
{
  "data": {},
  "traceId": "请求追踪 ID",
  "timestamp": 1783814400000
}
```

错误响应使用 `code`、`message`、`traceId`、`violations`、`timestamp`。所有对外时间字段使用 Unix 毫秒时间戳，避免 ISO 字符串格式不一致。认证、授权、限流和 Controller 内异常共用这一错误结构；客户端排查时应携带 `traceId`。

所有 Servlet 响应（包括业务 API、认证错误、Actuator 和静态资源）统一带有
`Powered-By` 与 `Server` 响应头，其值分别由启动环境变量
`APP_RESPONSE_POWERED_BY`、`APP_RESPONSE_SERVER` 决定。

默认情况下，所有业务接口都需要 Access Token。要开放一个外部接口，在 Controller 方法或类上标记 `@PublicApi`；它只跳过身份认证，仍然会经过 Trace ID、IP 访问控制、接口禁用和限流。公开内容需要最简响应时可使用 `@PublicApi(minimalResponse = true)`，例如公告公开列表；详情见 [公告示例模块](docs/api/announcements.md)。

请求 DTO 使用 Bean Validation，并启用严格 JSON 反序列化：未知字段会返回 `400 UNKNOWN_FIELD`，不会被静默忽略。路径中的用户 ID、角色和权限编码也会做格式校验。

## 构建与测试

```bash
./mvnw test
./mvnw verify
```

`verify` 会运行单元、SQLite 集成、架构和可用的 Testcontainers 测试，生成 JaCoCo 报告，并阻止行覆盖率低于 70%或分支覆盖率低于 45%的回退。本机没有 Docker 时，PostgreSQL/Testcontainers 测试需要交给 GitHub CI；SQLite 测试可直接运行。

构建正式版本时传入 `revision`，它会进入 Spring Boot BuildProperties 和 `/api/v1/system/info`：

```bash
./mvnw -Drevision=1.2.3 verify
```

推送 `v1.2.3` Tag 会同时触发同版本 JAR 和容器镜像工作流。镜像在 GitHub
Actions 中构建、扫描，通过后推送同一份归档；一般不在开发机本地构建应用镜像。
完整流程见[部署说明](docs/operations/deployment.md)，每次提交的变更复盘见
[复盘索引](docs/retrospectives/README.md)。

## 从模板开始新项目

建议在第一次业务提交前完成：

1. 修改 Maven `groupId`、`artifactId`、项目名称和 Java 根包名；
2. 修改 OpenAPI 标题、Compose 服务名、镜像名及文档中的示例；
3. 决定保留或删除公告等示例业务模块；
4. 生成生产 RSA 密钥并放入部署平台的 Secret，不放入仓库；
5. 设置明确的前端 CORS 来源和可信代理范围；
6. 用空 SQLite 和空 PostgreSQL 各执行一次迁移与核心烟测。

代码约束、模块扩展步骤和文档同步规则见 [AGENT.md](AGENT.md)。

## 当前重要限制

- `local` Profile 可临时生成 JWT 密钥方便开发；`postgres` Profile 必须配置一对 RSA 私钥/公钥（`APP_JWT_*_PEM` 或 `APP_JWT_*_PATH`）。用户状态、密码、角色或权限变化会递增 `token_version`，旧 Access Token 会立即被拒绝。
- Refresh Cookie 在 `postgres` Profile 默认启用 `Secure`；只有健康端点公开，`info`、`metrics` 等其余 Actuator 端点需要 `system:config:read`。
- 结构化 IP 规则和自动封禁结果会持久化，但限流、自动封禁触发计数和防爆破计数仍是有界的单实例内存状态；多实例部署时应将计数器替换为 Redis 等共享实现。仅来自 `APP_SECURITY_TRUSTED_PROXIES` 的代理才会读取 `X-Forwarded-For`。
- 不包含 MySQL、SQL Server、Redis、邮件验证、OAuth、MFA 和前端。
