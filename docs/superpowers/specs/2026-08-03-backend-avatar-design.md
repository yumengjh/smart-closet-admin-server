# 用户头像与通用存储完整设计

## 1. 文档状态

- 设计日期：2026-08-03
- 适用项目：`springboot-start` 后端、`springboot-admin` 管理后台
- 头像生成器：自托管 DiceBear 10.x，固定 `Lorelei` 风格
- 当前阶段：第一版已实施并完成后端全量测试、管理端类型检查/生产构建和 Compose 静态校验
- 内置资源：`src/main/resources/avatars/default-lorelei.svg`

## 2. 已确认的产品决策

1. 普通用户只能管理自己的头像；拥有 `system:user:write` 的管理员可以把其他用户头像强制重置为初始系统头像，但不能代替用户上传图片。
2. 头像图片允许匿名读取，方便 `<img>` 直接使用；公开 URL 只包含独立随机公开标识和非敏感版本号，不包含用户 ID、用户名、文件路径、存储对象 ID或生成 seed。
3. 第一版上传只接受静态 JPEG、PNG、WebP；后端统一处理并保存为 256×256 PNG。拒绝 SVG、GIF、HEIC、AVIF、BMP 和动画 WebP。
4. 新用户使用基于内部用户 ID 单向派生的初始 Lorelei；用户可以预览随机系统头像、确认后切换，也可以上传自定义头像。
5. 数据库保留 `avatarType`；普通用户响应不返回该字段，管理员响应可以返回。
6. DiceBear 和本地存储纳入独立的服务依赖健康管理：启动探测、一次性启动摘要、数据库状态、内存快照、自动探测、全量手动探测、单依赖重试及后台展示；配置缓存使用另一套 revision 刷新协议。
7. 文件存储抽象成独立通用模块；第一版只实现本地文件系统，头像模块在其上封装业务规则。
8. 头像临时文件、孤儿对象、引用利用率和空间占用纳入现有 GC 管理中心。
9. 随机系统头像采用“生成一张候选并预览，用户确认后才写入当前头像”的两阶段操作。
10. 第一版图片裁剪由后端自动完成；裁剪策略保留扩展点，未来 C 端可上传裁剪参数或直接上传裁剪结果。

## 3. 目标与非目标

### 3.1 目标

- 所有需要展示用户的接口统一返回可直接使用的 `avatarUrl`。
- 前端不安装 DiceBear、不接触 seed、不拼接存储路径，也不判断 SVG/PNG。
- DiceBear、存储目录或头像缓存故障不能阻断应用启动、注册、登录和用户资料读取。
- 上传内容必须经过真实解码、尺寸限制、裁剪、缩放和重新编码，不保存用户原始文件。
- 头像文件在容器重建后仍可保留，并能被统计、检查和清理。
- 通用存储模块可在后续承载普通文件、视频及对象存储实现。

### 3.2 第一版非目标

- 不使用 DiceBear 公共 API。
- 不使用 CDN、S3/OSS/MinIO，不把图片 BLOB 写入业务数据库。
- 不提供用户头像历史、头像审核、人工裁剪、滤镜或动图。
- 不允许管理员代用户上传或随机选择头像。
- 不承诺多实例下的分布式锁和跨节点实时缓存广播；第一版保持当前模块化单体、单应用实例边界。

## 4. 总体架构

```text
springboot-admin / 其它 C 端
            |
            | 只使用 avatarUrl、上传和候选确认接口
            v
┌──────────────────────────────────────────────────────────┐
│ users/avatar 业务模块                                    │
│ 用户权限、头像类型、seed、公开标识、版本、候选、审计      │
└───────────────┬──────────────────────┬───────────────────┘
                |                      |
                v                      v
┌────────────────────────┐   ┌────────────────────────────┐
│ storage 通用存储模块    │   │ runtime 服务依赖健康管理   │
│ 对象元数据、读写删除、  │   │ 配置快照、状态快照、探测、 │
│ 原子落盘、统计          │   │ 定时刷新、手动重试         │
└────────────┬───────────┘   └─────────────┬──────────────┘
             |                             |
             v                             v
      本地持久化数据卷                 DiceBear :3100
             |
             v
       maintenance GC
```

职责边界：

