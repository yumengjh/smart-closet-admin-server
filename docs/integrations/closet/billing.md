# 收费管理接入与本地联调

2026-11-11。源契约 `smart-closet-server/docs/management-api.yaml`，源提交 `6829831`。网关与管理前端均已覆盖收费22操作；全量管理契约91操作。

## 路由与页面

下表路径在管理后端前加 `/api/v1/closet`，上游前加 `/api/manage/v1`。所有GET要求 `closet:billing:read`，其余要求 `closet:billing:write`。权限授予SUPER_ADMIN，导航与路由同受集成开关控制。

| 方法 | 路径 | 页面 | 实际联调 |
| --- | --- | --- | --- |
| GET | `/billing/settings` | 收费管理 → 总开关与能力开关 | HTTP 200 |
| PUT | `/billing/settings` | 收费管理 → 总开关与能力开关 | HTTP 200 |
| GET | `/billing/tiers` | 收费管理 → 档位与成本 | HTTP 200 |
| PUT | `/billing/tiers/{tier}` | 收费管理 → 档位与成本 | HTTP 200 |
| GET | `/billing/cost-rates` | 收费管理 → 档位与成本 | HTTP 200 |
| POST | `/billing/cost-rates` | 收费管理 → 档位与成本 | HTTP 201 |
| GET | `/billing/margin` | 收费管理 → 利润报表 | HTTP 200 |
| GET | `/billing/margin/alerts` | 收费管理 → 利润报表 | HTTP 200 |
| GET | `/billing/margin/explain` | 收费管理 → 利润报表 | HTTP 200 |
| GET | `/billing/products` | 收费管理 → 商品与订单 | HTTP 200 |
| POST | `/billing/products` | 收费管理 → 商品与订单 | HTTP 201 |
| PUT | `/billing/products/{id}` | 收费管理 → 商品与订单 | HTTP 200 |
| GET | `/billing/orders` | 收费管理 → 商品与订单 | HTTP 200 |
| POST | `/billing/orders/{id}/sandbox-dispute` | 收费管理 → 商品与订单 | HTTP 200 |
| GET | `/billing/redemption-batches` | 收费管理 → 兑换码批次 | HTTP 200 |
| POST | `/billing/redemption-batches` | 收费管理 → 兑换码批次 | HTTP 201 |
| GET | `/billing/redemption-batches/{id}/codes` | 收费管理 → 兑换码批次 | HTTP 200 |
| POST | `/billing/redemption-batches/{id}/disable` | 收费管理 → 兑换码批次 | HTTP 200 |
| DELETE | `/billing/entitlements/{id}` | 业务用户 → 详情 → 点数与权益 | HTTP 200 |
| GET | `/users/{username}/billing` | 业务用户 → 详情 → 点数与权益 | HTTP 200 |
| POST | `/users/{username}/points` | 业务用户 → 详情 → 点数与权益 | HTTP 200 |
| POST | `/users/{username}/entitlements` | 业务用户 → 详情 → 点数与权益 | HTTP 201 |

## 行为与边界

- 集成仍默认关闭；浏览器使用平台JWT，网关使用HMAC调用源服务。创建兑换批次的明文码仅当前响应透传，并设置no-store；审计只保存动作、目标、结果及失败错误码。
- 点数调整必须有 `Idempotency-Key`，原样转发；页面对同一未知结果的重试复用该键。路径参数按段编码，输入范围与源契约一致；不存在映射404、冲突409、限流429。
- 本地收费与沙箱开启。沙箱不产生真实收款，不配置真实支付或调用付费模型。已有个人账号与衣柜数据保留；联调数据仅使用新建测试账号。
- 大屏展示今日订单、按币种实收与上游成本；实收与利润报表的收入确认口径不同。日导出完整保留billing对象，事件筛选增加billing_order/billing_ledger，数据只含userKey。

## 验证结果

- 管理后端完整Maven测试360项，0失败、0错误；PostgresMigrationTest因未设置CI变量跳过。JaCoCo行82.22%、分支62.02%，verify门槛通过。
- 网关契约测试覆盖91个源操作，收费22操作逐项验证认证、RBAC与签名，关闭集成时所有收费路由404。
- 管理前端typecheck、修改文件ESLint、10组契约脚本与生产构建通过。新增脚本检查22操作的路径、请求体、幂等头及页面调用。
- 真实HTTP联调覆盖收费22/22：点数幂等发放和扣回、权益发放/撤销、批次创建/掩码查询/兑换/停用、点数与订阅下单/沙箱支付/重复支付/拒付、开关切换、报表/预警/追溯。
- Semgrep扫描管理网关Java、前端TypeScript与secrets规则，0发现、0解析错误。
- 浏览器验证独立管理端菜单、收费设置、商品订单、批次、利润追溯和用户125点余额；用户端实际点击沙箱购买后余额125→325点、订单已到账，最后停用测试账号。没有真实模型用量，利润用量追溯为空是预期，不生成虚构消费。

## 本地运行

本次工作区通过主项目忽略目录 `.local/start-billing-stack.py` 启动 source/admin/ui，管理前端使用最终dist生产构建的Vite preview（代理到8081），分别监听127.0.0.1的8080/8081/8849，助手8091。源数据库在启用前离线备份到 `.local/backups/billing-before-*`。运行密钥只放本地忽略文件或进程环境，不进Git；现有admin未被重置，另建本地演示管理员。

已有管理数据库的CORS来源通过运行配置 `security.cors.allowed-origins` 添加 `http://127.0.0.1:8849` 和 `http://localhost:8849`；启动环境变量仅在新数据库初始化时使用。
