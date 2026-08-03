# 用户头像、通用存储与运行时管理实施计划

**目标：** 实现后端托管的 Lorelei 系统头像、随机候选、用户上传、匿名读取、通用本地存储、运行状态检查和头像 GC，并同步 `springboot-admin`。

**设计依据：** `docs/superpowers/specs/2026-08-03-backend-avatar-design.md`

**当前状态：** 完整设计已确认；内置最终回退 SVG 已生成；业务代码尚未实施。

## 1. 契约测试和迁移骨架

- 为公开 URL、DTO 字段、用户/管理员权限、运行时接口和 GC 资源先建立失败测试。
- 新增 Liquibase changeset：用户头像字段、`storage_objects`、`avatar_candidates`、`runtime_component_statuses` 及索引/外键。
- 加入 master changelog，并扩展 SQLite/PostgreSQL 迁移断言。
- 实现既有用户头像身份回填；测试重复启动幂等、唯一冲突重试和空值修复。

## 2. 通用存储模块

- 新建 `modules/storage` 的 application/infrastructure 分层和 `StorageService` 端口。
- 实现 `LocalStorageBackend`：受控对象键、临时写入、fsync、原子移动、流式读取、幂等删除、stat 和分页扫描。
- 增加 `StorageObject` 实体、Repository、状态变化和按 namespace 的统计查询。
- 测试路径穿越、绝对路径、符号链接逃逸、原子移动失败、文件缺失和测试目录隔离。
- 所有测试只使用 JUnit 临时目录，不读取或写入开发者 `data`。

## 3. 统一运行时设置与运行状态

- 扩展 `RuntimeSettingService.reload()`：完整校验后一次性替换不可变配置快照。
- 新增 `runtime.health-check.enabled`、`runtime.health-check.interval-seconds`、`avatar.generator.enabled` 定义和初始化；配置缓存刷新另由 `runtime.config-refresh.*` 管理。
- 新建 `RuntimeComponent`、状态实体、Registry、不可变内存快照和状态提交后更新规则。
- 实现全量刷新、单组件刷新和动态间隔调度器；并发刷新同一组件时合并或拒绝重复执行。
- 新增 `/api/v1/system/runtime-checks`、全量检查和单项检查 API，复用 `system:config:read/write`；配置缓存使用 `/api/v1/system/runtime-caches`。
- 更新启动监听顺序：组件有界探测先完成，`StartupSummaryLogger` 后输出一次状态摘要。
- 测试配置事务回滚、reload 非法值、状态持久化、内存一致性、失败脱敏和应用非阻断启动。

## 4. 本地存储资源检查

- 实现 `LOCAL_STORAGE` 探针：目录创建、探针文件写/读/移动/删和空间统计。
- 底层真实读写失败时向 Registry 报告 `ERROR`。
- 存储不可用时写接口返回 `STORAGE_UNAVAILABLE`，头像读取转最终回退。
- Actuator details 可展示脱敏状态，但不得让头像存储故障导致容器 liveness 失败。

## 5. DiceBear 生成服务依赖检查

- 从 `APP_AVATAR_GENERATOR_BASE_URL` 绑定生成器配置，默认 `http://localhost:3100`。
- 使用 Spring `RestClient` 实现固定 Lorelei SVG 请求，限制超时、重定向、类型和响应大小。
- 实现 `AVATAR_GENERATOR` 探针和真实调用失败后的即时 `ERROR` 状态。
- 生成业务只在运行时开关开启且最新快照 `UP` 时调用服务。
- 校验 SVG 基础安全结构；seed 和完整请求 URL 不进日志。
- 测试成功、连接拒绝、超时、非 2xx、错误类型、超大内容、危险 SVG、状态门控和恢复重试。

## 6. 用户头像领域模型和公开读取

- 扩展 `User`：公开头像 ID、类型、seed、对象引用和 revision；头像变化不影响 token version。
- 新建头像 URL 构建器，所有用户视图统一增加 `avatarUrl`；仅 `AdminUserResponse` 增加 `avatarType`。
- 实现初始 seed 单向派生和随机 seed 生成。
- 新建 `UserAvatarService`，支持初始惰性生成、对象缓存、按用户短期锁和并发失败对象回收。
- 新增匿名最小响应 `GET /api/v1/avatars/{publicId}`，直接返回 SVG/PNG。
- 加入正常对象长期 immutable 缓存、回退短缓存、ETag、nosniff 和 SVG CSP。
- 生成/存储不可用时返回 classpath 内置 `default-lorelei.svg`，不把回退写为用户缓存。

## 7. 上传图片处理

- 引入并锁定 `com.github.usefulness:webp-imageio` 与 `com.drewnoakes:metadata-extractor`，记录 JNI 平台边界、传递依赖和许可证。
- 新建 `AvatarImageProcessor` 与 `AvatarCropStrategy`；第一版实现 `CenterSquareCropStrategy`。
- 实现 5 MiB 流式限制、JPEG/PNG/WebP 真格式识别、静态帧校验、8192 边长和 4000 万像素限制。
- 读取 EXIF 方向，旋转/翻转后中心裁剪，高质量缩放到 256×256，重新编码 PNG并清除元数据。
- 限制并发图片处理数量和临时内存占用。
- 新增 `PUT /api/v1/users/me/avatar` 和幂等 `DELETE`；实现事务提交/回滚文件补偿和审计。
- 测试透明 PNG、方向 JPEG、静态 WebP、伪格式、动画、损坏、超限和并发替换。
- CI 增加 Linux amd64/arm64 与 macOS arm64 至少一次真实 WebP 解码冒烟。