- `users/avatar` 决定某个用户当前使用哪一种头像，以及何时替换。
- `storage` 不认识用户权限、DiceBear 和头像类型，只管理不可变对象及底层存储。
- `runtime` 不保存头像文件，只管理组件是否可用以及何时重新探测。
- `maintenance` 只通过受控资源实现扫描和清理，不接受任意目录或任意表名。

## 5. 用户头像状态模型

### 5.1 头像类型

数据库枚举字符串使用以下固定值：

| 值 | 含义 | 当前对象 |
| --- | --- | --- |
| `SYSTEM_INITIAL` | 基于用户内部 ID 派生的初始 Lorelei | 可为空，允许惰性生成 |
| `SYSTEM_RANDOM` | 用户确认过的随机 Lorelei | 正常情况下指向已生成 SVG |
| `UPLOADED` | 用户上传并处理后的图片 | 指向 PNG |

`avatarType` 只在管理员 DTO 中返回。普通用户只得到 `avatarUrl`，避免客户端依赖内部状态机。

### 5.2 seed 规则

- 初始 seed：`base64url(SHA-256("avatar:lorelei:v1:" + internalUserId))`。
- 随机 seed：后端使用 `SecureRandom` 生成 256 bit 随机值并做 base64url 编码。
- seed 只保存在数据库并传给内网 DiceBear，不写入日志、审计详情、公开 URL或前端响应。
- 用户名、显示名、邮箱和任何请求文本都不能作为 seed 或 DiceBear 参数。

### 5.3 公开 URL

每个用户单独生成 128 bit 以上的 `avatarPublicId`，与内部用户 ID 无推导关系：

```text
/api/v1/avatars/{avatarPublicId}?v={avatarRevision}
```

- `avatarPublicId` 在普通头像更换时保持稳定，避免外部引用永久失效。
- `avatarRevision` 每次正式变更后递增，只用于缓存失效，不是数据库实体版本。
- URL 不包含内部用户 ID、存储对象 ID、对象键、文件名、类型或 seed。
- 受保护的管理员用户管理 API 继续沿用项目现有内部用户 ID 路由；禁止的是公开媒体 URL 泄露内部标识。

## 6. 数据库设计

### 6.1 `users` 新增字段

| 字段 | 类型 | 约束 | 说明 |
| --- | --- | --- | --- |
| `avatar_public_id` | varchar(36) | 唯一、最终非空 | 公开头像随机标识 |
| `avatar_type` | varchar(32) | 非空 | 三种头像类型之一 |
| `avatar_seed` | varchar(64) | 最终非空 | 当前系统头像 seed；上传状态仍保留，便于恢复 |
| `avatar_object_id` | varchar(36) | 可空、外键 | 当前已持久化对象；回退状态可为空 |
| `avatar_revision` | bigint | 非空、默认 0 | URL 缓存版本 |

头像变更使用 `User` 已有乐观锁版本解决并发覆盖，但不递增认证用的 `token_version`，也不撤销登录会话。

### 6.2 `storage_objects`

通用存储对象元数据表：

| 字段 | 说明 |
| --- | --- |
| `id` | 内部 UUID，只供服务端关联 |
| `backend_code` | 第一版固定 `LOCAL` |
| `namespace` | 第一版使用 `AVATAR_GENERATED`、`AVATAR_UPLOADED`、`AVATAR_CANDIDATE` |
| `object_key` | 服务端生成的受控相对键，唯一且不公开 |
| `content_type` | `image/svg+xml` 或 `image/png` |
| `size_bytes` | 文件字节数 |
| `sha256` | 完整性和 ETag 来源 |
| `state` | `READY`、`DELETE_PENDING`、`MISSING` |
| `created_at` / `updated_at` / `version` | 审计和并发字段 |

通用表不存 `user_id`。某个对象是否被使用由头像表字段和候选表引用决定，避免存储模块依赖用户模块。

### 6.3 `avatar_candidates`

随机系统头像候选是短期资源：

| 字段 | 说明 |
| --- | --- |
| `id` | 内部 UUID |
| `public_id` | 高熵公开候选标识，唯一 |
| `user_id` | 候选所属用户，确认时校验 |
| `seed` | 随机 Lorelei seed，不返回前端 |
| `storage_object_id` | 候选 SVG 对象 |
| `expires_at` | 默认创建后 30 分钟 |
| `consumed_at` | 确认使用时间，可空 |

同一用户最多保留 5 个未过期候选。超过上限时先淘汰最旧候选，防止反复点击占用存储。

