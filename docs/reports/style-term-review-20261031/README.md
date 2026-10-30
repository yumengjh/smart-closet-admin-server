# 风格词审核代理报告（管理平台后端）

- 仓库：smart-closet-admin-server，分支 `feat/style-term-review`，基于 main `e3f5020`
- 问题登记：yumengjh/smart-closet-server #173；日期 2026-10-31；未 push、未合并
- 依赖：鱼搭 `feat/style-term-review-manage` 的 `/api/manage/v1/style-terms/pending*`

## 改动

| 项 | 内容 |
| --- | --- |
| 接口 | `GET /api/v1/closet/style-terms/pending`、`POST …/approve`、`POST …/reject`、`POST …/batch`（`ClosetStyleTermController`，HMAC 经 `ClosetManagementClient` 转发） |
| 权限点 | `closet:styleterms:read`、`closet:styleterms:write`，由 `ClosetPermissionBootstrap` 授予 SUPER_ADMIN |
| 菜单 | `closet-style-terms`（风格词审核，`/closet/style-terms`），组件键加入 `NavigationService.COMPONENT_KEYS` |
| 审计 | `CLOSET_STYLE_TERM_APPROVE`/`REJECT`，成功与失败都记，详情含词、映射、错误码，无用户信息 |
| 错误 | 上游 404 `STYLE_TERM_NOT_FOUND` 转平台 `NOT_FOUND` |
| 批量 | 最多 50 项，逐项独立转发与审计，单项失败不影响其他；批量批准不改映射 |

## 测试

`./mvnw -o -q test -Dtest='com.yumg.starter.modules.integrations.closet.*Test,com.yumg.starter.modules.navigation.*Test,ArchitectureRulesTest'`：97 个，0 失败，0 错误。新增 4 个用例（权限与菜单种子、队列签名与 0 起分页、批准/驳回的权限/校验/审计、批量逐项结果）。

## 未做

`docs/integrations/closet/management-api.yaml` 是历史快照，与鱼搭主仓 `docs/management-api.yaml` 本来就不一致（缺 token-budget 等），本次未同步；以鱼搭主仓为准。
