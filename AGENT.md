# Spring Boot Starter 维护说明

本文面向维护者和后续开发代理，说明仓库的真实边界、关键约束和推荐改动方式。用户入口、启动命令和功能介绍以 `README.md` 为准；接口契约以 `docs/API_REFERENCE.md` 和 Controller/DTO 为准。

## 项目定位

这是 Java 21 + Spring Boot 4.1 的模块化单体启动模板，不是完整 SaaS，也不预设微服务基础设施。默认目标是：

- 新项目克隆后可直接用 SQLite 开发；
- 使用 PostgreSQL Profile 部署；
- 自带认证、会话、RBAC、审计和常见服务治理基线；
- 用公告展示示例业务模块的组织方式；
- 保持单体内聚，等实际规模需要时再引入 Redis、消息队列或分布式追踪。

项目尚未生产使用，不承担旧数据库或旧接口的向后兼容。修改现有契约时仍需同步代码、测试和文档，避免模板内部自相矛盾。

## 快速验证

在仓库根目录执行：

```bash
./mvnw test
./mvnw verify
docker compose config --quiet
git diff --check
```

`verify` 是提交前基线：它运行单元/集成/架构测试、生成 JaCoCo 报告，并检查行覆盖率不低于 70%、分支覆盖率不低于 45%。PostgreSQL Testcontainers 用例在 Docker 不可用时可以跳过，交付说明必须如实标注。

测试必须使用 `target/*-test.db` 或 JUnit 临时目录，不得读写 `data/app.db`。不要为了跑测试删除开发者的本地数据库。

`docker compose config --quiet` 只解析和校验 Compose 配置，不会构建或启动镜像，可以作为本地静态检查。不要把它替换为 `docker compose build` 或 `docker build`。

## 目录与职责

```text
src/main/java/com/yumg/starter/
├── common/           跨模块 API、实体基类和 Web 基础设施
├── config/           Spring Security、JWT、启动校验与 Bootstrap
├── entities/         当前共享的 JPA 实体
└── modules/
    └── <module>/
        ├── api/              Controller 与请求/响应 DTO
        ├── application/      用例、事务边界和业务规则
        └── infrastructure/   Repository 与外部实现

src/main/resources/
├── application.yml
├── application-local.yml
├── application-postgres.yml
└── db/changelog/     Liquibase 唯一 Schema 来源
```

Controller 负责 HTTP 映射、校验和权限声明，不直接编排 Repository。应用服务持有事务和业务规则；Repository 只处理持久化查询。`common` 不能依赖业务模块。`ArchitectureRulesTest` 会检查其中一部分边界。

当前实体集中在 `entities` 包是模板现状，不要同时维护第二套领域模型。新增独立业务模块时，优先保持 `api/application/infrastructure` 三层结构；只有在实体数量和模块隔离价值明确时再迁移实体所有权。

## 请求与安全不变量

所有 `/api/v1/**` 默认需要认证。公开接口必须显式使用 `@PublicApi`；该注解只跳过认证，Trace ID、IP 规则、接口开关和限流仍会执行。`minimalResponse = true` 只用于确实需要无信封响应的公开内容接口。

受保护请求使用短期 RSA JWT Access Token，Refresh Token 只通过 HttpOnly Cookie 传递并在数据库中哈希保存。以下操作必须让旧凭据失效：

- 修改密码或用户状态；
- 分配/移除用户角色；
- 增删角色权限；
- 显式撤销用户会话。

实现时同时维护 `token_version` 和 Refresh Session 撤销，不要只处理其中一种 Token。

生产安全边界：

- `postgres` Profile 必须配置稳定的 RSA 私钥和公钥；
- Refresh Cookie 必须为 `Secure`；
- credentialed CORS 不允许 `*`；
- 只有 `/actuator/health/**` 匿名可读，其余 Actuator 端点需要 `system:config:read`；
- SQL Console 默认关闭，且只能在 `local` Profile 使用；
- 只有受信任代理地址才允许提供客户端 IP 转发头。

全局 Servlet 响应必须带 `Powered-By` 与 `Server` Header。它们分别由
`app.response.powered-by` / `APP_RESPONSE_POWERED_BY` 和
`app.response.server` / `APP_RESPONSE_SERVER` 在启动期配置，默认
`ASP.NET` 与 `Microsoft-IIS/10.0`。Header 名称不得改成 `X-*`；值必须保持
单行且不超过 128 字符，不能把未经校验的用户输入直接写入响应头。这些标识只是
可配置展示信息，不参与认证、授权或技术栈探测逻辑。

不要放宽这些边界来简化测试。为测试提供隔离配置或显式构造依赖。

## 数据库与事务不变量

