# 2026-10-10 鱼搭管理与大屏完整接入验收

## 结果与范围

鱼搭当前 feat/image-search 已包含 management-ops-kpi、全身穿搭、模型内存和图像检索相关更新。以当前 docs/management-api.yaml 和 Java Controller 为准，共 26 个 HTTP 操作全部接入 smart-closet-admin-server；前端为 smart-closet-admin。鱼搭业务源码无改动，原有归档文件保留，未提交或推送。

保留已有 16 个账号/洞察操作，新增 10 个操作：推荐 KPI、AI 用量、运维状态、任务、操作日志、大屏快照、按天宽表、增量事件、模板列表和审核。CSV 为按天出口的附加格式。默认仍关闭、使用服务端 HMAC，权限与审计由管理平台负责。内部会话型原图审核、处理器改配置与取消任务不属于中立契约，遵守现有只读运维和媒体边界。

## 主要文件

- 后端 integrations/closet/api/ClosetManagementController.java、application/ClosetManagementService.java：路径/参数白名单，零基分页与原始 CSV，部分模板写入和审计。
- ClosetInsightService、ClosetManagementClient、ClosetProblemTranslator：KPI 白名单、签名 CSV 字节下载、模板 404 映射。
- ClosetPermissionBootstrap、ClosetNavigationBootstrap、NavigationService：十项权限，六个菜单组件键，支持菜单编辑器。
- 前端 src/api/closet/management.ts、insights.ts：完整接口类型与 15 个趋势指标、月粒度。
- 新增 dashboard/ops/exports/templates 页面与 insights/KpiPanel.vue，复用 Element Plus 与 ECharts。
- 前端 routes/navigation/router 映射、菜单编辑器与中文权限/组件/审核审计说明同步。

## 验证

| 检查 | 结果 |
| --- | --- |
| 管理后端 ./mvnw -q verify | 169 tests，0 failures，0 errors，1 skipped（PostgresMigrationTest 因未设置 CI 环境变量跳过）。JaCoCo 生命周期门禁通过。 |
| JaCoCo | LINE 76.52%，BRANCH 52.95%，高于项目 70%/45% 门槛。 |
| 鱼搭管理专项测试 | 30 tests，0 failures，0 errors。命令：CLOSET_AGENT_RUNTIME_URL=http://127.0.0.1:9 mvn -q -Dtest='Management*Test,HmacCanonicalTest' test。 |
| 前端 pnpm typecheck | 通过。 |
| 前端 ESLint | 本次涉及 API/路由/页面通过；中文说明 mjs 保留原有格式，以关闭 prettier 规则的 ESLint 单独检查通过。 |
| 前端 node --test scripts/*.test.mjs | 13 组通过，覆盖全部 26 个操作加 CSV 及六个组件键。 |
| 前端 pnpm build | 通过，最终构建约 6 秒，产物约 3.1MB。 |
| 两个目标仓库 git diff --check | 通过。 |

## 真实联调

使用 /tmp/closet-phase2-e2e 独立目录、独立 H2/SQLite 数据；鱼搭 18082、管理后端 18083、Vite 18849。测试密钥与账号只用于隔离数据。未触碰 8080 正式进程或其数据库。

- 管理平台正常登录，动态菜单返回六个 fish closet 组件键。
- 新增接口通过真实 HMAC 到鱼搭；重复 status/types 参数传递正确。
- JSON 出口符合 data 信封；CSV 返回 text/csv;charset=UTF-8，无 JSON 信封。
- 创建一个测试业务账号，事件出口返回 16 位 userKey，不含用户名。
- 模板 PATCH、GET 回读与操作日志匹配，reviewedBy 为 manage:primary:phase2admin；后端自身也记录 CLOSET_TEMPLATE_REVIEW。
- 浏览器实际查看业务大屏、衣柜洞察、运行健康、任务/日志 tab、宽表及事件、模板审核。
- 390px 手机宽度查看模板列表/弹窗/导出；手机端保存 bonus=2.5，服务端 PATCH 200，API 再读验证 2.5。
- 浏览器点击下载 CSV 成功，下载文件验证 30 天数据行、UTF-8 表头且无 JSON 信封。
- 截图在相邻前端 output/playwright，验证缓存与截图已按路径忽略，不进入代码提交。

## 验证环境问题与处理

- 初次鱼搭管理测试中本机 Agent 可达，与预期不可达的测试环境冲突；用上述环境变量隔离后 30/30 通过。
- 本项目没有 mvnw，专项测试使用已安装的 mvn。
- 新隔离 SQLite 目录需先创建。已有数据库的 CORS 来源只在首次初始化时读取 env，通过隔离数据库的 runtime-config API 配置测试来源后浏览器登录正常。
- Python urllib 必须绕过本机代理，并用 Request 的 method 关键字，位置参数会误发 POST，已修正验证脚本。
- 构建覆盖运行中的 JAR 曾使临时进程懒加载类失败；最终使用固定复制的 JAR 重新启动，浏览器保存成功。
- 单独调用 jacoco:check 因 default-cli 没有 rules 配置失败；正式 verify 的 coverage-check execution 已通过。

## 正式启用

详见 [接入配置](../api/closet-integration.md)。正式实例尚未切换或重启；默认开关保持关闭。开启鱼搭管理接口和管理后端集成，并配置一致的 key ID/32 字节以上共享密钥后，启动管理后台即可出现完整菜单。临时联调进程验证结束后关闭。
