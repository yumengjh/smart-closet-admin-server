# 启动可观测性与响应标识复盘

日期：2026-07-29

交付方式：随对应提交交付

计划提交说明：`feat: improve startup observability and response identity`

## 一、背景与目标

模板原有启动日志由框架组件分散输出，应用真正就绪后缺少一眼可读的运行摘要；
响应也没有统一、可由部署环境调整的服务标识。本轮目标是在不引入额外排版依赖
的情况下补充字符化启动摘要，并为全部 Servlet 响应增加可配置的 `Powered-By`
与 `Server` Header，默认值为 `ASP.NET` 与 `Microsoft-IIS/10.0`。

## 二、范围

### 纳入

- Ready 事件后的字符框启动摘要；
- `APP_RESPONSE_POWERED_BY`、`APP_RESPONSE_SERVER` 启动环境变量及输入校验；
- 覆盖业务接口、认证错误、Actuator 和静态资源的统一响应头；
- 配置模板、Compose、用户文档、API 文档、部署文档和维护约束；
- 属性、过滤器、摘要渲染及应用集成测试。

### 排除

- 不修改或暂存用户已有的 `api-test.html`；
- 不构建或启动本地 Docker 应用镜像；
- 不把响应展示标识作为安全控制或真实技术栈声明；
- 不增加只用于字符排版的第三方库。

## 三、实施内容

### 1. 可配置响应标识

- 新增 `ResponseIdentityProperties`，读取 `app.response.powered-by` /
  `APP_RESPONSE_POWERED_BY` 与 `app.response.server` / `APP_RESPONSE_SERVER`；
- 空值分别回退为 `ASP.NET` 与 `Microsoft-IIS/10.0`，值会去除首尾空白，
  最长 128 字符；
- 拒绝 CR、LF 和其它控制字符，避免响应拆分或日志排版污染；
- 新增最高优先级的 `OncePerRequestFilter`，统一写入 `Powered-By` 与 `Server`，
  并防止后续 Servlet 代码覆盖模板配置。

### 2. Ready 启动摘要

- 监听 `ApplicationReadyEvent`，确保摘要代表应用已经真正可以处理请求；
- 从实际 Web Server Context 读取端口，而不是只显示静态配置；
- 展示应用名、构建版本、Profile、HTTP/健康/OpenAPI 地址、数据库类型、
  `Powered-By`、`Server` 和启动耗时；
- 使用 JDK 字符串与 Unicode 框线完成排版，对动态文本清理控制字符并限制宽度；
- 不输出数据库密码、JWT 密钥、管理员凭据或完整连接串。

### 3. 模板和文档同步

- `.env.example` 与 `compose.yaml` 增加两项变量及其默认值；
- README 补充启动日志示例语义、环境变量用法和响应约定；
- API、部署、项目状态与 AGENT 文档明确作用范围、安全边界和维护规则。

## 四、验证证据

| 验证 | 结果 | 备注 |
| --- | --- | --- |
| TDD 红灯 | 通过 | 目标类不存在时定向测试按预期在测试编译阶段失败 |
| 定向测试 | 通过 | 14 tests，0 failures，0 errors，0 skipped |
| 完整质量门 | 通过 | 118 tests，0 failures，0 errors，1 skipped；行 77.3%、分支 52.8% |
| Compose/差异检查 | 通过 | `docker compose config --quiet`、`git diff --check`；未构建镜像 |
| 独立 JAR 烟测 | 通过 | health 200、未认证接口 401，均只有一组自定义双 Header |
| 本地数据隔离 | 通过 | 测试使用 `target`，烟测使用临时 SQLite；保留后续本地运行数据 |

## 五、问题与过程复盘

- 首次组合测试补丁因旧测试 import 上下文判断错误被整体拒绝；检查文件不存在和
  Git 差异后，改为按准确上下文分批应用，未留下半成品。
- 官方文档查询服务在解析 Spring Boot 4.1 文档库后发生传输错误；没有重复无效
  请求，改用本地 Maven 实际依赖的 `javap` 核对 Ready 事件、Web Context 与
  Web Server API 签名。
- 过滤器测试最初尝试把包装响应强制转换为 Mock 响应；改为面向
  `HttpServletResponse` 接口测试，更符合 Servlet Filter 链真实行为。
- 第一次独立 JAR 烟测把 SQLite 指向尚未创建的临时子目录，应用按预期返回
  `SQLITE_CANTOPEN` 并拒绝启动；改用已经存在的临时目录后成功创建 245,760
  字节空库，没有改动仓库内开发数据库。
- 第一版烟测提取 Header 的 `awk` 写法未在当前系统上得到值，且脚本没有启用
  fail-fast，后续断言仍继续执行；最终脚本启用 `set -e` 并改用大小写不敏感的
  `grep`。HTTP 字段名本身大小写不敏感，验证时只对字段值作严格匹配。
- 用户确认伪装目标后，将初版 `Power-by` 改为更自然的 `Powered-By`，并增加
  `Server`；因为本轮尚未提交且项目不要求兼容，环境变量也直接统一为
  `APP_RESPONSE_POWERED_BY`、`APP_RESPONSE_SERVER`，没有保留临时旧名称。
- 最终 JAR 在临时目录和 18851 端口启动，全新 SQLite 为 245,760 字节；health
  返回 200、未认证接口返回 401，两者都且只包含一组
  `Powered-By: PHP/8.4`、`Server: nginx`，启动摘要同步显示两个自定义值。
- 初轮完成时 `data/app.db` 与原始指纹一致；随后文件在 12:15 发生本地运行变化，
  早于 12:22 开始的双 Header 调整。最终检查没有进程占用该文件，因此把它视为
  需要保留的开发数据，没有回滚、删除或纳入提交。

## 六、风险与搁置项

- Unicode 框线依赖终端字体支持；即使字形不理想也只影响日志视觉，不影响启动。
- `Powered-By` 是展示 Header，`Server` 也常被代理或网关删除、追加或覆盖；
  端到端部署时需按实际链路验证。
- PostgreSQL/Testcontainers 和镜像发布由远端 CI 验证，本轮不在本地构建镜像。

## 七、主要文件

| 范围 | 文件 | 说明 |
| --- | --- | --- |
| 响应 | `ResponseIdentityProperties.java`、`ResponseIdentityHeaderFilter.java` | 两项配置校验与统一 Header |
| 启动 | `StartupSummaryLogger.java` | Ready 字符框摘要 |
| 配置 | `application.yml`、`.env.example`、`compose.yaml` | 默认值和环境变量 |
| 测试 | 三份新增测试、`StarterApplicationTest.java` | 属性、过滤器、渲染和集成覆盖 |
| 文档 | README、AGENT、API、部署、项目状态 | 使用与维护契约 |

## 八、结论与后续

代码、文档、完整质量门、静态配置检查和独立 JAR 烟测均达到目标，可以进入
提交环节。本轮不自动创建 Tag 或触发发布，发布仍需用户明确授权。
