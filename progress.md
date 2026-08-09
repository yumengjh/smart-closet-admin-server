# Progress

## 2026-08-09

- 用户要求实际验证 IP 访问规则与自动安全防护是否生效，并确认所有安全机制均有文档，特别要解释本地 8849/8081 端口测试为何始终不产生自动封禁；第 19 阶段开始。
- 验证将使用临时 SQLite 副本和独立测试端口，通过受信任代理头模拟公网客户端，不修改当前开发数据库，也不停止 8081、8849、3100 服务。
- 文档审计确认现有 `runtime-settings.md` 与 `ip-access-rule-cache.md` 已覆盖核心概念，但缺少集中、可执行的安全防护说明，尤其没有明确解释 8849 经 Vite 转发后后端观察到回环来源、普通请求数与 429 拒绝数的区别，以及本地复现自动封禁的方法。
- 已在 `/private/tmp` 创建隔离数据库副本：端点容量设为 2、自动封禁拒绝阈值设为 2、封禁 60 秒，并预置针对 `203.0.113.77` 的临时人工 DENY；当前开发数据库未改动。
- 隔离端到端验证通过：人工 DENY IP 请求普通接口返回 `403 IP_ACCESS_DENIED`；同一来源访问恢复接口返回 401 而非 IP 拒绝；模拟 IP `203.0.113.88` 请求序列为 `401,401,429,429`，随后普通接口返回 403，临时数据库生成 `AUTO_RATE_LIMIT` 规则，`triggerType=RATE_LIMIT_ENDPOINT`、`violationCount=2` 且带 60 秒到期时间。
- 当前 8081 本地路径复现完成：默认端点容量 20、自动封禁阈值 10，连续 30 次请求严格返回 20 个 401 和 10 个 429，429 额度头完整；主数据库自动规则数前后均为 0。`.env` 未配可信代理，伪造的 XFF 被忽略，实际来源为回环地址，而回环地址按设计不进入自动封禁状态机。
- 隔离进程已优雅停止。首次清理临时测试目录使用 `rm -rf` 被安全策略拒绝，未删除任何项目数据；后续改用可恢复的系统废纸篓方式，不重复该命令。
- 临时测试目录已移入系统废纸篓，18081 不再监听；原有 8081、8849、3100 服务均保持运行。
- 新增 `docs/operations/ip-security-protection.md`，集中说明请求链、可信代理、规则优先级、三种限流桶、自动封禁状态机、登录防爆破边界、恢复入口、可观测性、多实例限制、部署检查和安全复现步骤；README、运行时配置、缓存说明与 API 手册均已建立入口或补充精确语义。
- 文档中明确：控制面 429 不进入自动封禁；本地 8849→8081 通常解析为回环来源；阈值累计的是 429 而非普通请求。定向执行 IP/限流相关 5 个测试类，共 9 个用例全部通过。
- 首次补充控制面/登录防爆破精确配置键和控制面回归测试时，单个补丁内同一文档的两个 hunk 顺序与文件行序相反，校验失败且未写入；已按从前到后的顺序重排，不重复原补丁。
- 新增控制面回归测试，锁定“独立 600 容量桶、429 响应、不会调用自动升级状态机”的恢复语义；IP/限流 5 个测试类现为 10 个用例全部通过。安全手册所需配置键、错误码、本地端口、可信代理、回环和多实例关键词静态检查无遗漏，部署清单也已链接该手册。

## 2026-07-14

- 建立剩余目标收口计划。
- 已确认 Refresh Token 重用事务回滚、Compose JWT 密钥缺失、日志/防爆破/OpenAPI/身份能力缺口。
- Refresh 重用路径已改为 `noRollbackFor = ApiException`，并有回归测试。
- 新增防爆破观察窗口配置、请求日志开关与请求结构化日志过滤器；审计开关通过惰性依赖生效，避免循环注入。
- 新增本人 Refresh Session 列表及退出全部设备端点；注册开关作为 `identity.registration.enabled` 运行时设置。
- 新增角色、权限目录 CRUD 与关联会话失效/审计。
- 已补 Maven Git 构建元数据、OpenAPI 通用错误组件、Compose JWT 密钥变量说明，以及测试页对 RBAC CRUD/本人会话的覆盖。
- 已完成 `node scripts/verify-api-test-console.mjs`、HTML 内联脚本语法检查、完整 Maven 测试（PostgreSQL/Testcontainers 因本机无 Docker 跳过）和 `git diff --check`。
- 已以 SQLite 在 `http://localhost:8080` 启动新代码并完成 HTTP 回归：健康检查、公开公告、登录、Git 系统信息、OpenAPI、会话列表、CORS POST 预检、RBAC 角色/权限 CRUD、注册开关以及“退出全部设备”后旧 Access Token 立即失效。
- 额外修复：删除未被使用的权限不再无故失效无关用户令牌；管理员/本人撤销会话会递增 token version。
- 开始整理全量 API 参考手册：以实时 OpenAPI、Controller 与 DTO 为事实来源，避免仅靠旧专题文档遗漏接口。
- 已新增 `docs/API_REFERENCE.md`：覆盖通用响应/错误/认证约定、所有业务 API、全部 18 个运行时配置键、公开公告的双视图规则、OpenAPI/Swagger 和 Actuator。
- 已用运行中的 `/v3/api-docs` 校验 31/31 个 OpenAPI 路径均出现在手册中，并校验 18/18 个运行时配置键均有说明；`git diff --check` 通过。