### 6.4 `runtime_component_statuses`

运行时探测结果与可编辑配置分开持久化：

| 字段 | 说明 |
| --- | --- |
| `component_code` | 主标识：`AVATAR_GENERATOR`、`LOCAL_STORAGE` |
| `status` | `UNKNOWN`、`CHECKING`、`UP`、`ERROR`、`DISABLED` |
| `last_checked_at` | 最近一次探测时间 |
| `last_succeeded_at` | 最近成功时间 |
| `latency_ms` | 最近探测耗时 |
| `consecutive_failures` | 连续失败次数 |
| `message` | 截断、脱敏后的运维说明 |
| `version` | 并发控制 |

管理员不能直接修改 `status`。状态只能由启动探测、自动探测、手动刷新或真实组件调用结果更新。

### 6.5 迁移与既有用户回填

- 使用新的 Liquibase changeset 创建表和字段，不修改已执行的历史 changeset。
- 为兼容 SQLite/PostgreSQL，新增头像字段先允许为空，由启动期批处理为既有用户生成 `avatarPublicId` 和初始 seed。
- 回填完成后应用层把空值视为数据异常；空库和测试库必须断言新注册用户一次性写全字段。
- 不在 Liquibase SQL 中使用数据库专有随机 UUID 函数。

## 7. 通用存储模块

### 7.1 端口

第一版定义以下服务端口，头像模块只依赖端口：

```text
StorageService.put(namespace, contentType, bytes) -> StoredObjectRef
StorageService.open(objectId)                    -> StoredObjectContent
StorageService.delete(objectId)
StorageService.stat(objectId)                    -> StoredObjectMetadata
StorageService.scan(namespace, cursor, limit)    -> StorageScanPage
StorageHealthProbe.check()                       -> ComponentCheckResult
```

禁止 API 调用方提供绝对路径或直接指定 `object_key`。

### 7.2 本地实现

默认根目录：

```text
${APP_STORAGE_LOCAL_ROOT:${APP_DATA_DIR:./data}/storage}/
  objects/avatar-generated/YYYY/MM/{random}.svg
  objects/avatar-uploaded/YYYY/MM/{random}.png
  objects/avatar-candidate/YYYY/MM/{random}.svg
  tmp/{random}.tmp
```

- 临时文件与最终文件位于同一数据卷，写完并 `fsync` 后使用原子移动。
- 文件权限遵循应用进程最小权限，禁止符号链接逃逸和路径穿越。
- 数据库只保存受控相对键；读取时再次归一化并确认结果仍在存储根目录内。
- Docker 应用必须挂载 `/app/data` 持久卷。
- 未来新增 S3/OSS 只增加新的 `StorageBackend`，不改变用户表和公开头像 URL。

### 7.3 文件与数据库一致性

头像替换流程统一采用：

1. 先写入不可变新对象并得到 `storage_object_id`。
2. 开启用户业务事务，替换引用并递增 `avatarRevision`。
3. 事务回滚：删除新对象；若进程崩溃，由 GC 识别为孤儿。
4. 事务提交：旧对象标记待删除并异步/提交后尽力删除；失败交给 GC。

文件系统和数据库无法形成真正的原子事务，因此所有异常路径都必须可由幂等删除和 GC 收敛。

## 8. 图片上传和处理

### 8.1 输入限制

- 请求文件最大 5 MiB。
- 宽、高分别不超过 8192 像素，总像素不超过 40,000,000。
- 只接受真实可解码的 JPEG、PNG、WebP；扩展名和请求 `Content-Type` 只作为提示。
- 拒绝多帧/动画图片、空文件、截断文件、格式签名与解码结果不一致的文件。
- 用户原始文件名、EXIF、ICC 注释和其它元数据都不保存。

WebP 使用 `com.github.usefulness:webp-imageio` 的 ImageIO 插件解码；JPEG 的 EXIF 方向使用 `com.drewnoakes:metadata-extractor` 读取。由于 WebP 插件包含 libwebp JNI 二进制，CI 必须覆盖实际部署架构。统一输出使用 JDK PNG 编码器，不依赖 WebP 编码。

### 8.2 处理流水线

```text
流式限制读取
  -> 格式签名和 ImageReader 探测
  -> 只读尺寸/帧数/EXIF 方向
  -> 校验边长和总像素
  -> 完整解码
  -> 按 EXIF 旋转/翻转
  -> CenterSquareCropStrategy 中心正方形裁剪
  -> 高质量缩放到 256×256
  -> 重新编码 PNG
  -> 通用存储 put
```