Liquibase changeset 是 SQLite 和 PostgreSQL 的 Schema 事实源。Hibernate 在 PostgreSQL 下使用 `validate`，不能用 `ddl-auto=update` 替代迁移。已执行的 changeset 不应修改；当前项目虽不要求旧版本兼容，仍应新增 changeset，让空库和升级路径保持同一套行为。

列表 DTO 若读取 `@ManyToMany` 或其它懒加载集合，应显式设计批量查询。分页集合不要直接 collection fetch join；优先分页查询主记录，再按当页 ID 一次加载关联并按原顺序组装。

运行时配置和 IP 规则同时存在数据库状态与内存快照。只有事务成功提交后才能替换或失效快照，回滚事务不能泄漏未提交值。多项关联设置使用同一事务批量更新，避免观察到中间状态。

GC 执行采用“编排服务 + 独立事务工作器”：

- 运行记录先以 `RUNNING` 独立提交；
- 资源清理在独立事务中执行；
- 失败时业务写入回滚；
- `FAILED` 状态与失败审计分别在新的事务中持久化。

不要给外层编排方法重新加一个覆盖全流程的事务。

## 查询与分页

分页响应统一使用 `PageResponse` 的 `items/page/size/totalElements/totalPages`。筛选必须进入数据库查询，不能先分页再在内存中过滤，否则内容和总数会不一致。

管理员用户列表同时支持 `q` 与 `status`。其查询先得到当页用户，再一次加载当页角色；角色目录一次加载权限。新增 DTO 字段时检查是否重新引入 N+1。

## 配置来源

启动期配置来自 YAML、环境变量和可选 `.env`。运行期可变项来自 `runtime_settings`，由 `RuntimeSettingService` 提供快照读取。

选择配置位置时遵循：

- 数据库连接、密钥、Cookie 安全、可信代理、响应展示标识：环境变量；
- CORS 来源、接口开关、限流、注册开关、请求日志：运行时设置；
- 编译期依赖和构建版本：Maven；
- 不允许把真实密码、Token、PEM、数据库文件提交到仓库。

新增环境变量时同步 `.env.example`、`compose.yaml`、README 或部署文档。新增运行时设置时同步初始化数据、类型/范围校验和 `docs/operations/runtime-settings.md`。

启动完成摘要由 `StartupSummaryLogger` 在 `ApplicationReadyEvent` 后输出。摘要可
使用 Unicode 框线增强可读性，但不要为纯字符排版增加第三方依赖；只允许显示
应用名、构建版本、Profile、实际端口、健康/OpenAPI 地址、数据库类型、响应标识
和耗时等非敏感信息。新增字段前先确认不会泄露密码、Token、密钥、完整连接串或
其它 Secret，并覆盖控制字符和超长文本。

## 新增业务模块

推荐顺序：

1. 创建 Liquibase changeset，并加入 master changelog；
2. 添加实体与 Repository；
3. 在 application 层实现用例和事务；
4. 添加请求/响应 DTO 与 Bean Validation；
5. 添加 Controller、`@PreAuthorize` 或显式 `@PublicApi`；
6. 如需新权限，更新 RBAC Bootstrap 和导航数据；
7. 覆盖成功、鉴权、校验、冲突与事务回滚；
8. 更新 API 文档和项目状态。

示例业务可以删除，但删除时要连同 changeset 后续状态、权限种子、导航种子、测试和文档一起处理，不能只删 Controller。

## 构建、版本与发布

POM 使用 CI-friendly `revision`：

```bash
./mvnw -Drevision=1.2.3 verify
```

普通本地构建默认是 `0.0.1-SNAPSHOT`。推送精确的
`vMAJOR.MINOR.PATCH` Tag 后，JAR 与镜像工作流都会去掉 `v` 并把同一版本传给
Maven/Docker，因此 JAR BuildProperties、系统信息、文件名和镜像标签一致。

Maven Wrapper 下载必须通过 `maven-wrapper.properties` 中的 Apache SHA-512。升级 Maven 时同时更新 URL、校验值，并在空的 `MAVEN_USER_HOME` 下验证下载。Dependabot 每周按 Maven、GitHub Actions 和 Docker 三组提出升级，合并前仍需完整 `verify`。

镜像构建上下文忽略 `.env`、私钥、证书和本地数据。若新增敏感文件类型，要同步 `.dockerignore` 和 CI 的秘密扫描规则。

### Docker 本地与远端约定

- 一般开发和提交验收不得在本地执行 `docker build`、`docker compose build` 或启动应用镜像。
- 本地只运行 Maven、单元/SQLite 测试、YAML 检查和 `docker compose config --quiet`。
- PostgreSQL Testcontainers 只有在本机镜像环境明确可用且任务确实需要时才运行；否则交给 CI。
- 正式镜像只通过推送 `vMAJOR.MINOR.PATCH` Tag 触发 `.github/workflows/image.yml` 构建。
- 镜像工作流先构建一次归档，扫描同一归档，通过后才推送 GHCR，不允许扫描后再次重建。
- 创建或推送 Tag 是外部发布动作，必须获得用户明确授权；不能因为代码验证通过就自行发布。