## 2026-07-15

- 用户确认直接实现前后端登录联动，选择 HttpOnly Cookie 方案；动态菜单暂时保留前端假数据。
- 已确认后端与前端是独立 Git 仓库，未开始修改业务代码。
- 后端认证改为 HttpOnly、SameSite=Strict 的 `refresh_token` Cookie；登录体增加 `rememberMe`，会话持久化标记随轮换保留，刷新和退出从 Cookie 读取 Token。
- 前端完成 Element Plus 注册页、登录页记住我入口、Cookie 自动刷新、401 单次刷新重试和服务端退出；Access Token 仅驻留内存。Vite `/api` 代理到本地 8080，动态菜单保持假数据。
- 修复 SQLite `addColumn` 造成 Refresh Session 索引丢失的问题，并增加 CORS credentials 支持。
- 验证：`./mvnw test` 通过（56 个测试，1 个 PostgreSQL/Docker 测试跳过）；`pnpm typecheck` 与 `pnpm build` 通过；临时 SQLite 实例的注册、Cookie 登录、刷新轮换、退出清 Cookie HTTP 冒烟通过；两个仓库均通过 `git diff --check`。
- 发现 8080 上残留 7 月 14 日启动的旧 JVM，旧 `LoginRequest` 不识别前端新增的 `rememberMe` 字段而返回 `UNKNOWN_FIELD`；已重启为当前代码。携带该字段的无效账号请求现正确返回 `401 INVALID_CREDENTIALS`，健康检查为 `UP`。
- 开始第 9 阶段：动态菜单与路由模块的设计调查。确认前端目前使用空的假路由接口，后端尚无菜单实体；动态组件映射缺失需要在新协议中显式校验并降级处理。
- 用户确认动态导航设计，并授权跳过额外文档审阅直接实施；设计已写入 `docs/superpowers/specs/2026-07-15-dynamic-navigation-rbac-design.md`。
- 已生成 `docs/superpowers/plans/2026-07-15-dynamic-navigation-rbac.md`，将按内联方式执行。
- 已完成 `navigation` 模块：Liquibase 菜单表、受控组件键校验、菜单 CRUD、JWT 权限过滤路由接口、审计事件和内置首页/菜单管理条目；新增 `system:menu:read/write`。
- 管理后台已将假动态路由替换为后端接口，采用受控组件注册表加载路由；接口失败也会结算加载状态并保留静态首页，避免侧栏永久显示加载中。已提供菜单 CRUD 页面及写权限控制。
- 新增 SQLite 迁移与导航服务回归测试；前端类型检查和生产构建均通过。
- 修复动态菜单叶子节点未显示：前端路由转换曾为页面菜单输出 `children: []`，被侧栏按“空目录”过滤；现在叶子页面省略该字段，并新增独立回归脚本验证该数据形状。
- 修复内置首页与静态首页冲突：动态接口中的 `/welcome` 曾保留 `name: home` 于侧栏，而前端路由注册会跳过该同路径条目，点击后触发 `No match for { name: home }`。动态菜单现在在渲染前剔除已注册的静态路径，并有回归验证。
- 用户确认继续集成 `api-test.html` 覆盖的正式管理能力；已完成多页面 Element Plus 集成设计，待用户审阅设计文件后进入实现计划。
- 用户已确认设计，已生成管理后台完整集成实施计划，等待选择执行方式。
- 用户要求在当前会话直接执行；已完成 `api-test.html` 所覆盖能力的 Element Plus 页面化集成：用户管理、角色与权限、运行时配置、IP 规则、审计日志、公告管理、账户安全和首页健康/系统信息概览。
- 后端导航受控组件白名单扩展为上述页面；启动器已向本地 SQLite 实际种入 `system`、`content`、`account` 目录及其内置页面，并把现有菜单管理项归入系统目录。`NavigationServiceTest` 先失败后通过，完整 `./mvnw clean test` 通过：62 个测试通过，1 个 PostgreSQL/Testcontainers 测试跳过。
- 前端新增按领域拆分的 API 客户端、当前用户权限组合式函数和五个可执行 Node 契约脚本；`node scripts/router-conversion.test.mjs`、`permission-composable`、`admin-page-contract`、`runtime-config-contract`、`announcement-contract` 与 `pnpm typecheck` 均通过。两个仓库的 `git diff --check` 均通过。
- 已重启本地后端至当前代码，`/actuator/health` 返回 `UP`；数据库中确认八个新页面菜单均已落库。由于没有读取或输出本地管理员密码，未执行带超级管理员身份的浏览器端到端点击验证。
- 已补齐 `vue-demi` 与 `tippy.js` 的直接依赖并更新锁文件；前端完整 Node 契约测试、`pnpm typecheck`、`pnpm build` 与 `git diff --check` 均成功。第 10 阶段已完成。
- 修复动态路由页切换白屏：用户管理、RBAC、IP 规则与公告页同时输出了卡片/弹窗等多个根节点，Vue `Transition` 因此无法动画并会在切换时卸载异常。已统一为单一 `.page-root` 根元素；新增 `route-page-root.test.mjs`（修复前失败、修复后通过），并重新通过 `pnpm typecheck` 与前端 `git diff --check`。
- 头像下拉菜单已增加“账户安全”“刷新会话”“退出系统”，覆盖纵向、横向和混合布局；账户安全页也提供刷新当前会话、退出当前登录和退出全部设备。刷新会话轮换 HttpOnly Refresh Cookie、更新内存 Access Token 与用户资料；任何 Token 不会显示或写入页面。`avatar-session-menu.test.mjs` 和 `pnpm typecheck` 通过。
- 修复 `/api/v1/users/me/sessions` 的 `500 INTERNAL_ERROR`（traceId `c4878547f72b43079904397999017909`）：定位到 006 迁移前的 150 条 Refresh Session 缺少 `persistent` 值，而实体字段是基本类型 `boolean`，Hibernate 读取时无法注入 `NULL`。新增 009 Liquibase 迁移归一为 `false`，本地重启时已实际更新 150 条；数据库复核空值为 0，健康检查为 `UP`，迁移回归测试通过。
- 修复 Vite 开发代理与依赖布局：重启前端服务后，`/actuator/health`、`/swagger-ui/index.html`、`/v3/api-docs` 均由 8848 正确代理至 8080，不再回退到 SPA HTML。补齐 `vue-demi`（CDN 插件）与 `tippy.js`（`vue-tippy` peer）为直接依赖，避免 pnpm 不创建根链接导致开发服务器无法重启；前端入口和 `pnpm typecheck` 均通过。

