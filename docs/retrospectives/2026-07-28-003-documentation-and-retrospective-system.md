# 工程文档与复盘制度复盘

日期：2026-07-28

交付方式：随对应提交交付

计划提交说明：`docs: document starter hardening workflow`

## 一、背景与目标

代码和构建能力已经变化，但原 README 无法完整回答模板定位、启动方式、模块边界、
生产安全和发布流程，仓库也缺少给后续维护者使用的工程约束。目标是让使用者只读
仓库文档即可正确启动、裁剪、验证和发布模板，并建立“一提交一复盘”的长期记录。

## 二、范围

### 纳入

- README 的模板定位、快速开始、架构、Profile、验证与发布；
- `AGENT.md` 的代码边界、不变量、验证和提交规则；
- API、项目状态、部署与运行时配置说明；
- `docs/retrospectives/` 的命名、模板、索引和前三篇复盘。

### 排除

- 运行时代码和构建配置；
- 用户原有的 `api-test.html`；
- 创建正式版本 Tag 或本地构建 Docker 镜像。

## 三、实施内容

### 1. README 作为模板入口

README 现在说明：

- 模板适用于可独立部署的模块化单体，不默认承诺分布式平台能力；
- local SQLite 与 PostgreSQL Profile 的启动、数据和安全差异；
- Controller、Application Service、Repository 与 Infrastructure 的分层数据流；
- 认证、RBAC、运行时配置、维护中心等模块边界；
- 测试数据库隔离、Maven 质量门和正式版本构建；
- 从模板创建新项目时需要修改或裁剪的内容；
- 语义化 Tag 的 JAR/GHCR 发布方式和复盘索引。

### 2. `AGENT.md` 的维护约束

`AGENT.md` 以当前代码为事实源记录：

- 包结构、配置归属、事务边界和安全不变量；
- 快照只能在事务提交后更新，批量设置必须保持原子性；
- 分页集合不能用会破坏分页语义的 fetch join；
- 测试数据库只能位于 `target` 或临时目录；
- Dependabot、Maven Wrapper 和 Compose 各自解决什么、不解决什么；
- 常规情况下不在本地构建应用 Docker 镜像；
- 只有明确授权的 `vMAJOR.MINOR.PATCH` Tag 才触发正式发布；
- 推送后使用 `gh run list/watch/view` 观察工作流；
- 每个提交必须在 `docs/retrospectives/` 携带一篇对应复盘。

### 3. API 与运维说明同步

API 手册明确 SQL Console 的 local-only 显式开关和 Actuator 权限；部署说明同步
PostgreSQL Secure Cookie、CORS、Compose 与 Tag 发布；运行时配置说明区分环境
变量初始化值和数据库中的动态值；项目状态文档记录当前已完成的模板能力与有意
不默认加入的规模化能力。

### 4. 一提交一复盘制度

目录使用 `YYYY-MM-DD-NNN-<slug>.md`。复盘至少记录背景、范围、实现、行为变化、
真实验证、过程问题、风险、主要文件和后续动作。未执行的验证必须明确写“未验证”，
不得写入密码、Token、私钥或生产数据。

复盘与对应代码进入同一个提交，一篇文档不能持续追加给多个提交。本轮的大工作区
因此被拆成运行时、构建发布和文档制度三批，每批各自有独立复盘。

## 四、验证证据

| 验证 | 结果 | 备注 |
| --- | --- | --- |
| Markdown 本地链接检查 | 通过 | README、AGENT、API/运维和复盘索引 |
| 文档与配置变量核对 | 通过 | Cookie、CORS、SQL Console、Tag 版本 |
| YAML/Compose 静态检查 | 通过 | 文档中的行为与配置一致 |
| `git diff --check` | 通过 | 无空白错误 |
| 完整 Maven 验证 | 通过 | 108 tests、0 failures、0 errors、1 skipped |

## 五、问题与过程复盘

- 最初用一篇综合复盘覆盖所有本地变更，但用户要求分批提交后，这会违反
  “一提交一复盘”；提交前拆为三篇独立文档，没有产生历史补丁提交；
- 复盘状态若在提交后从“待提交”改为“已提交”会制造递归文档提交，因此改用
  “随对应提交交付”的稳定元信息；
- Docker 相关文档明确区分 Compose 静态解析、应用镜像构建与正式 Tag 发布，避免
  把“配置可解析”写成“镜像已经验证”。

## 六、风险与搁置项

- 文档需要随代码持续更新，AGENT 规则只能帮助审查，不能替代维护者判断；
- 本机没有 `actionlint`，Tag 工作流的最终语义仍需首次远端发布验证；
- 本次只观察 `main` 的普通 CI，不会触发 JAR/GHCR 正式发布。

## 七、主要文件

| 范围 | 文件 |
| --- | --- |
| 项目入口 | `README.md` |
| 维护约束 | `AGENT.md` |
| API/状态 | `docs/API_REFERENCE.md`、`docs/PROJECT_STATUS.md` |
| 运维 | `docs/operations/deployment.md`、`docs/operations/runtime-settings.md` |
| 复盘制度 | `docs/retrospectives/README.md`、`_template.md`、本轮三篇复盘 |

## 八、结论与后续

文档已经覆盖启动模板的使用、架构、维护和发布边界。提交并推送后观察普通 CI；
正式版本号与 Tag 仍由用户另行决定。