透明 PNG/WebP 保留 alpha；JPEG 输出使用不透明 RGB。处理时限制单请求内存和并发数，避免大图并发耗尽堆。

### 8.3 未来前端裁剪扩展

图片处理内部使用 `AvatarCropStrategy`：

- 第一版固定 `CenterSquareCropStrategy`，HTTP 请求无裁剪参数。
- 后续可以增加归一化 `x/y/width/height` 裁剪框，并由后端再次校验边界。
- C 端也可以直接上传已裁剪方图，后端仍必须执行格式、尺寸、解码和重新编码安全检查。
- 存储对象和用户头像状态不依赖裁剪策略，因此升级不迁移历史数据。

## 9. DiceBear 生成与安全

### 9.1 配置

生成服务地址来自环境变量：

```properties
APP_AVATAR_GENERATOR_BASE_URL=http://localhost:3100
```

应用配置：

```text
app.avatar.generator.base-url
app.avatar.generator.connect-timeout
app.avatar.generator.read-timeout
app.avatar.generator.max-response-bytes
```

本地默认 `http://localhost:3100`。如果后端运行在 Docker 中，使用 `.env` 覆盖为容器可访问地址；不要求 Java 应用容器内再嵌 Node 运行时。

### 9.2 固定请求边界

- 固定路径：`/10.x/lorelei/svg`。
- 固定输出尺寸：256。
- 只有后端 seed 可进入请求。
- 校验 HTTP 状态、`image/svg+xml`、最大响应体和 SVG 基础安全结构。
- 禁止重定向到未受信任主机，不记录完整请求 URL，避免 seed 进入日志。

### 9.3 状态门控

- 只有 `AVATAR_GENERATOR` 最新内存状态为 `UP` 且运行时开关开启时，业务请求才调用 DiceBear。
- 一次真实生成调用失败后，立即把组件状态更新为 `ERROR`；后续请求直接降级，直到自动探测或手动重试成功。
- 业务请求不在每次生成前读数据库；状态服务以 `ConcurrentHashMap<String, RuntimeComponentSnapshot>` 提供不可变快照。
- 数据库保存最后已知状态用于重启和后台审计，但应用启动完成前必须重新探测，不能长期信任上一次进程的 `UP`。

## 10. 头像读取与降级

### 10.1 正常读取

```text
GET /api/v1/avatars/{avatarPublicId}?v={avatarRevision}
  -> 按 avatarPublicId 查用户
  -> 当前对象存在且本地存储 UP：流式返回 SVG/PNG
  -> 系统头像尚未生成且生成器 UP：互斥生成、存储、更新引用后返回
  -> 其它情况：返回 classpath 最终回退 SVG
```

并发首次读取使用按公开头像标识的短期本地锁。即便未来出现多实例重复生成，也依靠用户乐观锁只保留一个引用，失败实例删除自己的新对象。

### 10.2 降级顺序

1. 当前已持久化头像对象。
2. 系统头像且生成器可用时生成并缓存。
3. `classpath:/avatars/default-lorelei.svg` 最终回退。

DiceBear 不可用时不把最终回退写成用户的 Lorelei 缓存，否则服务恢复后用户会永久停留在公共回退图。上传对象缺失时也返回最终回退，但保留数据库状态并产生存储异常指标，由运行时检查和 GC 报告暴露。

### 10.3 HTTP 缓存与安全响应头

- 正常对象：`Cache-Control: public, max-age=31536000, immutable`，ETag 使用对象 SHA-256。
- 最终回退：`Cache-Control: public, max-age=60`，允许组件恢复后尽快重新获取。
- 设置准确的 `Content-Type`、`Content-Length`、`X-Content-Type-Options: nosniff`。
- SVG 增加限制性 `Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'; sandbox`。
- 原始图片响应使用最小公开响应，不经过 JSON 信封，但仍经过 Trace、IP、端点开关和限流过滤器。

## 11. 用户操作流程

### 11.1 注册与既有用户

- 注册保存用户后初始化 `avatarPublicId`、初始 seed、`SYSTEM_INITIAL` 和 revision 0。
- 注册不等待 DiceBear；首次读取或后台预热时生成。
- DiceBear 故障绝不让注册、登录或 `/users/me` 失败。

