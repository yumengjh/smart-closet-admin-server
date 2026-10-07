# 鱼搭管理集成 M4 复盘

日期：2026-10-07

交付方式：单次提交 `feat: add optional closet management integration`

## 一、背景与目标

start 需要接入鱼搭的中立管理 API，为后台提供业务用户与衣柜洞察能力，同时保留默认关闭和可移除边界。按已确认的 M4 设计实现带 HMAC 的上游客户端、权限与菜单、审计、错误转换、健康检查和配套文档。

## 二、范围

### 纳入

- `modules/integrations/closet` 三层模块、启动期配置校验、签名 RestClient、路由、错误映射、审计和运行依赖探测。
- 默认关闭时无路由、client、权限或菜单；启用时向 SUPER_ADMIN 幂等授予权限并种入导航。
- SQLite 隔离测试和文档/环境变量同步。

### 排除

- fish closet 服务端和 admin 前端仓库；本次只修改 `smart-closet-admin-server`。
- GitHub 模板目录、Tag、Push 与 Docker 镜像构建。

## 三、实施内容

### 1. 可插拔集成模块

- 新增 `app.integrations.closet.*` 配置与启用校验，默认关闭。
- 实现 RFC 3986 query 规范化、HMAC-SHA256/base64url、随机 nonce、操作人及 Trace ID 头。
- 新增用户与洞察控制器、平台分页适配、请求白名单、错误转换与脱敏写审计。
- 新增条件化的 RBAC/导航种子与两秒签名健康探测。
- 扩展平台错误枚举与 ApiException 以保留上游字段校验信息。

### 2. 配置与文档

- 同步 `.env.example`、Compose、部署说明、README、API 参考和项目状态。
- 增加签名测试向量以及隔离数据库 API 测试。

### 3. 审查与联调修复

- 规范化 query 改为先 RFC 3986 编码再排序，与鱼搭校验端一致；原实现按原文排序，遇到非 ASCII 值时签名不匹配。新增 `sortsEncodedPairsLikeTheClosetVerifier` 回归测试。
- `StarterApplicationTest` 的默认关闭用例显式固定 `app.integrations.closet.enabled=false`，并在类加载时删除跨运行保留的 `target/starter-application-test.db`。原用例会读到开发者本地 `.env`（联调时开启了集成）以及上次开启运行留下的权限种子而误报失败。
- `ClosetProblemTranslator` 先判断 5xx：鱼搭繁忙（`503 MANAGEMENT_BUSY`）应为 `CLOSET_SERVICE_UNAVAILABLE`，不再被误判为集成配置错误；删除重复的 5xx 分支。

## 四、验证证据

| 验证 | 结果 | 备注 |
| --- | --- | --- |
| Closet 定向 API 测试 | 通过 | 与签名/配置/错误转换测试一并纳入全套 147 个测试；HTTP stub 使用 `MockRestServiceServer` |
| `./mvnw verify` | 受沙箱限制 | 全量执行遇到两个既有 loopback HTTP 测试类共 5 个 bind 错误（`Operation not permitted`）；排除这两个类后 147 个测试通过（0 failures/errors，1 skipped），JaCoCo coverage gate 通过。启用 Mockito 和 JaCoCo javaagents，Maven 缓存使用 `/private/tmp` 副本 |
| `docker compose config --quiet` | 通过 | 仅解析配置，没有构建镜像 |
| `git diff --check` | 通过 |  |
| 本机完整 `./mvnw verify`（审查修复后） | 通过 | 在非沙箱环境运行，包含两个 loopback 测试类：155 个测试通过（0 failures/errors，1 skipped），JaCoCo 门槛通过 |
| 跨服务联调（M6） | 通过 | 鱼搭 18080 开启管理接口、本服务 18090 开启集成、admin 前端 8849：登录、业务用户分页与详情、全站与单用户洞察、按周趋势、禁用用户、额度校验错误、404 映射、中文 query 签名均正常；停止鱼搭时返回 `503 CLOSET_SERVICE_UNAVAILABLE`，其他后台功能正常，恢复后自动恢复 |

## 五、问题与过程复盘

- 默认 Maven 缓存目录不可写，Maven 尝试更新 `~/.m2` 的 resolver 状态时失败；将现有依赖仓库复制到 `/private/tmp` 并通过 `-Dmaven.repo.local` 执行，不改动仓库外的用户缓存。
- JDK HttpServer 无法在当前沙箱绑定 loopback；切换为设计允许的 `MockRestServiceServer`。
- 仓库中原有的 `ContainerHealthCheckTest` 与 `DiceBearAvatarGeneratorTest` 也使用 JDK HttpServer，因此无法在这个沙箱完成绑定。没有为环境限制改动这两组无关测试；其余完整测试集及覆盖率门槛均通过。
- MockRestServiceServer 不允许请求开始后再追加 expectation；测试已调整为每个场景先登记完整 expectation 集合。

## 六、风险与搁置项

- Postgres/Testcontainers 能否执行以最终运行结果为准。
- 上游响应数据按 JSON 原样透传，字段由鱼搭契约（`docs/integrations/closet/management-api.yaml` 快照）决定；鱼搭契约升级时需同步快照。
- 模板既有问题：`.env` 作为 properties 导入，其中的 `SERVER_PORT` 不会映射为 `server.port`，本地联调需用系统环境变量或 `--server.port` 指定端口。本次未修改模板行为。

## 七、主要文件

| 范围 | 文件 | 说明 |
| --- | --- | --- |
| 模块 | `src/main/java/com/yumg/starter/modules/integrations/closet/` | API、应用服务、配置、签名与上游转换 |
| 通用 API | `src/main/java/com/yumg/starter/common/api/` | 三个错误码与上游 validation violations |
| 配置 | `.env.example`、`compose.yaml`、`src/main/resources/application.yml` | 环境变量与默认关闭 |
| 测试 | `src/test/java/com/yumg/starter/modules/integrations/closet/` | 签名、配置、错误转换及 stub API 测试 |
| 文档 | `docs/api/closet-integration.md`、`docs/API_REFERENCE.md`、`docs/PROJECT_STATUS.md`、`docs/operations/deployment.md`、`README.md` | API、安全、部署与功能状态 |

## 八、结论与后续

M4 的代码和文档已完成，审查发现的签名排序与错误映射问题已修复；本机完整 verify 与跨服务联调通过。未发布、未进行 Docker 构建。
