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

## 运行时配置与 IP 防护审计（2026-08-04）

- 当前系统至少存在三类容易被界面文案混为一谈的 IP 防护：运行时字符串 allow/deny 列表、数据库持久化 `ip_access_rules` 访问规则，以及进程内请求限流/登录防爆破计数。前两类决定“是否允许访问”，限流只返回 429，登录防爆破只限制认证尝试；现有持久化 IP 规则并不等同于自动封禁记录。
- 管理后台的“IP 访问规则”页面目前只管理人工创建的 `ALLOW/DENY` 持久规则，没有自动风险来源、命中次数、首次/最近命中、解封时间等字段，因此不能直接表达用户期望的自动封禁生命周期。
- 运行时配置页面同时暴露全局限流与端点限流；是否触发取决于过滤器实际覆盖范围、客户端 IP 解析、当前内存快照值和测试请求是否超过容量，不能仅凭页面点击频率判断。下一步按代码和运行数据库逐项核对。
- 全局限流默认值为“每个解析后的客户端 IP、60 秒固定窗口 120 次”，只有 `/api/**` 且非 `OPTIONS` 请求计数；端点限流默认路径为空，因此默认完全不生效。手工点击后台刷新通常很难在 60 秒内超过 120 次，这已经是“看起来没触发”的首要候选原因。
- `RateLimitService` 超限时只返回 429，并不会创建 `ip_access_rules` 的 DENY 记录；其状态仅存在当前 JVM 的 `ConcurrentHashMap`，重启丢失、多实例不共享。当前没有“累计异常行为后自动封 IP”的实现。
- 登录防爆破当前按 `username` 计数，不按客户端 IP；它与通用 API 限流是两套状态。需要继续核对认证服务是否把锁定写回用户状态，以及页面能否观察。
- `IpAccessFilter` 会特意放行 `/system/runtime-config` 和 `/system/runtime-caches`，以防错误 IP 策略把管理恢复入口一并锁死；但 `RateLimitFilter` 没有这个豁免，所以这些管理接口仍会进入全局限流。
- 三个动态调度器的首轮语义不一致：配置缓存和运行检查从启动时刻等待完整配置间隔，GC 因 `lastRunAt=0` 会在第一个 60 秒调度 tick 立即执行。页面文案没有表达这种差异。
- 8081 真实请求验证通过：对当前唯一配置了端点限流的 `/api/v1/system/audit-events` 连续发 25 次请求，状态序列严格为 20 个 401、随后 5 个 429；证明过滤器和当前内存配置生效。手工页面操作未触发的合理解释是 60 秒内没有超过 20 次，或刷的是其它仅受 120 次全局阈值保护的接口。
- 当前 429 响应没有 `Retry-After`，虽然旧设计/运维文档要求提供；`ApiErrorWriter` 也没有可传递窗口剩余时间的能力。并且限流过滤器在请求日志过滤器之前短路，429 本身不会进入现有 `RequestLoggingFilter`，更没有专门的限流审计/指标事件，造成后台无感知。
- 实测登录使用之前会话中记录的管理员口令返回 401，本轮没有继续尝试或修改账号；限流验证改用无需登录也会先经过安全过滤器的请求完成。
- 后端目前定义 27 个运行时键，但配置页硬编码分组只展示 20 个：缺少登录失败阈值/统计窗口/锁定时长，以及 `maintenance.gc.enabled`、GC 调度开关/间隔、最大运行时长。GC 页面另行管理其中两个调度键，但全局 GC 开关和最大运行时长在两个页面都不可见；“运行时配置列表”并不完整。
- 前后端重复维护配置键、名称和说明，且前端不消费后端的范围元数据（后端响应也没有 min/max/是否敏感/重启要求等元数据）。`el-input-number` 没有按配置定义设置边界，非法值只能等后端拒绝；后端把非法值伪装为 404，语义也不合理。
- 两套 IP 黑白名单确实冲突：`security.ip.allow-list/deny-list` 是无结构字符串配置，`ip_access_rules` 是结构化、带到期时间的规则表；请求时二者叠加，但后台分处两个页面。当前数据库字符串黑名单仍有 `192.168.31.133`，结构化规则表为空，用户只看“IP 访问规则”页会误以为没有封禁。
- `IpAccessRuleRequest` 缺少数据库长度对齐校验，允许过去时间并允许重复/重叠规则；页面也不显示规则是否当前生效、来源、创建/更新时间或创建人。过期规则仍留在表中且没有专用 GC 资源。
- 请求日志没有记录解析后的客户端 IP；审计事件模型也没有客户端 IP。即使发生持续刷接口，现有日志/后台无法回答“哪个 IP、命中了哪条限流策略、何时开始、累计多少次”。
- 配置缓存自动对账存在“自关闭”多实例缺陷：`RuntimeCacheScheduler` 先读取本机快照中的 `runtime.config-refresh.enabled`，为 false 就完全不查 revision。某节点同步到 false 后，如果另一节点再把数据库改回 true，该节点永远不会自动发现，只能手工刷新或重启。
- 缓存状态的 `synchronizedState` 只是“内存 revision 与上一次观察到的数据库 revision 相等”，列表接口不会即时查询数据库；因此在下一次轮询前可能仍显示“已同步”。同时全量自动/手工刷新按 provider 串行执行，前一个 provider 抛错会阻止后续 provider 刷新，缺少逐项隔离结果。
- 运行时设置的数据库读取和 revision 读取不是一个明确的一致性快照；在多实例并发提交时存在“读到旧配置、随后读到新 revision并误标已同步”的窗口，应以 revision-before/values/revision-after 或更强事务隔离保证一致。
- IP 管理恢复路径不完整：字符串 IP 策略锁死时可通过已豁免的 `/runtime-config` 恢复，但结构化 `ip_access_rules` 一旦把管理员当前 IP 排除，`/ip-access-rules` 本身也会被过滤器拒绝，现有 `/runtime-caches` 只能重载不能删除规则，后台没有 break-glass 恢复入口。
- 全局限流也覆盖运行时配置与缓存管理接口。若共享 NAT/反向代理 IP 被耗尽，管理员会与攻击流量共用桶并暂时无法调整配置；窗口允许配置到 3600 秒，控制面缺少独立额度或受保护的恢复策略。
- `RateLimitService` 和 `BruteForceService` 的 Map 无容量上限，过期项只依赖默认每小时一次的 GC 资源清理；大量不同 IP、具体路径或伪造用户名可以在清理前造成内存增长。端点模式若使用通配符，桶键仍取具体 URI，攻击者轮换资源 ID 可绕开“整类端点”容量。
- 运行时更改限流容量/窗口不会重建已有窗口：旧窗口保留原到期时间和累计次数；关闭后再快速开启也会恢复旧桶。页面当前把修改描述成即时生效，但精确语义并非全量即时。
- 运行检查的单组件异常已在组件内部转为 ERROR，彼此隔离；配置缓存刷新却没有同等隔离，这两套运行时管理的故障语义不一致。
- 文档称运行时设置只管理服务治理、不管理业务开关，但中央 `RuntimeSettingService` 实际同时注册身份注册、头像生成和 GC 等模块配置，并直接依赖 `AuditService`；`AuditService` 又通过 `ObjectProvider` 反向读取运行时设置。统一管理目标合理，但定义所有权和依赖方向已经冲突，建议改为各模块贡献类型化 `RuntimeSettingDefinition`，中央注册表只负责存储、校验、缓存和审计协议。
- PostgreSQL 的 credentialed CORS `*` 只在应用启动时由 `ProductionSafetyValidator` 拒绝；服务启动后仍可通过运行时更新改成 `*`，绕过生产安全门禁。CORS 方法、Ant 路径和字符串 IP 列表也只校验长度，没有语义校验或更新前模拟。
- `security.audit.enabled` 可以把后续安全/管理审计整体关闭，`security.request-log.enabled` 也可关闭全部请求日志；对于配置变更、自动封禁、登录和权限变更等高风险事件，不应由同一普通运行时开关完全静默。
- `maintenance.gc.max-run-seconds` 实际只是数据库 GC 锁的租约长度，并不会中断超时任务；“GC 最大执行时长”名称和说明会让管理员误以为存在硬超时。任务超过租约后还可能被另一实例取得锁并并发执行。
- 文档声称运行状态“刷新成功后整体替换”，实际 `refreshAll()` 逐组件查询、保存并逐项发布快照；刷新过程中读取者可能看到新旧混合状态。若需要批次一致性，应在全部检查结束后一次发布；若接受逐项更新，应修正文案。
- 周期设置的实际精度受固定调度 tick 限制：配置缓存/运行检查每 10 秒检查一次是否到期，GC 每 60 秒检查一次。界面只展示目标间隔，没有说明首次运行和最大附加延迟；启动检查还受单独的启动期 `app.runtime.component-startup-probe.enabled` 控制，不属于运行时开关。