### 11.2 随机系统头像候选

1. 用户请求一个随机候选。
2. 后端确认生成器和存储状态均为 `UP`，生成新的安全随机 seed。
3. 调用 DiceBear、保存候选 SVG 和候选记录。
4. 返回短期 `previewUrl`、`candidateId`、`expiresAt`，不返回 seed。
5. “换一张”创建新候选，不修改用户当前头像。
6. 用户点击“使用此头像”后校验候选归属、有效期和未消费状态，再正式替换头像。

候选预览 URL同样只包含高熵公开标识。确认成功后候选对象直接转为当前生成头像，避免重复调用 DiceBear。

### 11.3 上传头像

- 上传成功立即切换为 `UPLOADED` 并递增 revision。
- 保留当前系统 seed，但当前对象改为上传 PNG。
- 旧对象在事务提交后清理。
- 上传依赖本地存储，不依赖 DiceBear；DiceBear `ERROR` 时仍允许上传。

### 11.4 恢复与管理员重置

- 用户“恢复初始头像”和管理员“重置头像”都切换为 `SYSTEM_INITIAL`，重新计算初始 seed，清空当前对象引用并递增 revision。
- 重置后可惰性重新生成；生成器不可用时先显示最终回退。
- 管理员重置需要 `system:user:write`，必须写审计；不撤销用户会话。

## 12. HTTP API 设计

### 12.1 用户和公开读取

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/avatars/{publicId}` | 匿名 | 返回当前头像原始内容 |
| `PUT` | `/api/v1/users/me/avatar` | 登录用户 | 上传 JPEG/PNG/WebP并切换 |
| `DELETE` | `/api/v1/users/me/avatar` | 登录用户 | 幂等恢复初始系统头像 |
| `POST` | `/api/v1/users/me/avatar/system-candidates` | 登录用户 | 生成一张随机候选 |
| `POST` | `/api/v1/users/me/avatar/system-candidates/{candidateId}/apply` | 登录用户 | 确认使用候选 |
| `GET` | `/api/v1/avatar-candidates/{candidateId}` | 匿名、短期 | 返回候选原始 SVG |

候选创建响应示例：

```json
{
  "data": {
    "candidateId": "FQyT5H4q8Xv4cZ5M5xQy9A",
    "previewUrl": "/api/v1/avatar-candidates/FQyT5H4q8Xv4cZ5M5xQy9A",
    "expiresAt": "2026-08-03T10:30:00Z"
  },
  "traceId": "...",
  "timestamp": 1785751200000
}
```

上传、删除和候选确认返回更新后的普通用户 DTO。普通用户 DTO 增加：

```json
{
  "avatarUrl": "/api/v1/avatars/YGmP0uJ7RteL5KY8VhR7Aw?v=3"
}
```

### 12.2 管理员用户接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/admin/users/{id}/avatar/reset` | `system:user:write` | 强制恢复初始系统头像 |

管理员用户列表和详情 DTO 增加 `avatarUrl`、`avatarType`。内部用户 ID 只出现在现有受保护管理 API 中，不进入公开媒体 URL。

### 12.3 运行时管理接口

| 方法 | 路径 | 权限 | 说明 |
| --- | --- | --- | --- |
| `GET` | `/api/v1/system/runtime-caches` | `system:config:read` | 当前配置缓存 revision、同步状态和加载结果 |
| `POST` | `/api/v1/system/runtime-caches/refresh` | `system:config:write` | 从数据库重载全部配置缓存 |
| `POST` | `/api/v1/system/runtime-caches/{code}/refresh` | `system:config:write` | 从数据库重载单个配置缓存 |
| `GET` | `/api/v1/system/dependencies` | `system:config:read` | 当前服务依赖健康状态 |
| `POST` | `/api/v1/system/dependencies/refresh` | `system:config:write` | 手动探测全部服务依赖 |
| `POST` | `/api/v1/system/dependencies/{code}/refresh` | `system:config:write` | 手动重试一个服务依赖 |
| `GET` | `/api/v1/system/runtime-config` | `system:config:read` | 保留现有配置列表接口 |
| `PUT` | `/api/v1/system/runtime-config/{key}` | `system:config:write` | 保留现有配置修改接口 |

单组件刷新是同步、短超时操作，返回刷新后的状态。全量刷新原子替换配置快照，但组件逐个返回自己的成功或错误，某一个组件失败不让整个响应变成 500。

