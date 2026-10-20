# 鱼搭 1.2 完整账号控制接入复盘

日期：2026-10-20

交付方式：工作区交付，待完整 verify 在允许监听端口的环境通过后提交；本次未提交、未推送

计划提交说明：`feat(closet): 接入鱼搭 1.2 完整账号控制与注册邀请`

## 一、背景与目标

鱼搭中立管理接口已升级至 1.2，现有管理代理缺少用户资料、单会话退出、登录锁定、注册与邀请和账号审计能力。本次按代理契约补齐 13 个操作，保持 JWT/RBAC、HMAC、默认关闭、分页转换和脱敏审计，验证后提交到 feat/closet-accounts，不推送。

## 二、范围

### 纳入

- 本仓库 integrations/closet 内的控制器、DTO、应用服务、错误转换、权限与菜单种子。
- 原有上游 MockRestServiceServer 集成测试、关闭开关的实际路由测试。
- API 参考、集成说明、1.2 代理契约、上游 OpenAPI 快照、README 与交付状态。

### 排除

- 开始时工作区干净，没有用户已有修改需要排除。
- 不修改 smart-closet-server、smart-closet、smart-closet-admin 或 GitHub 模板仓库。
- 不实现前端页面，不启动应用镜像，不发布、不推送；测试不访问 data/app.db。

## 三、实施内容

### 1. 用户账号

- 列表增加 registeredVia/deactivated/lastLoginAt，创建增加 displayName，原样保留上游新增字段。
- 新增资料修改、单会话撤销、登录锁定查看/解除、最近账号事件；方法级权限与 DTO/路径校验在转发前执行。
- 用户列表拒绝未知及重复参数，防止筛选意外被忽略；分页避免 page+1 整数溢出。

### 2. 注册邀请与账号审计

- 新建 ClosetRegistrationController/Service，注册模式支持 null/缺省恢复默认；邀请码创建、详情、部分更新、撤销。
- 邀请码 note/使用次数/有效期在本地校验；已用次数约束由上游负责，PATCH 仅转发非 null 字段。
- 复用现有 ManagementService 的白名单分页转换，接入邀请码列表与全站账号事件；账号事件日期、动作、用户名、actor 校验。
- 写操作成功/失败均通过 AuditService 记录，邀请码创建成功仅保存 id，不保存 code、备注或上游 detail；用户操作不保存昵称/密码/临时密码。
- 补齐 SESSION_NOT_FOUND/INVITE_NOT_FOUND 到 404 NOT_FOUND、INVITE_REVOKED 到 409 CONFLICT。

### 3. 权限、菜单与文档

- 新增 closet:registration:read/write 并授予 SUPER_ADMIN，鱼搭目录下新增注册与邀请（25）、账号审计（27）菜单，组件键/图标/权限遵循契约。
- 同步 39 个操作的路由权限表与 1.2 代理说明；复制上游 OpenAPI 到本仓库快照，未修改上游文件。

## 四、验证证据

| 验证 | 结果 | 备注 |
| --- | --- | --- |
| 离线编译 | 通过 | ./mvnw -o -q -DskipTests compile |
| 定向测试 | 通过，84 个，0 失败/错误/跳过 | ./mvnw -o -Dtest=ClosetIntegrationApiTest,ClosetDisabledTest test；API 70 个、关闭开关 14 个 |
| 完整 verify | 未通过：232 项，226 通过，0 断言失败，5 环境错误，1 跳过 | MAVEN_ARGS=-o、JAVA_TOOL_OPTIONS 预加载 Mockito agent 后运行 ./mvnw verify；详见下文 |
| 打包与覆盖率门禁单独检查 | 通过 | 使用上述测试执行数据运行 ./mvnw -DskipTests verify；行 77.26%、分支 54.98%，超过 70%/45% 门槛；不等同于完整 verify 通过 |
| 静态配置检查 | 通过 | docker compose config --quiet，未构建或启动镜像 |
| 差异检查 | 通过 | git diff --check；所有改动/新增文件另检查无行末空白 |
| 契约快照 | 通过 | 与上游逐字节相同，39 个操作，SHA-256 db29509d9ecfa6b712fddddff9d155bbbcf7df05fa737f798810ac883b420352 |

## 五、问题与过程复盘

