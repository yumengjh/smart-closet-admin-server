# P2 官方素材管理收口验证

- 日期：2026-11-15
- 后端仓库：`/Users/yaopinxin/project/art-showcase-admin-server`，分支 `feat/art-showcase-management`
- 前端仓库：`/Users/yaopinxin/project/art-showcase-admin`，分支 `feat/art-showcase-management`

## 固定契约来源

源仓 `/Users/yaopinxin/project/art-showcase-server` 的 owner 确认本次固定提交为 `63bff12d855b6151460d5c0195f0bc1fdd939548`。该提交的 `docs/management-api.yaml` SHA-256 为 `66e99b68e27cf40235a63681c6b12eb85aacd2ea4848d49cbe45bf6391b57332`。同步使用仓库脚本完成，没有手工编辑快照；后端快照头和正文对应此提交及哈希。

| 检查 | 结果 |
|---|---|
| `scripts/sync-closet-contract.sh /Users/yaopinxin/project/art-showcase-server` | 通过 |
| `scripts/sync-closet-contract.sh --check /Users/yaopinxin/project/art-showcase-server` | 通过，快照与源契约一致 |
| `./mvnw '-Dtest=ClosetContractSnapshotTest' '-DCLOSET_CONTRACT_SOURCE=/Users/yaopinxin/project/art-showcase-server/docs/management-api.yaml' test` | 3 项通过 |
| `./mvnw '-Dtest=ClosetIntegrationApiTest#everyUpstreamContractOperationHasAnActualGatewayMapping' test` | 1 项通过；此前因快照缺少 7 个素材路由而失败的映射断言已恢复 |

相邻的 `/Users/yaopinxin/project/smart-closet-server` 当前是另一分支，契约哈希不同且不含这些素材路由；本次没有把它用作来源，也没有修改它。源仓仍位于固定提交，验证后工作区干净。

## 后端 MockMvc 与真实 HTTP

| 验证层 | 命令/场景 | 结果与边界 |
|---|---|---|
| 网关 MockMvc | `./mvnw '-Dtest=ClosetIntegrationApiTest#showcase*' test` | 11 项通过。覆盖普通及带引号 boundary、有效 PNG、12 MiB `.bin` 不透明传输体、原始 multipart bytes/HMAC/X-Operator、分页边界和 multipart resolver 精确路由。 |
| 网关 MockMvc + 真实源 HTTP | `./mvnw '-Dtest=ClosetShowcaseLiveContractTest' '-Dshowcase.live.test=true' '-Dshowcase.fixture=/Users/yaopinxin/project/art-showcase-server/target/p2-showcase-fixture.png' test` | 1 项通过。网关测试客户端使用 MockMvc；被测网关到源服务的请求经 `127.0.0.1:18764` 真实 HTTP，并由源服务校验 HMAC。 |
| 真实 HTTP 状态回读 | 同上，Surefire XML 中记录的路径状态 | 7 × 200、1 × 403、2 × 409。依次覆盖上传、草稿创建、重复上传冲突、列表、图片读取、仅 write 权限发布拒绝、publish 权限发布成功、过期 revision 冲突、下架、下架后编辑恢复草稿。 |

源服务使用独立的 `target/p2-m-live-storage-run`；网关测试使用独立 SQLite 文件 `target/p2-m-showcase-live-test.db`。没有使用共享 8080、真实用户、生产服务或模型。服务停止后确认 18764 无监听。隔离测试数据留在各仓库忽略的 `target/` 下，未纳入提交。

## 管理页面 Mock 浏览器

使用前端仓库 `.qa-showcases/vite.config.mjs` 和合成 PNG fixture，在本机 `127.0.0.1:18875` 浏览器中运行；该 mock 仅验证界面交互，不证明真实 API 行为。

- 选择合成 PNG 后出现图片预览；填写必填来源、标题和说明及排序值后创建成功，表格回读“草稿”。调用记录确认上传为 `FormData(file, sourceNote)`，随后创建请求携带 `uploadId` 和内容字段。
- 对草稿执行发布与确认后，表格显示“已发布”；执行下架与确认后显示“已下架”。
- 人为注入一次 409 后，页面锁住保存并显示“重新载入”；点击后重取列表、重开详情，revision 从 2 更新为 3，未提交的旧表单值不覆盖服务端状态。
- `?readOnly` 下新建、发布、下架入口消失；详情可读，保存操作不显示。
- 视口检查：390 px 时对话框 358 px、文档宽度 390 px；320 px 时对话框 288 px、文档宽度 320 px；1440 px 时对话框 760 px、编辑区两列各 352 px。移动端内容在对话框内滚动，创建流程的页脚按钮可滚动到达。

本机截图：`/tmp/p2m-showcase-mobile-390-final.png`、`/tmp/p2m-showcase-mobile-320-final.png`、`/tmp/p2m-showcase-desktop-1440-final.png`。截图没有作为仓库资源提交。

## 前端检查

| 命令 | 结果 |
|---|---|
| `pnpm typecheck` | 通过 |
| 对本次变更的 TS/Vue 文件运行 `pnpm exec eslint --no-cache --max-warnings 0 ...` | 通过，无问题 |
| `pnpm exec prettier --check ...` | 通过，所有目标文件格式正确 |
| `pnpm exec stylelint --max-warnings 0 src/views/integrations/closet/creation/Showcases.vue` | 整理样式属性顺序和空行后通过 |
| `pnpm build` | 通过；产物 3.48 MB。工具提示浏览器兼容数据较旧，不影响构建结果 |
| `node scripts/closet-showcases-contract.test.mjs` | 通过：7 项请求契约、原始 multipart boundary、二进制预览和独立导航 |

## 清理与范围

真实源服务和本地 Vite mock 服务都已停止，分别确认 18764 与 18875 无监听；浏览器 TaskSpace 已关闭。验证只针对本 P2 改动和契约同步，没有重跑大套件、访问生产环境、调用模型、push、开 PR、触发 CI、合并或部署。真实 HTTP 测试的网关入口由 MockMvc 驱动，浏览器部分由本地 mock 驱动；二者覆盖范围已分开记录。