## 13. 统一运行时管理

### 13.1 运行时设置

新增数据库运行时设置：

| 键 | 默认值 | 范围 | 说明 |
| --- | --- | --- | --- |
| `runtime.config-refresh.enabled` | `true` | boolean | 启用配置缓存 revision 自动对账 |
| `runtime.config-refresh.interval-seconds` | `60` | 10–3600 | 配置缓存检查周期 |
| `runtime.dependency-health.enabled` | `true` | boolean | 启用服务依赖自动探测 |
| `runtime.dependency-health.interval-seconds` | `60` | 10–3600 | 服务依赖探测周期 |
| `avatar.generator.enabled` | `true` | boolean | 是否允许生成新 Lorelei |

存储第一版不提供可关闭开关，因为它是上传和缓存的唯一持久化后端；可以查看和刷新状态，但不能通过误操作关闭。将来存在多个 backend 后再增加路由/启停配置。

### 13.2 配置快照刷新

扩展现有 `RuntimeSettingService`：

- `reload()` 在只读事务中完整读取受支持键，验证所有值后一次性替换不可变快照。
- 单项或批量更新仍只在事务提交后替换快照。
- 自动组件调度器每 10 秒检查一次是否到达动态间隔，不为每次间隔修改重建线程池。
- 手动全量刷新先重载配置，再按新配置探测组件。

### 13.3 组件探测

`AVATAR_GENERATOR`：

- 请求固定 health seed 的 64px Lorelei SVG。
- 校验连接、状态码、类型、响应大小和最小 SVG 结构。
- 使用严格连接/读取超时，不跟随任意重定向。

`LOCAL_STORAGE`：

- 确保存储根存在、可写、可读。
- 在 `tmp` 创建随机探针文件，执行写入、读取、原子移动和删除。
- 返回可用空间、总空间等非敏感摘要；探针文件异常残留可由 GC 清理。

探测开始将状态置为 `CHECKING`，结束写数据库，事务提交后更新内存快照。真实读写失败也可以立即报告组件 `ERROR`。

### 13.4 启动行为和日志

- 应用启动不以 DiceBear 或本地头像缓存健康作为 Spring Context 成功条件。
- `ApplicationReadyEvent` 后先以有界超时并行探测组件，再输出一次 `StartupSummaryLogger` 框线摘要。
- 日志只展示组件名和状态，不展示 seed、存储路径、带凭证 URL、异常堆栈全文。

示例：

```text
│ Avatar Gen  : ERROR (fallback active)                         │
│ Local Store : UP                                              │
```

运行期状态变化使用普通结构化日志记录，不重复输出完整启动摘要。

### 13.5 健康语义

- DiceBear `ERROR` 是可降级状态，不把应用整体 `/actuator/health` 变为 `DOWN`。
- 本地存储 `ERROR` 会让上传和新候选返回服务不可用，但资料读取仍返回 classpath 回退。
- 可以在 Actuator details 中增加组件详情；应用总体状态最多标记 `DEGRADED` 概念，容器存活检查仍保持成功。

## 14. GC、利用率和报告

### 14.1 GC 资源

接入现有 `GcResource`：

| 资源代码 | 内容 | 默认策略 |
| --- | --- | --- |
| `avatar-candidates` | 过期或已消费超过保留期的候选记录和对象 | 自动、1 天 |
| `avatar-storage-orphans` | 无用户/候选引用的头像对象、残留临时文件、待删除对象 | 自动、1 天安全窗口 |

上传或替换后的旧对象会立即尽力删除；GC 是崩溃和异常情况下的最终收敛，不是正常删除的唯一机制。

### 14.2 利用率报告

新增只读存储报告，不强行塞入现有只有 candidates/deleted 的 GC 执行结果：

```text
GET /api/v1/system/storage/report
权限：system:gc:read
```

报告包含：

- 总对象数、总字节数、文件系统总量/可用量。
- 按 namespace 的对象数和字节数。
- 当前被用户引用、被候选引用、孤儿、待删除、元数据存在但文件缺失的数量和字节数。
- 最近扫描时间、扫描耗时、扫描是否完整、异常摘要。
- 头像类型分布只返回聚合数量，不返回用户清单和对象键。

后台 GC 页增加“文件存储”概览卡和资源详情；真正删除仍通过已有 dry-run/执行流程。