- 首次常规 Maven 编译试图更新 ~/.m2 的 resolver-status.properties，被当前只读缓存权限阻止；已有依赖齐全，改用离线解析通过编译，无需修改缓存或下载依赖。
- 首次完整 verify 中，既有 Mockito 单元测试因沙箱阻止动态 self-attach 而失败；后续通过 JAVA_TOOL_OPTIONS 在 JVM 启动时加载现有 mockito-core 5.23.0 agent，并用 MAVEN_ARGS=-o 保持离线解析，不修改 POM、依赖版本或生产配置。
- 扩大 API 参数化测试后，累计请求触发原有每 IP 全局限流，掩盖了权限与校验断言。为每个测试用例分配独立测试客户端 IP，继续保留真实安全过滤器与生产限流配置。
- 上游 Mock 不允许实际请求发出后再追加期望；拆开列表与详情的 Mock 生命周期，并明确查询参数顺序。
- 默认关闭的检查由 Bean 条件断言扩展为带权限的真实 MockMvc 请求，验证所有 13 个新路由返回 404。
- 本地开发库仍由任务开始前已有的 Java 进程（PID 8189）持有并自行更新，因此未用文件修改时间断言开发库静止；通过测试连接日志与测试 JVM 的 lsof 文件句柄确认本次定向测试使用 target/closet-integration-api-test.db 和 target/closet-disabled-test.db，未操作已有服务或开发库。

## 六、风险与搁置项

- 上游使用 MockRestServiceServer 验证，未启动真实鱼搭服务或前端联调；本次未修改这些仓库。
- 完整 verify 的 5 个错误均为 java.net.SocketException: Operation not permitted，发生在未改动的既有测试尝试绑定 127.0.0.1:0 时：DiceBearAvatarGeneratorTest 的 3 项，以及 ContainerHealthCheckTest 的 acceptsSuccessfulReadinessResponse、rejectsUnhealthyReadinessResponse。没有修改这些测试或把它们静默跳过。
- PostgreSQL PostgresMigrationTest 的 1 项按现有 @EnabledIfEnvironmentVariable(CI=true) 条件跳过；本次没有执行 PostgreSQL Testcontainers 验证。
- 由于用户要求全部通过后再提交，完整 verify 被环境限制阻断时保留工作区，不生成提交。计划提交说明见文首。
- 与代理契约无功能偏差；邀请码 maxUses 不低于已用次数等有状态规则由上游最终校验。

## 七、文件清单

| 文件 |
| --- |
| `README.md` |
| `docs/API_REFERENCE.md` |
| `docs/PROJECT_STATUS.md` |
| `docs/api/closet-integration.md` |
| `docs/integrations/closet/accounts-1.2.md` |
| `docs/integrations/closet/management-api.yaml` |
| `docs/retrospectives/2026-10-20-001-closet-accounts.md` |
| `docs/retrospectives/README.md` |
| `src/main/java/com/yumg/starter/modules/integrations/closet/api/ClosetManagementController.java` |
| `src/main/java/com/yumg/starter/modules/integrations/closet/api/ClosetRegistrationController.java` |
| `src/main/java/com/yumg/starter/modules/integrations/closet/api/ClosetUserAdministrationController.java` |
| `src/main/java/com/yumg/starter/modules/integrations/closet/api/dto/ClosetCreateInviteRequest.java` |
| `src/main/java/com/yumg/starter/modules/integrations/closet/api/dto/ClosetCreateUserRequest.java` |
| `src/main/java/com/yumg/starter/modules/integrations/closet/api/dto/ClosetProfileRequest.java` |
| `src/main/java/com/yumg/starter/modules/integrations/closet/api/dto/ClosetRegistrationRequest.java` |
| `src/main/java/com/yumg/starter/modules/integrations/closet/api/dto/ClosetUpdateInviteRequest.java` |
| `src/main/java/com/yumg/starter/modules/integrations/closet/application/ClosetManagementService.java` |
| `src/main/java/com/yumg/starter/modules/integrations/closet/application/ClosetNavigationBootstrap.java` |
| `src/main/java/com/yumg/starter/modules/integrations/closet/application/ClosetPermissionBootstrap.java` |
| `src/main/java/com/yumg/starter/modules/integrations/closet/application/ClosetRegistrationService.java` |
| `src/main/java/com/yumg/starter/modules/integrations/closet/application/ClosetUserAdministrationService.java` |
| `src/main/java/com/yumg/starter/modules/integrations/closet/infrastructure/ClosetProblemTranslator.java` |
| `src/test/java/com/yumg/starter/modules/integrations/closet/ClosetDisabledTest.java` |
| `src/test/java/com/yumg/starter/modules/integrations/closet/ClosetIntegrationApiTest.java` |

## 八、结论与后续

实现、契约文档与定向验证已完成；完整 verify 尚未通过，因此本次没有提交。需在允许本地 TCP 监听的环境按原测试集重跑 verify，通过后按文首说明提交；不创建 Tag、不推送、不发布。前端页面及真实上游联调另行开展。

验证日志位于 /private/tmp/closet-accounts-tests-fixed.log（定向 84 项通过）、/private/tmp/closet-accounts-verify-agent.log（完整 232 项与 5 个环境错误）、/private/tmp/closet-accounts-coverage.log（单独打包与覆盖率检查通过）。