## 8. 随机系统头像候选

- 实现 `AvatarCandidate` 实体、Repository、30 分钟失效、用户归属和每用户最多 5 个有效候选。
- 新增候选创建接口、匿名候选 SVG 读取和确认使用接口。
- “换一张”只新建候选；确认时把候选对象提升为当前对象，切换 `SYSTEM_RANDOM` 并递增 revision。
- 过期、已消费、越权和重复确认分别返回稳定错误；候选公开标识不可枚举。
- 增加每用户端点限流和 `USER_AVATAR_RANDOMIZED` 审计。

## 9. 管理员重置和用户响应覆盖

- 新增 `POST /api/v1/admin/users/{id}/avatar/reset`，权限 `system:user:write`。
- 重置恢复 `SYSTEM_INITIAL` 和派生 seed，提交后清理旧对象，不撤销会话。
- 管理员用户列表/详情批量组装头像 URL，避免引入 N+1。
- 注册、当前用户、管理员用户等正式响应和 OpenAPI 同步字段；普通响应不泄露类型、seed、对象引用。
- 增加本人恢复、管理员重置、权限拒绝和审计测试。

## 10. GC 与存储报告

- 新增 `avatar-candidates` 和 `avatar-storage-orphans` 两个 `GcResource`。
- dry-run/真实执行覆盖过期候选、已消费候选、1 天安全窗口后的孤儿、待删除对象、临时文件。
- 任何扫描和删除都限定在本地存储根与已知 namespace，禁止任意路径输入。
- 新增 `/api/v1/system/storage/report`，返回对象数、字节、引用、孤儿、缺失、磁盘空间和扫描元数据。
- 保留现有 `GcResourceResult` 的候选/删除语义，不把复杂利用率强塞进运行历史 JSON。
- 测试引用保护、对象缺失、元数据孤儿、文件孤儿、批量上限和跨根目录保护。

## 11. 配置和部署

- 更新 `application.yml`、`.env.example`、配置属性校验和测试 Profile。
- `compose.yaml` 为应用增加 `app-data:/app/data`；只通过环境变量配置 DiceBear 可达地址。
- 不在 Java 应用镜像内嵌 Node，也不要求 DiceBear 成为 Spring 启动依赖。
- 更新 `.dockerignore`、部署/备份说明；验证数据库和存储卷必须成对备份。
- 只运行 `docker compose config --quiet` 做静态验证，不擅自构建或发布镜像。

## 12. `springboot-admin` 当前用户和头像弹窗

- `CurrentUser` 增加 `avatarUrl`，Store 每次 profile 加载覆盖 avatar，登出清空。
- 导航栏头像菜单增加“更换头像”，弹窗提供系统头像与上传头像页签。
- 系统页签实现候选预览、换一张、确认、恢复初始；上传页签只做轻量格式/5 MiB 提示。
- 成功操作后刷新 Store；加载失败继续使用本地静态占位图。
- 增加 API/Store/弹窗契约测试，不在前端安装 DiceBear 或图片裁剪库。

## 13. `springboot-admin` 管理页面

- 用户列表第一列加入 40px 头像，详情头部加入 80px 头像和头像类型。
- `system:user:write` 用户可二次确认重置；不提供代上传和代随机。
- 运行时配置页提供探测总开关、刷新周期和表格式配置缓存管理；组件运行状态、全量刷新和单项重试合并到 welcome 页“服务健康”。
- GC 页增加两个资源策略和存储利用率报告。
- 同步前端硬编码设置分组、中文展示映射、API 类型和 Node 契约脚本。

## 14. 正式文档和测试页

- 更新 `docs/API_REFERENCE.md`、用户/认证专题、`docs/operations/runtime-settings.md`、部署文档、README、PROJECT_STATUS。
- 更新 API 测试页，但保留用户现有未提交修改并谨慎合并。
- 新增错误码、运行时键、环境变量、审计事件、缓存语义和故障降级说明。
- 若最终提交，按项目规范在同一提交增加 retrospective 及索引。

## 15. 验证顺序

1. 定向迁移、存储、运行时、头像、图片处理和 GC 测试。
2. `./mvnw test`。
3. `./mvnw verify`。
4. 管理后台全部 Node 契约脚本、`pnpm typecheck`、`pnpm build`。
5. 两仓库 `git diff --check`。
6. `docker compose config --quiet`。
7. 宿主机 HTTP 冒烟和 DiceBear/存储故障演练。

## 16. 完成标准

- 普通用户 DTO 始终提供不含内部 ID 的 `avatarUrl`；管理员额外看到 `avatarType`。
- 系统初始头像、随机候选确认、JPEG/PNG/WebP 上传和恢复全部可用。
- 上传最终文件始终为安全的 256×256 PNG；生成头像为受控 Lorelei SVG。
- DiceBear 启动失败显示一次摘要并进入 `ERROR`，应用仍可用；自动/手动刷新成功后恢复 `UP`。
- 本地存储由通用模块管理并有运行时状态、利用率报告和 GC 收敛。
- 管理后台用户、头像弹窗、运行时和 GC 页面均同步完成。
- 所有回归、覆盖率、类型检查、构建和配置校验通过；未经明确授权不提交、不推送。
