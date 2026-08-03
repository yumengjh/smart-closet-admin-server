# 项目目标与交付状态

## 第一版目标

`springboot-start` 是可复用的 Spring Boot 后端工程底座，不是预制 SaaS。第一版必须以 SQLite 直接启动、以 PostgreSQL 部署，并提供账号认证、可扩展 RBAC、服务治理、审计和一个公告业务模块示例。

文档与实现必须同步：路由、请求/响应、启动配置、安全边界和本文件状态随功能改动一起更新。

## 当前已完成

- Java 21、Spring Boot 4.1、Maven Wrapper、SQLite `local` 与 PostgreSQL `postgres` Profile。
- Liquibase 管理用户、RBAC、Refresh Session、运行时配置、审计和公告表；SQLite 外键与 PostgreSQL 迁移测试。
- 全局 API 响应、稳定错误码、Trace ID、可配置 `Powered-By`/`Server` 响应标识、参数校验、未知 JSON 字段拒绝、分页、401/403/500 安全错误处理。
- 注册、登录、RSA JWT Access Token、Refresh Token 哈希存储、轮换、复用检测、当前设备登出、本人会话列表、本人退出全部设备、改密后撤销 Refresh Session，以及可动态关闭的自助注册。
- 当前用户资料读取/修改、改密；管理员分页查询用户、读取详情、状态变更、撤销指定用户会话；最后一个超级管理员保护。
- 后端托管用户头像：稳定 Lorelei 默认头像、随机候选预览/确认、JPEG/PNG/WebP 上传与统一 PNG 转换、管理员重置、不透明公开 URL、本地持久化和内置最终回退。
- 可扩展通用存储模块、本地存储资源状态、存储利用率报告，以及头像候选/孤儿对象 GC 资源；管理后台配置页使用表格管理配置缓存，welcome 页“服务健康”统一展示应用、服务依赖和本地资源状态。
- 运行时设置与持久化 IP 规则使用统一 revision 内存缓存协议，支持提交后即时发布、按 revision 自动对账及全量/单项手动刷新；服务依赖与本地资源检查使用独立调度和接口。
- 数据库 RBAC：角色/权限目录 CRUD、用户角色分配、角色权限分配，以及角色或权限变动后的 Refresh Session 撤销。
- 运行时 CORS、全局/接口级限流、接口禁用、CIDR IP 白黑名单、可配置观察窗口的登录防爆破、请求日志/审计开关、配置审计和审计分页查询。
- 公告示例：公开已发布列表/内容、管理端草稿读取/创建/修改/发布/撤回/删除、乐观锁版本和公告审计。
- `@PublicApi`：只跳过认证，仍通过 Trace ID、IP 策略、接口禁用和限流；公告公开接口可按调用者权限返回最简或管理详情。
- Dockerfile、Compose、GitHub Actions CI、GHCR 镜像扫描/发布基础文件，以及根目录浏览器 API 测试页。
- 管理员用户 `q/status` 组合筛选在数据库分页层执行，用户角色与角色权限采用批量加载，避免列表 DTO 的 N+1 查询。
- CI 使用 70% 行/45% 分支覆盖率防回退；Maven Wrapper 校验官方 SHA-512，Dependabot 按周分组维护 Maven、Actions 和基础镜像依赖。
- 推送语义化版本 Tag 会自动触发 JAR 与镜像发布，并把同一正式版本注入 Maven BuildProperties；产物内部版本、文件名和镜像标签保持一致。
- 应用 Ready 后输出字符框启动摘要，集中展示非敏感运行信息；`Powered-By` 与
  `Server` 默认 `ASP.NET`、`Microsoft-IIS/10.0`，可通过对应环境变量覆盖并
  拒绝控制字符。

## 已完成的安全与运维闭环

1. 外部 RSA 签名/验签密钥：`postgres` Profile 缺失成对密钥会拒绝启动；`local` 仅为开发允许临时密钥。
2. JWT `token_version` 服务端校验：角色、权限、密码与用户状态变化后，旧 Access Token 立即失效。
3. 审计：注册、登录成功/失败、登出、Refresh Token 复用、用户状态、RBAC、运行时配置、IP 规则和公告操作记录结果、目标、Trace ID 与脱敏元数据。匿名失败登录没有可识别 actor；后续若接入设备/客户端身份，可在不记录凭据的前提下扩展。
4. 可信代理与真实客户端 IP：仅来自 `APP_SECURITY_TRUSTED_PROXIES` 的直接代理才解析 `X-Forwarded-For`。
5. 持久化 IP 规则：提供 CRUD、有效期、备注和审计；运行时字符串策略仍作为兼容的全局策略。
6. `/api/v1/system/info`、build/git 元数据、受控 Actuator（health/info/metrics）已提供；Maven 构建会生成 `git.properties`，无 Git 仓库的源码包会安全降级为 `null`。
7. OpenAPI 已声明 Bearer 认证、公开公告接口和通用错误响应组件，并为认证、用户、RBAC、运行时配置、审计、IP 规则、系统信息和公告模块分组/标记；Springdoc 仅在 `local` Profile 默认启用。
8. SQLite 启动、匿名/鉴权烟测和完整 Maven 测试在本地验证；PostgreSQL/Testcontainers 仍依赖可用 Docker，由 GitHub CI 验证。
9. SQL 执行台对写入 CTE/执行计划使用保守危险判定；IP 访问规则采用内存快照；GC 调度暴露最近执行状态并接入 Actuator 健康检查；公告管理列表使用分页。
10. 测试数据库与本地数据完全隔离；运行时配置/IP 快照仅在事务提交后切换；GC 资源失败会回滚业务写入并持久化失败运行记录；全局限流按客户端 IP 聚合，端点策略作为第二层独立桶。
11. PostgreSQL 部署默认使用 Secure Refresh Cookie，并拒绝通配 credentialed CORS；SQL Console 默认关闭；非健康 Actuator 端点要求 `system:config:read`。

## 有意不包含在第一版

- MySQL、SQL Server、运行时数据库切换、Redis/分布式限流、对象存储/CDN 后端。
- 邮箱验证、验证码、找回密码、OAuth、MFA。
- 面向具体服务器/云平台的部署工作流。

## 验收标准

- 新开发者按 README 能用 SQLite 启动并登录初始超级管理员。
- PostgreSQL Profile 的迁移和核心测试由 CI 验证（本机没有 Docker 时此项不伪称已通过）。
- 用户、RBAC、运行时配置、公告公开/管理接口的权限和错误契约有测试覆盖。
- Access Token 可由外部密钥签发/验签，且在用户授权版本变化后立即被拒绝。
- 所有安全与管理动作写入可查询、脱敏的审计记录。
- 文档、OpenAPI 与实际路由保持一致。
