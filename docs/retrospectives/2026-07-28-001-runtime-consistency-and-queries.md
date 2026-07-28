# 运行时一致性与查询修复复盘

日期：2026-07-28

交付方式：随对应提交交付

计划提交说明：`fix: harden runtime consistency and admin queries`

## 一、背景与目标

`springboot-start` 是尚未投产的 Spring Boot 启动模板。本次从测试隔离、生产
安全、事务边界、限流语义和数据库查询五个方向处理反向审计中确认的真实缺陷，
不为不存在的旧版本增加兼容层，也不默认引入 Redis、消息队列或分布式事务。

完成标准是：测试不再修改开发数据库；PostgreSQL 不能带危险默认启动；内存快照
只反映已提交数据；GC 失败可追踪且业务写入回滚；全局限流不能通过更换路径绕过；
管理员组合筛选与角色/权限加载正确且没有逐条查询。

## 二、范围

### 纳入

- Local/PostgreSQL 配置、Compose 环境变量和生产安全启动校验；
- Actuator、SQL Console、CORS 与 Refresh Cookie 的安全边界；
- 运行时设置、IP 规则和 GC 的事务一致性；
- 全局与端点两层限流；
- 管理员 `q + status` 查询、用户角色及 RBAC 权限批量加载；
- 与上述行为对应的单元测试和 SQLite 集成测试。

### 排除

- 用户原有的 `api-test.html` 修改；
- 构建质量门、Dependabot、Wrapper 指纹和 Tag 工作流，交由下一提交；
- README、AGENT 和运维文档，交由独立文档提交；
- 本地 Docker 镜像构建和版本 Tag 发布。

## 三、实施内容

### 1. 测试数据库隔离

原有多个 `@SpringBootTest` 会使用默认 local 数据源，使 `mvn verify` 迁移并写入
`data/app.db`。现在上下文测试分别使用 `target/*-test.db`，迁移集成测试使用
JUnit `@TempDir`。构建目录由 Maven 管理，测试不再触碰开发者数据库。

最终复核的本地数据库基线保持不变：

- mtime：`1785200498`；
- 大小：`835584`；
- SHA-256：`c8183cc4469123dc597d597a17915ad413167cdd42e9d152babacbd068330917`。

### 2. PostgreSQL 生产安全默认

新增 `ProductionSafetyValidator`，在 `postgres` Profile 下拒绝以下配置：

- Refresh Cookie 未启用 Secure；
- 携带凭据的 CORS 使用通配来源 `*`。

`application-postgres.yml` 默认启用 Secure Cookie；初始 CORS 来源支持环境变量且
不再默认为通配；数据库已有运行时设置不会被环境变量强行覆盖。SQL Console 默认
关闭，只能在 local Profile 中显式启用。健康检查继续匿名开放，其它
`/actuator/**` 需要 `system:config:read`。

### 3. `compose.yaml` 的职责

Compose 文件描述本地 PostgreSQL 组合环境，而不是 Spring 配置或生产编排平台：

- `postgres` 服务定义数据库镜像、账号、健康检查、重启策略和持久化卷；
- `app` 服务从 Dockerfile 构建应用，接收数据库、JWT、Cookie、CORS 和可信代理
  等环境变量并暴露端口；
- `depends_on: condition: service_healthy` 要求数据库健康后再启动应用；
- `postgres-data` 使容器重建后数据库数据仍然保留；
- `profiles: ["postgres"]` 避免默认命令意外启动完整应用栈。

`docker compose config --quiet` 只验证静态展开结果，不会构建或运行镜像。

### 4. 快照与多项设置的提交一致性

运行时设置原先在数据库事务提交前更新进程内快照，后续审计或提交失败会形成
“数据库已回滚、进程仍使用新值”的分裂状态。现在单项设置在事务 `afterCommit`
后更新；新增 `updateAll(Map)`，先完整校验再在同一事务写入多项设置。GC schedule
的开关与间隔因此具备原子性。IP 规则创建和删除同样只在提交成功后刷新快照。

### 5. GC 失败状态与业务回滚

原 GC 服务把运行记录、资源清理和异常抛出放在同一事务中，失败时 `FAILED` 记录
也会回滚。现在编排层与 `GcRunWorker` 分离：