## 2026-07-16

- 完成后端运行性审查：全量 Maven 基线为 83 个测试通过、0 失败、1 个 PostgreSQL/Testcontainers 测试跳过。
- 用户确认直接实现 GC 资源/策略中心，并要求在开发阶段增加受限 SQL 调试台；第 11 阶段开始。
- 已完成 014 Liquibase 迁移：GC 策略、运行记录、锁表以及 Refresh Session 的用户/家族/到期索引。SQLite 迁移断言先失败后通过。
- 已完成 GC 初始资源和调度器：Refresh Token、审计日志、限流状态、登录失败状态均通过 `GcResource` 注册；策略独立持久化，自动任务使用数据库锁。`GcExecutionServiceTest` 先因缺类失败后通过。
- 已完成 GC 管理 API、本地 SQL 执行 API、后台 GC 可视化与 SQL 执行台；导航项按 `system:gc:read` 与 `system:sql:execute` 权限展示。SQL 控制台仅 local+显式开关可运行，多语句拒绝、写操作确认、敏感列脱敏和审计均已覆盖。
- 验证：后端 `./mvnw test` 为 85 通过、0 失败、1 跳过；管理端 `pnpm typecheck`、`pnpm build` 成功。
- GC 结果协议已改为结构化 JSON：每次任务会返回总候选数、总删除数和各资源的候选/删除/说明，管理端不再显示 `resource:a/b` 压缩字符串。GC 页面重构为运行概览、资源策略、运行记录三个视图，支持单资源预览/真实清理、按预览/真实/来源/状态筛选以及任务详情抽屉。
- 为结构化结果先补充了失败测试，再实现编码/解码；最新验证为后端 `./mvnw test` 87 通过、0 失败、1 跳过，管理端 `pnpm typecheck && pnpm build` 成功。已重启后端，`http://localhost:8080/actuator/health` 与经 8848 代理的健康检查均为 `UP`。
- 修复 GC 策略“编辑后立即预览被旧值覆盖”：前端策略改为显式未保存状态，预览/真实清理前会等待保存目标策略完成后才调用执行接口。新增独立的 `maintenance.gc.schedule.enabled`，GC 页可管理全局自动清理开关和 1–1440 分钟执行间隔；关闭自动计划不会影响手动运行。新增调度开关回归测试先失败后通过；全量后端测试为 88 通过、0 失败、1 跳过，前端类型检查和构建通过。重启后已确认新设置初始化至 SQLite。