## 15. 安全、审计和限流

### 15.1 权限

- 本人上传、恢复、创建候选和确认候选：只需有效登录。
- 管理员查看头像类型：`system:user:read`。
- 管理员重置：`system:user:write`。
- 查看运行时状态：`system:config:read`；刷新/重试：`system:config:write`。
- 存储报告和 GC：沿用 `system:gc:read/write`。

### 15.2 审计事件

新增：

- `USER_AVATAR_UPLOADED`
- `USER_AVATAR_RESET`
- `USER_AVATAR_RANDOMIZED`
- `ADMIN_USER_AVATAR_RESET`
- `RUNTIME_COMPONENT_REFRESHED`
- `RUNTIME_COMPONENT_REFRESH_FAILED`

审计只记录 actor、目标用户内部 ID、头像类型、结果和 traceId；不记录 seed、图片字节、原文件名、对象键或完整组件异常。

### 15.3 限流

- 公开头像读取沿用全局限流，并允许浏览器长期缓存。
- 上传按用户增加更严格端点限流。
- 随机候选建议限制为每用户每小时 30 次，确认接口限制为每分钟 10 次。
- 候选公开标识使用高熵随机值并短期失效，避免枚举。

## 16. 错误语义

建议新增稳定错误码：

| 错误码 | HTTP | 场景 |
| --- | --- | --- |
| `AVATAR_FILE_TOO_LARGE` | 413 | 超过 5 MiB |
| `AVATAR_FORMAT_UNSUPPORTED` | 415 | 不是允许的静态 JPEG/PNG/WebP |
| `AVATAR_IMAGE_INVALID` | 400 | 损坏、尺寸异常、动画或无法解码 |
| `AVATAR_PROCESSING_FAILED` | 422 | 合法输入但裁剪/编码失败 |
| `AVATAR_CANDIDATE_EXPIRED` | 410 | 候选已过期 |
| `AVATAR_CANDIDATE_INVALID` | 404 | 候选不存在或不属于当前用户 |
| `AVATAR_GENERATOR_UNAVAILABLE` | 503 | 创建随机候选时生成器不可用 |
| `STORAGE_UNAVAILABLE` | 503 | 上传/候选写入时存储不可用 |

公开头像读取本身不因这些错误返回 JSON；只要公开标识存在，就尽量返回当前对象或最终回退。不存在的公开标识返回 404。

## 17. `springboot-admin` 同步设计

### 17.1 全局当前用户

- `CurrentUser` 增加 `avatarUrl`。
- `loadProfile()` 每次以服务端 `avatarUrl` 覆盖 Pinia Store，不能沿用本地旧头像。
- 登出时清空 avatar，防止切换账号后串图。
- 现有导航栏 `<img>` 和本地静态回退继续保留，网络加载失败时显示本地占位。

### 17.2 本人头像弹窗

在导航栏头像菜单增加“更换头像”，打开统一弹窗：

- “系统头像”页签：展示当前头像、创建随机候选、“换一张”、“使用此头像”、“恢复初始头像”。
- “上传头像”页签：接受 JPEG/PNG/WebP，前端只做 5 MiB 和扩展名快速提示，不承担安全校验和第一版裁剪。
- 保存成功后刷新当前用户 Store；弹窗不接触 seed、对象 ID 和头像类型。

### 17.3 管理员用户管理

- 用户列表第一列新增 40px 圆形头像，再显示用户名、显示名、状态和角色。
- 详情抽屉顶部显示 80px 头像；管理员可见 `avatarType` 的中文标签。
- 拥有 `system:user:write` 时显示“重置为初始头像”，二次确认后调用管理员重置接口。
- 管理员页面不提供代上传、随机候选和 seed 查看。

### 17.4 运行时管理

现有运行时配置页增加两个区域：

- “自动刷新配置”：组件探测总开关和刷新秒数。
- “配置缓存”：运行时设置、IP 访问规则的 revision、同步状态、条目数和手动加载入口。
- “服务依赖”：DiceBear、本地存储状态卡，展示状态、最近检查、最近成功、耗时、连续失败和脱敏说明。

页面提供“刷新全部”和每张卡片的“重新尝试”。`ERROR` 使用红色，`CHECKING` 使用加载态，`DISABLED` 使用灰色；失败不弹出不可恢复的全页错误。

### 17.5 GC 页面

