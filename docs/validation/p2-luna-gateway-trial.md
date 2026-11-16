# P2 Luna 官方素材管理网关试做记录

- 日期：2026-11-15
- 仓库：`/Users/yaopinxin/project/art-showcase-admin-server`
- 分支：`feat/art-showcase-management`
- 开始时 HEAD：`2629fcba5904a0d4b3d8c8bd530b314ddfd8f452`

## 结果

发现并修复一项上传边界校验缺陷。完整相关测试类运行 220 项，219 项通过；唯一失败是已有网关映射测试发现官方素材路由尚未出现在本地管理 API 契约文件中。该契约文件与快照不在本次修改范围，已保留给主代理处理。

## 发现与依据

`ClosetShowcaseController.upload` 通过 Spring `MediaType` 读取 boundary。对于合法的带引号 boundary，例如 `boundary="fixture boundary"`，`MediaType.getParameter("boundary")` 保留两侧引号；原有校验正则既不允许引号，也不允许 MIME boundary 中合法的内部空格，因此会把合法上传误判为 400。

现在仅在校验时去除成对引号，并接受合法 ASCII boundary 字符及内部空格，仍拒绝空值、尾随空格、非法字符和超过 70 字符的 boundary。请求交给 `postRaw` 的类型头和请求体没有重建。新增测试使用完整 multipart 结构和恰好 12 MiB 的原始请求体，核对上游收到的字节、operator 及按原字节计算的 HMAC；12 MiB + 1 字节仍返回 413。

分页用例补齐 page 0 → 源服务 page 1、page 999999 → 源服务 page 1000000、size 1/50 合法以及越界拒绝，并继续按无序集合校验 query 参数。multipart resolver 用例补齐同一路径的 GET/PUT、尾斜线、子路径、其他上传路径和带 context path 的请求，确认只有精确 POST 路径绕过解析。

完整相关测试类中的失败来自 `ClosetIntegrationApiTest.everyUpstreamContractOperationHasAnActualGatewayMapping`。运行时实际映射包含 7 个 `/creations/showcases` 路由，而 `docs/integrations/closet/management-api.yaml` 的期望清单没有这些路由。按任务约束，本次没有修改该契约文件或快照，也没有放宽测试；请主代理决定后续契约同步工作。

## 修改文件

- `src/main/java/com/yumg/starter/modules/integrations/closet/api/ClosetShowcaseController.java`：按 MIME boundary 规则校验引号包裹及内部空格。
- `src/test/java/com/yumg/starter/modules/integrations/closet/ClosetIntegrationApiTest.java`：补充分页边界、完整 12 MiB multipart、签名及 resolver 路由边界用例。

其余三个授权文件 `ClosetShowcaseService.java`、`ClosetShowcaseMultipartConfiguration.java` 和 `ClosetDisabledTest.java` 与派发前 `/tmp/p2-luna-trial-baseline/manifest.json` 中记录的内容一致，本次未改。开始时这五个基线文件的 SHA-256 均与 manifest 一致。其他已存在的未提交改动均保留，没有 reset、clean 或 stash。

## 测试与检查

| 命令 | 结果 | 日志 |
|---|---|---|
| `./mvnw '-Dtest=ClosetIntegrationApiTest#showcase*' test` | 9 项通过，0 失败，退出码 0 | `/tmp/p2-luna-trial-showcase-final.log` |
| `./mvnw -Dtest=ClosetIntegrationApiTest,ClosetDisabledTest test` | 220 项，219 通过；上述契约映射测试 1 项失败，退出码 1。`ClosetDisabledTest` 65 项全部通过 | `/tmp/p2-luna-trial-full-final.log` |
| `git diff --check` | 通过，退出码 0 | 命令输出为空 |

此前为排查 boundary 校验执行的尝试日志保留在 `/tmp/p2-luna-trial-focused.log`、`/tmp/p2-luna-trial-showcase-final2.log` 和 `/tmp/p2-luna-trial-boundary-debug.log`；最终单项 boundary 测试通过的日志为 `/tmp/p2-luna-trial-boundary-final.log`，1 项通过，退出码 0。

## 未验证范围与清理

真实源服务按任务要求保持关闭；未运行 opt-in `ClosetShowcaseLiveContractTest`，未访问生产环境或调用模型。没有启动共享服务或遗留后台进程；Maven/Surefire 测试进程均已退出。未提交、推送、建 PR、触发 CI 或发版。