### 建议的收敛方案

- P0：移除运行时字符串 `security.ip.allow-list/deny-list` 的产品入口，将现有值迁移成结构化规则，最终只保留 `ip_access_rules` 一份有效规则源；配置页不再出现重复 IP 策略。
- P0：限流决策从 boolean 升级为结构化结果（策略、桶、剩余配额、重试秒数），429 返回 `Retry-After`，并记录带解析后客户端 IP 的安全事件和指标；请求日志也增加脱敏/规范化后的客户端 IP。
- P0：补全控制面恢复能力。`runtime-config`、`runtime-caches`、`ip-access-rules` 应使用独立的管理额度或受认证/RBAC保护的 break-glass 策略，不能与普通 API 共享一个可被攻击者耗尽的 IP 桶。
- P1：新增有界 `IpAbuseTracker`。第一次超限仍只返回 429；同一解析后 IP 在观察窗口内累计多次超限后，才创建有期限的自动 DENY 规则。第一版单机可使用 Caffeine 有界缓存，持久化只发生在“生成/更新封禁”状态转换时；多实例部署再替换为 Redis 原子计数，不在每次请求上写关系数据库。
- P1：自动封禁规则至少记录 `source=AUTO_RATE_LIMIT/AUTO_LOGIN_ABUSE/MANUAL`、触发策略、首次/最近违规时间、违规次数、封禁开始/到期时间和操作状态。人工删除改为“解除/撤销”以保留历史，过期记录交给 GC 策略归档/清理。
- P1：IP 访问规则页统一展示“当前封禁、人工规则、最近触发事件”，列表直接显示 IP/CIDR、来源、状态、命中/违规次数、最近触发、到期时间和原因，并提供解除、延长、转永久；页面顶部展示当前请求解析出的客户端 IP 与代理信任模式，便于部署排错。
- P1：自动升级不要把一次 429 等同于恶意。建议以“观察窗口内的拒绝次数/连续受限窗口”为二级阈值，默认临时封禁 15 分钟，并对重复事件分级延长但设置上限；显式受信任规则只豁免自动升级，人工 DENY 仍保持最高优先级。
- P1：登录防爆破保留账号维度，同时增加独立 IP 维度；账号锁定和 IP 自动封禁分别记录，避免攻击者通过轮换用户名绕过，也避免仅凭共享 IP 一次失败锁住真实账号。
- P1：运行时配置改为模块化类型定义注册表，由后端返回分组、类型、min/max、枚举、危险级别、作用域和即时生效语义；前端按元数据渲染，不再硬编码配置键。生产安全约束必须同时用于启动校验和运行时更新。
- P1：修复缓存自关闭、多 provider 故障隔离和 revision 一致性读取；缓存页把“已同步”改为明确的“截至最近检查已同步”，或列表时轻量读取当前 revision。
- P2：统一三个调度器的首次执行、失败重试和时间精度语义；把 `maintenance.gc.max-run-seconds` 改名为锁租约，或实现真正可取消的硬超时后再保留现名。

### 第一版实施结果

- IP 请求判断已收敛到结构化规则单一来源，旧字符串名单在启动事务中迁移并删除；实际本地数据 `192.168.31.133` 已成为 `MIGRATED DENY`。
- 全局、端点模式和管理控制面使用明确的独立桶；429 已返回重试和额度头。端点桶以匹配模式为键，不能再靠轮换具体资源 ID 绕过同类端点容量。
- 连续 429 会经有界 Caffeine 状态机升级为有期限的 `AUTO_RATE_LIMIT` 规则，规则页统一展示人工、迁移、自动和历史状态。回环/本地/组播地址不自动封禁。
- 运行时配置接口返回分组、整数范围和风险元数据，后台动态展示全部 31 个键；缓存自关闭恢复与 Provider 故障隔离已修复，页面准确表述为最近一次检查结果。
- 仍保留为后续工作的边界：集群共享计数器、登录来源 IP 维度、运行时定义的模块化贡献机制、严格的 revision 一致性读取，以及过期 IP 历史记录的 GC 策略。