- 现有资源策略自动显示两个新增头像 GC 资源。
- 概览增加文件利用率、孤儿数量、缺失文件和磁盘可用空间。
- 报告只展示聚合结果，不能展示内部对象键和用户 ID。

## 18. 配置和部署

`.env.example` 新增：

```properties
# 自托管 DiceBear；本机直接运行后端时默认使用 3100。
APP_AVATAR_GENERATOR_BASE_URL=http://localhost:3100

# 通用本地存储根目录；默认位于 APP_DATA_DIR/storage。
APP_STORAGE_LOCAL_ROOT=./data/storage
```

Compose：

- 为 Java 应用挂载 `app-data:/app/data`。
- 如果 DiceBear 由同一 Compose 管理，Java 使用服务名和容器端口；如果由宿主机或外部编排管理，只通过环境变量提供可达地址。
- DiceBear 不需要暴露公网端口；生产网络策略只允许 Java 应用访问。
- 不把头像数据目录打进镜像，也不把 `.env`、数据库或图片提交到 Git。

备份恢复必须同时包含业务数据库和本地存储卷。只恢复其中之一会由存储报告显示缺失对象或孤儿。

## 19. 测试与验收

### 19.1 后端测试

- SQLite/PostgreSQL Liquibase 结构和既有用户回填。
- `avatarPublicId` 唯一且公开 URL 不含内部 ID、seed、对象 ID。
- JPEG/PNG/WebP 正常处理；伪扩展名、损坏文件、动画、超限尺寸、解压炸弹和 SVG 被拒绝。
- EXIF 旋转、中心裁剪、透明通道和 256×256 PNG 输出。
- 上传、恢复、随机候选、过期、越权确认、管理员重置和并发覆盖。
- 文件准备/事务提交/回滚/进程残留各路径的一致性和 GC 收敛。
- DiceBear 正常、超时、错误状态、错误类型、超大响应、状态门控、恢复刷新。
- 本地存储原子写、路径穿越、符号链接、磁盘错误、对象缺失。
- 公开读取不包 JSON，并具有缓存和安全响应头。
- 运行时设置提交后快照、全量 reload、组件刷新、启动探测和日志脱敏。
- GC dry-run、真实清理、利用率报告及资源根边界。

### 19.2 前端测试

- API 类型和头像弹窗契约脚本。
- Store 用服务端头像覆盖本地值并在登出时清理。
- 用户列表头像首列、管理员重置权限、配置缓存/服务依赖独立刷新和 GC 报告。
- `pnpm typecheck`、`pnpm build` 和现有全部 Node 契约测试。

### 19.3 运行验收

1. DiceBear 和存储正常：注册用户首次读取生成并缓存不同 Lorelei。
2. 随机候选反复预览不改变当前头像；确认后 URL revision 变化。
3. JPEG/PNG/WebP 上传后都返回 256×256 PNG。
4. 停止 3100：已有头像继续读取；新系统头像返回内置回退；上传仍可用。
5. 手动重试 DiceBear 成功后状态从 `ERROR` 变为 `UP`，新生成恢复。
6. 破坏本地存储：上传返回明确 503，但注册、登录和头像读取仍不出现 500。
7. 后台用户、运行时配置和 GC 页面均显示正确状态和聚合数据。
8. 重建应用容器后数据卷头像仍存在。

## 20. 实施顺序

1. 数据库和通用存储模块。
2. 服务依赖状态、探测和启动摘要；配置缓存 revision 独立管理。
3. DiceBear 生成、内置回退和公开读取。
4. 上传处理、随机候选、用户/管理员操作与审计。
5. GC 资源和存储利用率报告。
6. `springboot-admin` 当前用户、头像弹窗、用户管理、配置缓存、服务依赖和 GC 页面。
7. 正式 API/运维文档、Compose、回归和宿主机故障演练。

## 21. 最终不变量

- 前端永远只依赖后端 URL 和操作接口。
- 公开头像 URL 永远不携带内部用户 ID、seed、对象 ID 或文件路径。
- 用户上传原图永远不直接落盘为最终对象。
- DiceBear、存储或缓存故障永远不阻断认证和用户资料读取。
- 可编辑配置与探测状态永远分开，业务流量只读取最新内存状态快照。
- 所有文件最终都必须能被数据库引用、候选引用或 GC 安全识别。
- 第一版后端裁剪只是策略实现，不成为未来 C 端裁剪的架构障碍。