1. 新事务创建 `RUNNING`；
2. 独立业务事务执行资源清理；
3. 成功时完成运行记录；
4. 失败时回滚资源写入；
5. 新事务保存 `FAILED`；
6. 再用独立事务写失败审计，避免审计异常反向抹掉失败状态。

SQLite 集成测试在资源事务写入后主动抛错，确认资源写入回滚，而 FAILED 运行记录
和 `GC_RUN_FAILED` 审计均保留。

### 6. 全局与端点双层限流

旧“全局”key 包含路径，客户端可轮换路径分散计数。现在每次 API 请求先进入
`global:<client-ip>`；若端点另有策略，再进入
`endpoint:<client-ip>:<request-path>`。端点桶是更严格的第二层，而不是全局桶
的替代品。

### 7. 管理员组合查询与 N+1

管理员分页旧逻辑在传入 `status` 时忽略 `q`。现在一个 JPQL 条件同时应用规范化
关键词和状态，分页内容与 total 来自同一条件。

用户角色采用“先分页用户、再按当页 ID 一次加载角色、按原顺序组装 DTO”，避免
在分页 collection fetch join 导致结果失真，也消除了逐用户懒加载。角色目录和
权限删除路径同样一次加载权限集合。真实 SQLite 测试覆盖相同关键词、不同状态
用户，确认组合筛选和角色返回正确。

## 四、验证证据

| 验证 | 结果 | 备注 |
| --- | --- | --- |
| P0 定向上下文测试 | 17/17 通过 | 配置、安全、事务、限流与测试隔离 |
| GC 定向单元/集成测试 | 8/8 通过 | FAILED 状态、审计持久化、资源回滚 |
| 用户/RBAC 定向测试 | 4/4 通过 | 组合筛选与批量权限加载 |
| SQLite migration/integration | 7/7 通过 | 含 GC 事务和管理员组合筛选 |
| 最终 `mvn verify` | 通过 | 108 tests、0 failures、0 errors、1 skipped |
| Compose 静态解析 | 通过 | `docker compose config --quiet` |
| 差异检查 | 通过 | `git diff --check` |
| 本地开发数据库 | 未变化 | mtime、大小与 SHA-256 均保持基线 |

## 五、问题与过程复盘

- SQLite JDBC 不会创建 `target/test-data/` 父目录，测试库改放 Maven 已创建的
  `target/` 后稳定运行；
- Spring Boot 4 当前测试依赖不提供旧包名 `AutoConfigureMockMvc`，改为复用
  `WebApplicationContext + MockMvcBuilders + springSecurity()`；
- 管理员测试最初错误假设 `PageImpl` 会保留与内容矛盾的 total，改为断言服务
  透传 Page 自身元数据；
- 跨仓库补丁出现过路径拼写和上下文错误，工具均在应用前整体拒绝；拆小补丁并
  复核差异后继续，没有留下半套实现。

## 六、风险与搁置项

- PostgreSQL Testcontainers 因本机镜像源拉取 Ryuk 超时而未在本地完成，等待
  GitHub CI；
- 多实例限流和调度仍需要 Redis/分布式协调，但不属于通用单体模板默认能力；
- 本次不发布 Tag，也不执行本地 Docker 镜像构建。

## 七、主要文件

| 范围 | 文件 |
| --- | --- |
| 生产安全 | `SecurityConfiguration.java`、`ProductionSafetyValidator.java`、`application*.yml`、`.env.example`、`compose.yaml` |
| 事务一致性 | `RuntimeSettingService.java`、`IpAccessRuleService.java`、`GcExecutionService.java`、`GcRunWorker.java` |
| 限流 | `RateLimitFilter.java`、`RateLimitFilterTest.java` |
| 查询 | `UserRepository.java`、`UserAdministrationService.java`、`RoleRepository.java`、`RbacService.java` |
| 回归测试 | `StarterApplicationTest.java`、`ProductionSafetyValidatorTest.java`、`SqliteMigrationTest.java` 等 |

## 八、结论与后续

本提交把可复现的运行时正确性缺陷收敛到测试保护下。后续提交只处理构建/发布
工程化和文档制度，不再改变这里的业务语义。
