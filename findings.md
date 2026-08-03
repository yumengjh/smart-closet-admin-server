# Findings

- `AuthenticationService.refresh` 在默认事务中调用 `TokenService.rotate`；后者重用令牌时抛出 `ApiException`，会回滚家族撤销与审计。
- `compose.yaml` 现已显式转发 PEM/文件路径两种 JWT 密钥变量；Compose 不会猜测或挂载宿主机密钥路径，文件路径方案需部署方显式提供只读 secret volume。
- 请求结构化日志、`security.request-log.enabled`、防爆破观察窗口和 identity 注册开关已通过运行时设置实现。
- Maven 的 `git-commit-id-maven-plugin` 由 Spring Boot 4.1 的依赖管理提供，构建会生成受控的 `git.properties`；OpenAPI 已补通用错误组件。
- 角色/权限 CRUD、会话列表和本人退出全部设备已实现，状态文档不再将其列为非目标。
- `springboot-admin` 是独立 Vue 3/Element Plus 项目，当前认证仍指向模板假接口；其 Vite 开发端口为 8848，动态菜单接口可在本次保持假实现。
- 后端已有登录、注册、Token 轮换和退出能力，但 Refresh Token 当前出现在 JSON 响应与请求体；本次改为 HttpOnly Cookie，前端仅在内存中保留 Access Token。
- SQLite 中 Liquibase 的 `addColumn` 会重建 `refresh_sessions` 并丢失二级索引；新增 007 变更集恢复唯一 `token_hash` 索引和查询索引。
- 当前前端 `getAsyncRoutes()` 被临时实现为返回空数组；路由转换器则直接将后端 `component` 字符串映射到 `src/views`。缺少映射时会产生 `undefined` 组件，是“加载中”状态的高风险来源。
- 后端 RBAC 已有角色、权限、用户角色和角色权限 CRUD，但没有菜单/路由实体、菜单读取接口或菜单管理 API；菜单功能应成为独立模块并仅引用现有权限编码。
- 2026-07-16 运行性审查：`RateLimitService` 与 `BruteForceService` 的内存 Map 没有过期清理；`refresh_sessions` 缺少按 `user_id/issued_at`、`family_id`、`expires_at` 的维护索引，且失效会话没有生命周期清理。GC 需要以“资源”而不是裸表为扩展单位，避免针对任意表的危险通用删除。
- SQL 调试能力仅面向当前 local 开发阶段：它必须由 Profile、显式配置、RBAC 三层共同保护，并默认屏蔽敏感列值；不能作为生产数据库后门。

## 头像能力规划（2026-08-03）