## 2026-08-03

- 用户确定默认头像采用 DiceBear Lorelei，并选择由后端统一服务，前端只处理 `avatarUrl`。
- 用户已在宿主机 3100 端口启动自托管 DiceBear；规划采用本地 HTTP 生成服务，不依赖 DiceBear 公共 API。
- 完成第一轮头像架构核查：确认数据库、实体、DTO、接口和 Compose 持久卷均需补齐；原始用户上传 SVG 将禁止，默认 SVG 只允许固定 UUID seed 的可信生成链路。
- 本地连通性探测记录：首次请求受 shell 代理影响而访问 `127.0.0.1:7890`；使用 `--noproxy '*'` 后当前受限环境仍无法访问宿主机 3100。后续实施验证应由宿主机执行或使用 Compose 服务名，不重复以同一路径探测。
- 用户从宿主机验证 3100 的 Lorelei SVG 响应成功；随后授权访问 Docker daemon，确认运行容器和端口映射。
- 通过容器内部请求固定 seed 生成 `src/main/resources/avatars/default-lorelei.svg`，作为 DiceBear 不可达且用户无缓存时的最终 classpath 回退；XML、文件类型和危险 SVG 片段检查通过。
- 生成脚本在文件写入完成后的字节统计阶段因执行环境没有全局 `TextEncoder` 报错；复核确认文件已完整创建，因此改用 `wc/file/xmllint/shasum` 验证，不重复原失败路径。
- 用户要求将头像草案升级为覆盖整个用户模块的完整设计文档，并要求在定稿前询问未决细节。当前暂停扩写，等待确认管理员能力、读取隐私、上传规范、默认头像刷新和生成服务可观测性等产品决策。
- 用户确认匿名读取、敏感 ID 不进头像 URL、常见位图含 WebP 输入、系统头像可随机更换、管理员可重置、头像类型仅管理员可见，并要求统一运行时组件刷新、本地存储抽象和头像 GC 报告。
- 已对照现有 `RuntimeSettingService`、启动摘要、GC 资源框架和 `springboot-admin` 用户/配置/GC 页面确定扩展位置；运行时配置与探测状态将分表分责，后台现有 Store/页面结构可增量接入。
- 已核对 Java WebP 方案：优先评估活跃的 `com.github.usefulness:webp-imageio` ImageIO 插件，上传统一输出继续采用 PNG；待用户确认预览/提交交互与第一版精确输入格式范围后扩写完整设计。
- 用户确认候选预览后提交、静态 JPEG/PNG/WebP 和第一版后端自动裁剪；未来 C 端裁剪作为策略扩展，不改变存储与头像状态模型。
- 已将头像规格扩展为完整设计：覆盖用户状态、公开匿名 URL、数据库、通用存储、WebP/裁剪、DiceBear、降级、随机候选、运行时组件刷新、启动摘要、GC/利用率、后台管理、配置部署和验收，共 670 行。
- 已同步重写 145 行实施计划，按迁移/存储、运行时组件、生成与读取、上传/候选、GC、`springboot-admin` 和验收拆分；`git diff --check` 通过。
- 用户已授权开始实施。已重新读取文件化计划并建立七阶段执行清单，当前从数据库迁移和通用存储底座开始。
- 首轮结构检查误读了不存在的 `BaseEntity.java` 一次；已确认实际基类为 `BaseUuidEntity`，不重复尝试该路径。
- 头像迁移失败测试首次正确报告三个新表缺失；加入初版迁移后，SQLite 暴露 Liquibase 重建 `users` 会把 `user_roles` 外键改指 `users_temporary`。已改为 SQLite 原生逐列追加、PostgreSQL 保留 `avatar_object_id` 外键，并在 SQLite 交由应用/GC 校验引用。
- 修正迁移后，`SqliteMigrationTest#liquibaseCreatesCoreTables` 已通过：新表、用户头像字段、唯一索引、候选外键均已验证，且现有用户角色关系未被破坏。
- 用户头像状态测试先编译失败后通过，已实现不透明公开 ID、基于内部 ID 的稳定初始 seed、三种头像类型和独立 revision；头像变化不会递增认证 token version。
- 本地存储后端测试先失败后通过，已覆盖受控随机键、同卷临时写入/原子移动、SHA-256、读取、幂等删除、路径逃逸拒绝和无残留健康探针。
- 存储服务组合测试首次运行遇到本机 JDK 21 的 Mockito self-attach 初始化失败；该测试将改用 JDK 动态代理的内存 Repository，不为此引入 JVM Agent 配置。
- 新存储组合测试改为内存 Repository 后，存储服务、本地后端和用户头像状态共 6 个测试通过。
- 运行时配置已从可变 ConcurrentHashMap 重构为原子不可变快照，新增组件自动探测开关、间隔和头像生成开关，以及数据库全量 reload；实现了本地存储运行时组件、状态持久化/内存快照、自动刷新和管理 API。
- `RuntimeSettingServiceTest` 连续两次受同一 Mockito self-attach 环境错误影响，未出现业务断言失败；后续不再重复该运行路径，先以无 Mockito 测试和完整集成测试验证。
- DiceBear 客户端测试在受限沙箱内因禁止绑定本地端口失败；获准在沙箱外运行后通过，固定 Lorelei 路由、查询参数、Content-Type/大小和危险 SVG 拒绝均已验证。
- 首次编译公开头像 Controller 时发现当前 Spring `HttpHeaders` 没有 `CONTENT_SECURITY_POLICY` 常量；已改用标准字符串 Header 名称，不重复依赖该常量。
- WebP 与 EXIF 依赖首次解析因沙箱禁止写入用户 Maven 仓库失败；按权限流程在沙箱外执行后依赖下载和主代码编译成功，未改动 Maven 仓库位置或绕过权限边界。
- 头像图片处理器测试先因目标类型不存在而编译失败，符合 TDD 预期；开始实现 JPEG/PNG/WebP 静态图校验、EXIF 方向修正、可替换居中裁剪和 256×256 PNG 重编码。
- 图片测试在受限沙箱加载 WebP 原生库时触发 JVM `Abort trap`；同一测试在沙箱外能正常启动并实际暴露 PNG reader 的 `seekForwardOnly` 与帧数搜索冲突，确认原生库本身可运行，已据此修正 reader 输入模式。
- 修正 ImageIO reader 模式后，头像处理器 3 个测试全部通过，包括当前 macOS ARM 环境的真实 WebP 编解码。
- 随机候选实体先以缺类编译失败建立基线；随后补充候选领域状态、上传/恢复/随机预览/确认、匿名预览及管理员重置接口。首次编译发现 `UserProfileController` 遗漏 `Pattern` import，已就地修正。
- 候选领域测试与 multipart 超限稳定错误映射共 10 个测试通过；上传图片处理增加了最多 4 个并发任务的进程内保护。
- 存储利用率报告先因目标服务/模型不存在编译失败，随后开始实现数据库引用分类、缺失/未登记/临时文件统计，以及 `avatar-candidates`、`avatar-storage-orphans` 两个 GC 资源。
- 新增存储报告分类测试通过。首次完整 SQLite Context 验证发现图片处理器存在多个构造器后 Spring 不再自动选择单参数生产构造器；已显式标注注入构造器，问题与数据库迁移无关。
- 修正构造器注入后，完整 `SqliteMigrationTest` 5 个用例通过，确认新迁移、JPA 映射、Repository 查询和 GC 注册能在同一 Spring Context 中运行。
- `springboot-admin` 已接入本人头像弹窗、三个布局的头像入口、用户列表/详情头像、管理员重置、运行时组件刷新以及 GC 存储报告；`pnpm typecheck` 与前端 `git diff --check` 已通过。
- 首次后端全量测试执行 118 个用例，头像/WebP/DiceBear/SQLite 新测试均通过，但暴露 4 个集成回归：测试 `application.yml` 遮蔽主配置导致 Actuator 只暴露 health、运行时调度器启动后立即写 SQLite 造成测试事务锁竞争，以及无 AuditService 的两个纯单元测试读取了未初始化审计快照。已分别改为追加式 test properties、调度器首个周期后运行和空审计依赖短路。
- 三类失败复跑后 SQLite 锁竞争和运行时快照单测均已恢复；发现 Spring 配置加载中同名 `application.properties` 仍会遮蔽主 YAML，而非合并。已移除测试配置文件，改由 Surefire 系统属性指定 `target` 存储根和关闭启动探测，保留主配置的 Actuator 暴露设置。
- 清理旧测试产物后 `StarterApplicationTest` 5 个用例通过；在允许 JVM attach 与绑定本地临时端口的环境执行 `./mvnw clean test`，当时 118 个测试、0 失败、0 错误、1 个 PostgreSQL/Testcontainers 条件跳过。
- 首次 `./mvnw clean verify` 的全部行为测试通过，但新增模块使整体行覆盖率降至 66%，未达到 70% 门禁。补充头像管理、惰性生成/降级和运行时组件状态的行为测试，并增加 SVG 协议相对外部引用回归测试后，最终 `verify` 为 130 个测试、0 失败、0 错误、1 跳过；行覆盖率 75.10%、分支覆盖率 50.84%，两项门禁通过。
- 管理端 `pnpm build` 成功，产物约 2.33 MB；`docker compose config --quiet` 通过。API、运行时设置、部署备份、README、项目状态和头像规格状态已同步更新，第 12 阶段完成。
- 用户指出“运行时组件”混淆了配置缓存刷新和外部依赖健康探测，并确认按拆分方案实施第 13 阶段。当前结论：热点业务配置已读内存，但自动数据库对账错误地挂在组件健康调度器上；管理页和 `/system/runtime/refresh` 也同时刷新两种状态。
- 第 13 阶段设计收口：新增通用缓存 revision/provider 注册表，首批纳管运行时设置和持久化 IP 规则；配置缓存与服务依赖各自拥有调度、状态和手动刷新接口，配置写入继续保持事务提交后才发布。
- 第 13 阶段首轮失败测试符合预期：`RuntimeCacheRegistryTest` 因通用 provider、status 和 registry 尚不存在而出现 16 个编译错误；该失败用于锁定稳定顺序、单缓存强制刷新和仅按 revision 自动对账的契约，不重复运行未修改代码。
- 通用缓存首轮实现后定向测试中，注册表、组件解耦和 IP 快照测试均通过；SQLite 明确拒绝后置 `addUniqueConstraint`，旧设置测试仍使用已迁移的 `runtime.component-health.*` 键。迁移改用跨库唯一索引，测试契约同步到 `runtime.dependency-health.*` 后再复跑。
- 第 13 阶段完成：新增 `runtime_cache_revisions`、通用 provider/registry/status、独立缓存调度器和管理 API；`RUNTIME_SETTINGS` 与 `IP_ACCESS_RULES` 已纳管，写事务内递增 revision，提交后才发布本机不可变快照，自动任务仅在 revision 变化时重载。
- DiceBear 与本地存储改为独立“服务依赖”接口和调度，原组件全量刷新不再调用配置 reload；管理后台同步拆为“配置缓存”和“服务依赖”，旧混合 `/system/runtime` 客户端调用已移除。
- 验证完成：后端 `./mvnw clean verify` 为 132 个测试、0 失败、0 错误、1 个 PostgreSQL/Testcontainers 条件跳过，JaCoCo 门禁通过；管理端全部 10 个 Node 契约脚本、`pnpm typecheck`、`pnpm build` 和两个仓库 `git diff --check` 通过，Compose 配置有效。
- 本地后端已迁移现有 SQLite 并重启于 8081，前端继续运行于 8849，用户自管 DiceBear 保持在 3100；健康检查直连和前端代理均为 `UP`，OpenAPI 已暴露六个独立缓存/依赖路径，数据库确认两个 provider revision 行和四个新运行时键。
- 按用户反馈重新整理 `/system/config`：运行时管理配置提升到页面首位并使用响应式网格，配置缓存与服务依赖紧随其后并排展示，其余安全治理配置后置；管理端契约测试、类型检查、生产构建和 diff 检查通过。
- 新增独立运维文档 `docs/operations/ip-access-rule-cache.md`，说明持久化 IP 规则独立缓存的边界、请求期性能原因、revision/提交后发布/多实例对账、手动重载、失效时间语义，以及它与运行时字符串 IP 策略的组合关系。
- 用户要求后台显示全部运行状态检查，并指出本地文件存储不是外部服务。第 14 阶段开始：将 DiceBear 分类为服务依赖、本地文件存储分类为本地资源，后台逐项显示中文状态。
- 第 14 阶段失败契约符合预期：后端 `RuntimeComponentResponse` 尚无 `kind()` 导致测试编译失败，管理端仍调用 `/system/dependencies` 而未提供运行检查分类；两项失败分别锁定 API 分类字段和新 `/system/runtime-checks` 客户端契约。
- 管理端实现后的首次契约复跑只因测试仍要求静态标签 `>服务依赖<` 失败，而页面采用数据驱动的 `checkGroups.title` 渲染；契约已改为验证分组模型中的两个中文标题，类型检查本身已通过。
- 第 14 阶段完成：管理 API 改为 `/system/runtime-checks`，响应新增 `kind`；DiceBear 归类为 `SERVICE_DEPENDENCY`，本地文件存储归类为 `LOCAL_RESOURCE`，后台按中文类别分组并逐项显示“正常/异常/已停用/检查中/未检查”。运行检查配置键迁移为 `runtime.health-check.*`。
- 完整验证通过：后端 `./mvnw clean verify` 为 132 个测试、0 失败、0 错误、1 个 PostgreSQL/Testcontainers 条件跳过并通过 JaCoCo 门禁；管理端全部 Node 契约、`pnpm typecheck`、`pnpm build`、Compose 和两个仓库 diff 检查通过。
- 运行态迁移和重启完成：8081 健康状态为 `UP`，OpenAPI 只暴露新的三条 `/runtime-checks` 路径；数据库已保存 `runtime.health-check.enabled/interval-seconds`。本地存储检查为 `UP`，DiceBear 因现有 `avatar.generator.enabled=false` 显示 `DISABLED`；3100 服务未被操作。
- 受保护接口实测时，首次登录请求遗漏 `rememberMe` 得到 400，按 DTO 补齐后现有管理员密码校验得到 401；未修改账号或密码。随后使用 OpenAPI 契约、迁移日志和数据库状态表完成只读运行态验证。
- 首次查询 `system_settings` 误用了不存在的 `setting_value` 列而失败；读取表结构后改用实际的 `value_json`，确认新配置键和值正确。
- 用户指出配置页状态上下重复、配置缓存卡片化过重，并要求把运行状态检查合并到 welcome 页现有“服务健康”。第 15 阶段开始：先核对 welcome 健康实现和页面契约，再进行跨页面信息架构重构。
- 第 15 阶段失败契约符合预期：配置页尚未使用 `el-table` 展示缓存且仍包含运行检查请求/区域，welcome 页也尚未接入运行检查 API；失败锁定“配置页只配置、welcome 统一健康”的页面边界。
- 第 15 阶段页面实现完成：配置页移除运行检查 API、摘要和详情，配置缓存改为 revision/条目/异常/操作表格，其余服务配置合并为单卡片标签页；welcome 将 Actuator、DiceBear 和本地存储合并到同一个“服务健康”表格，并按权限提供全量刷新与单项重试。
- 新页面契约和 `pnpm typecheck` 已通过；普通用户仅请求公开 Actuator 健康，拥有 `system:config:read/write` 的管理员才读取或重试内部运行检查。
- 第 15 阶段验证完成：全部 10 个 Node 契约、`pnpm typecheck`、`pnpm build` 和两个仓库 `git diff --check` 通过；8849 的 welcome/config 源模块均由 Vite 成功编译返回 200，8081 Actuator 仍为 `UP`。对应规格、实施计划、运维说明和项目状态已同步。
- 第 16 阶段提交前收口：`RuntimeComponent` 的检查类别改为实现方强制声明，DiceBear 明确标记为服务依赖；旧 `/system/dependencies/**` 暂作已废弃兼容入口；welcome 在检查均无异常但存在停用项时汇总为“部分停用”，避免误报“正常”。