获准发布后的典型命令：

```bash
git tag v1.2.3
git push origin v1.2.3
gh run list --workflow image.yml --limit 5
gh run list --workflow release-jar.yml --limit 5
gh run watch <run-id> --compact --exit-status
gh run view <run-id> --log-failed
```

`gh` 只用于观察或按明确授权处理远端工作流，不用它绕过 Tag 版本校验。

## 工程配置文件说明

### `.github/dependabot.yml`

这是 GitHub Dependabot 的版本更新策略，不参与编译和应用运行。当前每周检查：

- Maven 依赖和插件；
- GitHub Actions；
- Dockerfile/Compose 引用的镜像。

同一生态的更新被分组为一个 PR，降低通知噪声。Dependabot 只提出修改，不代表升级安全或兼容；合并前仍需审查变更说明并运行完整 `verify`。

### `.mvn/wrapper/maven-wrapper.properties`

该文件固定项目使用的 Maven 发行版本和下载地址。当前 `only-script` Wrapper 还记录 Maven ZIP 的 SHA-512，`mvnw`/`mvnw.cmd` 首次下载后会计算指纹，不匹配就拒绝解压执行。

它保护的是“下载到的 Maven 发行包与项目审核过的字节是否一致”，不是 POM 依赖锁文件。它不能验证每个第三方依赖是否无漏洞，也不能替代 HTTPS、仓库权限、依赖审查或 CI。

### `compose.yaml`

该文件描述 PostgreSQL 与应用容器的组合运行拓扑：

- `postgres` 定义数据库镜像、账号/数据库、健康检查和持久化卷；
- `app` 定义应用构建上下文、PostgreSQL/JWT/安全环境变量、端口和健康检查；
- `depends_on.condition=service_healthy` 让应用等待数据库健康；
- `postgres-data` 让数据库数据在容器重建后保留；
- `app` 位于 `postgres` Compose Profile，只有显式启用时才参与启动。

它不是生产平台部署方案，也不会被 Spring Boot 直接读取。环境变量由 Compose 展开后传给容器；新增生产变量时需要同时更新 `.env.example` 和 `compose.yaml`。

## 文档维护

| 改动 | 至少同步 |
| --- | --- |
| 启动方式、功能或边界 | `README.md` |
| 路由、参数、响应或错误码 | `docs/API_REFERENCE.md` |
| 认证/RBAC/公告契约 | 对应 `docs/api/*.md` |
| 环境变量、部署、发布 | `docs/operations/deployment.md`、`.env.example` |
| 运行时设置 | `docs/operations/runtime-settings.md` |
| 交付能力和限制 | `docs/PROJECT_STATUS.md` |
| 维护约束 | `AGENT.md` |
| 每次提交的原因、范围和验证 | `docs/retrospectives/` |

历史计划位于 `docs/superpowers`，只用于理解决策背景，不是当前事实源。发生冲突时以当前代码、测试、README 和正式运维/API 文档为准。

## 提交复盘规范

从本规范建立后，每个项目提交必须在同一个提交内包含一篇
`docs/retrospectives/YYYY-MM-DD-NNN-<slug>.md`：

- 日期使用提交所在地日期，`NNN` 是当天从 `001` 开始的三位序号；
- `<slug>` 使用小写英文、数字和连字符，描述提交主题；
- 一篇复盘只对应一个提交；不要用同一文档持续追加多个提交；
- 必填内容包括背景、目标、范围、逐项改动、验证证据、风险/搁置项和文件清单；
- 不能伪造未执行的测试，也不能写入 Token、密码、私钥或生产数据；
- 同步更新 `docs/retrospectives/README.md` 索引；
- 以 `docs/retrospectives/_template.md` 为起点，不修改模板来记录具体提交。

复盘文档与代码一起暂存和提交。提交前若发现用户已有的无关修改，复盘应明确排除，且不得把该文件纳入提交。

## 提交前检查

- 查看 `git status --short`，保留不属于当前任务的用户修改；
- 为本次提交创建并索引唯一复盘文档；
- 运行与改动直接相关的定向测试；
- 运行 `./mvnw verify`；
- 检查 `data/app.db` 未被测试触碰；
- 运行 `docker compose config --quiet` 和 `git diff --check`；
- 检查文档没有声称本机未实际完成的 Docker/PostgreSQL 验证；
- 不在本地构建应用镜像；需要发布时使用经授权的版本 Tag 和远端工作流；
- 提交只包含本轮有意修改，不顺手格式化无关文件。