- 当前 `User` 实体、Liquibase `users` 表、个人资料请求/响应和管理员用户响应均没有头像字段；现有个人资料修改只覆盖 `displayName`。
- 用户选定 DiceBear `Lorelei`。官方 10.x Lorelei 输出为 SVG，样式为 CC0 1.0；官方自托管 API 使用 Fastify，可通过 `dicebear/api:4.9.0` 运行。
- 已核对的包规模：`@dicebear/core@10.3.0` 发布包解压约 1.33 MB、0 运行时依赖；`@dicebear/styles@10.3.0` 解压约 6.60 MB，Lorelei 压缩样式定义约 115 KB。官方 API Docker 镜像按 Docker Hub 展示压缩约 145 MB。
- 用户已经在宿主机 3100 端口启动 DiceBear。当前受限执行环境第一次探测被代理变量转到 `127.0.0.1:7890`；显式绕过代理后仍无法连接宿主机 3100，属于执行环境与宿主机端口隔离，不能据此判定服务异常。
- 当前 Dockerfile 已创建并授权 `/app/data`，但 `compose.yaml` 没有为应用挂载该目录；头像落盘前必须增加持久卷，否则容器重建会丢失文件。
- 为保持前端简单，后端应始终返回统一 `avatarUrl`。读取端点需直接返回 SVG/PNG 二进制，不能被标准 JSON 响应信封包装。
- 默认头像建议惰性生成并落盘缓存：路径由用户 UUID 和固定生成器版本推导，不需要数据库逐条保存生成文件键；上传头像则使用随机不可猜文件键并在数据库保存引用。
- DiceBear 不可用时应返回后端内置的安全字母 SVG，但不把降级文件永久缓存为 Lorelei，以便服务恢复后重新生成。
- DiceBear 曾针对把不可信输入直接带入 SVG 的场景提示安全风险；本方案只使用后端已有 UUID、固定样式和固定选项，用户上传 SVG 一律拒绝，位图上传后解码并重新编码。
- 用户在宿主机执行 `curl http://localhost:3100/10.x/lorelei/svg` 成功，确认 10.x Lorelei 路由可用。当前容器名为 `dicebear-api`，实际镜像为本地构建的 `dicebear-api-selfbuilt:4.10.0-c239f15-pnpm`，映射 `127.0.0.1:3100 -> 3000`。
- 默认头像缓存格式确定为 SVG：Lorelei 原生输出已经很小且无需转码；用户上传头像仍统一重新编码为 PNG。前端只使用 URL，不感知格式差异。
- 已使用固定 seed `springboot-start-fallback` 从运行中的本地容器生成最终回退头像 `src/main/resources/avatars/default-lorelei.svg`。文件为有效 SVG，大小 5,323 字节，SHA-256 为 `6450a2b117affc4a59384ee830c77f4d7d24cee901861c4630eac411fecd9101`，静态检查未发现脚本、事件属性、外部图片或外部 `use` 引用。
- 用户确认头像可匿名读取，但 URL 不得携带用户内部 ID 或其它敏感字段；设计需引入独立、不可推导的公开头像标识。
- 用户要求上传支持常见位图格式并包含 WebP，后端解码后统一转换保存；默认头像允许从基于用户身份的初始生成结果切换到新的随机系统头像，也允许上传自定义头像。
- `avatarType` 保留在数据库，但只在管理员接口返回；普通用户接口只需要稳定的 `avatarUrl`，避免暴露实现细节。
- 用户要求把 DiceBear 和本地存储纳入统一运行时管理：启动时探测并在格式化摘要中输出一次，状态持久化且在内存统一缓存，支持定时刷新、可配置刷新周期、全量手动刷新和单组件重试。
- 运行时“配置”与“探测状态”必须分模：现有 `runtime_settings` 适合保存开关与刷新周期；实时可用性应使用独立组件状态模型，生成请求以最新内存快照为准，不能把管理员可编辑的陈旧状态当作健康事实。
- 通用存储需成为独立模块，第一版只提供本地实现；头像模块只管理头像业务元数据和转换逻辑。头像孤儿文件、临时文件、引用利用率与容量报告接入现有资源化 GC 框架。
- 后台管理项目位于 `/Users/yaopinxin/project/GitHub/springboot-admin`，需同步用户列表首列头像、头像类型展示、管理员重置入口，以及运行时组件状态/重试/刷新周期管理。
- `RuntimeSettingService` 当前用静态定义表初始化数据库，并用 `ConcurrentHashMap` 保存事务提交后才更新的快照；现有 Controller 只有列表和单项修改接口，没有显式重载数据库或批量刷新 API。
- `StartupSummaryLogger` 在 `ApplicationReadyEvent` 后只输出一次 Unicode 框线摘要，当前可自然增加 DiceBear 与本地存储两行非敏感状态；应只显示状态和必要的本地组件标签，不输出带凭证的完整 URL。
- GC 当前通过 `GcResource` Bean 注册资源，由策略、执行编排、独立事务工作器和运行历史组成；头像临时文件/孤儿文件适合新增资源实现，不需要另造清理调度器，但“利用率/占用统计报告”需要在资源描述或独立存储报告 API 上扩展只读统计。
- `springboot-admin` 已有用户管理、运行时配置、GC 页面及对应 API 文件；用户 Store 也已有 `avatar` 字段与导航栏头像回退逻辑，因此接入后端 `avatarUrl` 不需要改造全局布局结构。
- 后台用户表当前第一列是用户名，详情抽屉已有账号状态/会话/角色操作；可将第一列改为 40px 头像列，在详情头部展示大头像与只读 `avatarType`，并仅给 `system:user:write` 增加“重置为系统默认”操作。
- 后台 `CurrentUser` 尚无 `avatarUrl`，而 Store 在加载个人资料时错误地沿用本地旧 `avatar`；接入时应以每次 `/users/me` 返回的 `avatarUrl` 覆盖 Store，并在登出时清空，避免账号切换后串用头像。
- 运行时配置页目前按前端硬编码分组，GC 页面已能显示资源策略与执行历史；新增头像/运行时组件后需同步扩展分组、类型定义和契约测试，不能仅依赖后端返回新字段自动出现。
- JDK 自带 ImageIO 和 TwelveMonkeys 都不能作为 WebP 完整支持方案。当前可选 `com.github.usefulness:webp-imageio:0.11.0` 于 2026-07 发布，Maven Central 标注 Apache-2.0；它把 libwebp 的 JNI 二进制随库分发，支持 Java ImageIO 读写及 macOS ARM，但实施时必须在 CI 覆盖 Linux amd64/arm64 和 macOS arm64 的真实解码测试。
- 旧的 `org.sejda.imageio:webp-imageio:0.1.6` 已约六年未发布，不应作为第一选择。头像上传只需要它的 WebP 解码能力，统一输出仍可用 JDK 原生 PNG 编码，减少对 WebP 编码参数和输出兼容性的依赖。
- 用户最终确认随机系统头像使用“预览候选后确认”，第一版输入限定静态 JPEG/PNG/WebP，后端负责 EXIF 方向、中心裁剪和 256×256 PNG 输出；未来 C 端裁剪通过可替换 `AvatarCropStrategy` 或受控裁剪参数扩展。
- 完整设计确定公开媒体路由使用独立高熵 `avatarPublicId`，用户表保存 `avatarType/avatarSeed/avatarObjectId/avatarRevision`；随机候选用短期独立表和对象引用，确认时直接提升候选对象，避免重复生成。
- JPEG EXIF 方向建议使用 `com.drewnoakes:metadata-extractor`；Maven Central 当前版本为 2.21.0、Apache-2.0，可只读取方向后丢弃所有原始元数据。相比引入仍为 alpha 的完整 Commons Imaging，它更贴合本次窄用途。