## 2026-08-04

- 开始第 17 阶段只读审计：检查全部运行时配置、统一缓存与 IP 访问规则的职责边界，并按实际过滤器、匹配规则和运行配置复现“连续请求未触发限流”。本阶段先输出证据、冲突清单和设计建议，不直接改变安全行为。
- 已完成第一轮静态链路核对：确认自动封禁闭环不存在、端点限流默认无匹配路径、全局限流默认 120 次/60 秒/IP，且登录防爆破按用户名而不是 IP 统计。
- 一次用于统计配置键引用的 zsh 命令因把换行键列表作为单个 `rg` 参数而失败；后续改用逐行读取，不重复该写法。
- 首次运行 8081 限流实测脚本时误用 zsh 只读变量名 `status`，脚本在发出登录请求后立即停止，尚未发出 25 次目标接口请求；改用任务专用变量名后再测。
- 真实 8081 验证完成：审计日志接口前 20 次返回 401，第 21–25 次返回 429，限流链路确实工作；同时确认 429 缺少 `Retry-After` 且不会生成自动 IP 规则。
- 完成运行时键与后台可见性对照：27 个后端键只有 20 个出现在配置页；确认无结构字符串 IP 名单与结构化 IP 规则重复并存，且当前数据库状态已经出现“规则页为空、字符串黑名单非空”的认知冲突。
- 完成多实例缓存、生产安全校验、GC 调度/锁、运行检查发布与控制面恢复路径审计，形成 P0/P1/P2 收敛方案；第 17 阶段完成。本轮未修改业务代码、数据库或后台页面。
- 用户确认实施审计方案，第 18 阶段开始。第一版范围锁定为结构化 IP 规则单一来源、429 可观测性、连续超限自动临时封禁、规则页统一展示、控制面恢复、有限内存状态，以及运行时配置可见性/缓存自关闭修复。
- 已加入 019 IP 防护迁移和规则来源/状态/触发统计字段，扩展规则 DTO 长度校验与管理响应；加入 Caffeine 直接依赖，为限流和违规状态设置容量上限做准备。
- 完成第一轮限流/自动封禁主链路和运行时设置元数据改造；首次主代码编译因 Java 无法推断流排序 lambda 的实体类型失败，已显式声明 `RuntimeSetting` 类型后继续验证。
- 新增限流剩余额度/重试时间、端点模式统一桶和自动封禁阈值测试，定向 6 组后端测试已通过。管理端已改为按后端 group/min/max/dangerous 元数据动态渲染全部配置，IP 规则页开始统一展示人工、迁移、自动封禁和历史状态。
- 首次前端契约循环中类型检查通过，但 IP 规则页改写后没有保留两个既有静态页面契约（精确单根类名和统一页头布局类），导致 2 个脚本失败；已恢复这两个布局契约。该轮后端编译命令因工作目录在管理端而未执行，下一轮回到后端目录单独验证。
- 新增运行时 CORS 语义校验测试的首次定向执行仅有 1 个断言失败：测试预期英文错误文案与项目实际统一文案不一致（`Invalid request parameter` / `Request parameter is invalid`），业务校验已正确抛错；已将断言修正为项目现有文案后复测。
- 修正后定向执行运行时设置、缓存调度/故障隔离和自动封禁 4 个测试类，共 11 个用例全部通过；测试中的缓存刷新异常堆栈是故障隔离用例的预期日志。
- 后端 `./mvnw clean verify` 完成：140 个测试、0 失败、0 错误、1 个 PostgreSQL/Testcontainers 条件跳过，JAR 打包与 JaCoCo 覆盖率门槛通过；`docker compose config --quiet` 通过。
- 管理端 `pnpm typecheck`、全部 11 组 `scripts/*.test.mjs` 契约测试和 `pnpm build` 均通过，生产构建产物约 2.34 MB。
- 已核验并替换 8081 上属于本仓库的旧 Maven/Java 进程；新进程成功应用 019 迁移并把旧字符串 deny `192.168.31.133` 迁移为 `MIGRATED` 结构化规则，旧两项设置已删除，6 项控制面/自动封禁设置已初始化。3100、8080 和 8849 未被重启。
- 首次健康烟测受本机 HTTP 代理环境影响返回代理生成的空 502，并因误用 SQLite 字段名 `key/value` 查询失败；改为 `curl --noproxy '*'` 和真实列名 `setting_key/value_json` 后，Actuator 返回 200/UP，迁移数据检查通过。
- 对 8081 的受保护审计端点连续请求实测：第 21 次返回 429，并带 `Retry-After: 60`、限额/剩余量/重置时间头。回环地址按安全设计只触发限流、不创建自动封禁，避免本机开发或代理自锁。
- 8849 的 Vite 进程确认属于 `springboot-admin`，通过其 `/api` 代理访问 8081 得到预期 401，证明前端仍指向新后端；运行缓存页将状态文案收敛为“最近一次检查的一致/待同步”，避免把上次观察结果误读为实时查库。31/31 个运行时配置键均已写入 API 手册，第 18 阶段完成。