## 头像实施发现（2026-08-03）

- 当前实体基类实际为 `BaseUuidEntity`，不存在 `BaseEntity.java`；后续实体应沿用 `AuditedEntity -> BaseUuidEntity`，不要创建重复基类。
- 当前 master changelog 从 011 跳到 014/015；头像与存储迁移可使用下一个 `016`，并同步 SQLite/PostgreSQL 迁移测试。
- `pom.xml` 尚无图像处理依赖；加入 WebP/EXIF 依赖后需要重新评估 JAR、许可证和 JaCoCo 覆盖率门禁。
- SQLite 无法在不重建表的情况下给既有 `users.avatar_object_id` 增加外键；实际迁移采用逐列原生 `ALTER TABLE` 并保留应用/GC 引用校验，PostgreSQL 则保留数据库外键。候选表是新表，两种数据库均可正常建立用户和存储对象外键。
- `UserRepository` 当前可直接扩展 `findByAvatarPublicId`，管理员分页仍采用分页 ID 后批量加载角色的模式；新增头像字段是用户表标量，不会引入额外 N+1。
- `@PublicApi(minimalResponse = true)` 已能让头像二进制绕过 JSON 信封，同时 `PublicApiRequestMatcher` 会自动允许匿名访问并保留 Trace/IP/限流过滤器；无需在 Security 配置硬编码头像路由。
- 注册 `UserResponse`、当前用户 `CurrentUserResponse` 和管理员 `AdminUserResponse` 都由静态 `from(User)` 组装，统一 URL 可由不依赖 Spring 的 `UserAvatarUrl` 纯函数生成，避免给每个应用服务新增 URL 服务依赖。
- 头像上传处理器的失败测试已证明三项核心抽象尚未存在；实现将输入限制与 ImageIO 头信息校验前置，并用重编码 PNG 的方式移除 EXIF/ICC 等原始元数据，裁剪决策通过 `AvatarCropStrategy` 隔离，后续可以换成前端提交的受控裁剪参数。
- 现有头像规格已明确候选 API 使用高熵 `candidateId`、匿名短期预览、30 分钟失效和每用户最多 5 个有效候选；确认接口必须同时校验用户归属、有效期和未消费状态，不能把 seed 暴露给客户端。
- 当前 `UserProfileController` 与 `UserAdministrationController` 是头像操作的最小接入点，且 `AuditService` 已能记录当前 JWT actor；头像变更不需要引入新的认证上下文或会话撤销逻辑。
- Spring 的 multipart 解析会在 Controller 的显式 5 MiB 检查之前拒绝超限请求，因此必须给 `MaxUploadSizeExceededException` 增加稳定的 `AVATAR_FILE_TOO_LARGE` 映射；只在业务服务判断字节数不足以保证协议一致。
- 同名 `src/test/resources/application.yml` 或 `application.properties` 会遮蔽而不是增量合并主配置；测试专用存储目录和关闭启动探测已改为 Surefire 系统属性，避免丢失 Actuator 等主配置。
- 管理端头像、运行时组件和存储报告改动已通过 `pnpm typecheck` 与正式 Vite 构建；后端全量测试必须在允许 JVM attach 和本地临时端口的环境执行，沙箱权限错误不代表业务测试失败。
- 新增核心服务如果只靠迁移和底层单测会显著拉低整体覆盖率；头像管理、惰性缓存/回退和组件状态机的直接行为测试将最终 JaCoCo 恢复到行 75.10%、分支 50.84%，不需要降低或排除门禁。

## 运行时配置缓存重构（2026-08-03）

- `RuntimeSettingService.enabled/integer/string` 读取 `volatile` 不可变 Map，业务热点不查数据库；`list()` 管理接口仍直接查表。
- 单项和批量修改会先持久化，事务提交后原子替换本机快照，回滚不会发布未提交配置。
- 配置缓存自动对账由 `RuntimeCacheScheduler` 独立管理；`RuntimeComponentRegistry.refreshAll()` 只执行运行状态检查，不加载配置表。
- IP 持久化规则已接入统一缓存状态、手动全量/单项刷新和按 revision 的跨实例自动对账协议。
- 请求路径仍会重复进行字符串转整数、CSV 拆分和端点规则构造；本阶段先建立统一 revision/刷新协议和职责拆分，随后在不改变接口语义的前提下评估类型化派生快照。
- 第一版统一缓存注册两个 provider：`RUNTIME_SETTINGS` 和 `IP_ACCESS_RULES`。二者共用 `runtime_cache_revisions` 的单调 revision，写事务内递增、提交后发布本机快照；自动任务先比较单行 revision，变化时才全量加载对应配置。
- 配置缓存调度使用独立的 `runtime.config-refresh.enabled` 与 `runtime.config-refresh.interval-seconds`；运行检查使用 `runtime.health-check.*`，不再通过 `RuntimeComponentRegistry.refreshAll()` 顺带重载配置。
- 管理 API 分为 `/system/runtime-caches`（缓存状态、全量/单项刷新）和 `/system/runtime-checks`（DiceBear 服务依赖与本地存储资源的状态检查）；旧 `/system/runtime` 不再作为混合入口。
- 017 迁移已将旧 `runtime.component-health.*` 数据键原位改名，并建立跨 SQLite/PostgreSQL 的唯一 revision 索引；缓存 revision 行由 provider 启动时幂等确保，不依赖静态 UUID 种子。
- 管理后台已明确拆成“配置缓存”和“运行状态检查”两块：前者展示内存/数据库 revision、条目数和加载错误，后者按“服务依赖”“本地资源”展示检查结果；任一运行检查都不会触发配置表加载。
- `LOCAL_STORAGE` 的探针会真实执行目录创建、临时文件写入、读回校验、删除和磁盘容量读取；这些检查有运行价值，但它属于 `LOCAL_RESOURCE`，不应与 DiceBear 这样的 `SERVICE_DEPENDENCY` 混称为服务依赖。
- 第 14 阶段采用统一“运行状态检查”注册表，响应增加 `kind` 分类；后台按“服务依赖”和“本地资源”分组，并把内部状态翻译为中文检查结果。
- 当前本地运行状态为：`LOCAL_STORAGE=UP`，检查说明为“目录创建、临时文件读写删除和磁盘容量检查正常”；`AVATAR_GENERATOR=DISABLED` 是因为数据库中的头像生成运行时开关关闭，并非 3100 端口故障。
- 第 15 阶段目标边界：`/system/config` 只保留运行策略和配置缓存管理，缓存使用列表/表格表达；运行检查属于运维概览，应并入 `/welcome` 的既有服务健康区域，避免同一状态在配置页重复出现。
- welcome 原“服务健康”只显示 Actuator 的单个 `UP` 标签；重构后以同一表格合并应用服务、`SERVICE_DEPENDENCY` 和 `LOCAL_RESOURCE`，既消除配置页重复，又保留分类、说明、最近检查、耗时和重试能力。
