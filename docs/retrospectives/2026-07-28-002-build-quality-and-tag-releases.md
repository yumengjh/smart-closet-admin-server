# 构建质量与 Tag 发布复盘

日期：2026-07-28

交付方式：随对应提交交付

计划提交说明：`build: enforce quality and tag releases`

## 一、背景与目标

运行时代码修复后，构建侧仍缺少适度覆盖率门槛、依赖更新入口、Maven 发行包
完整性校验和统一发布版本。目标是用低维护成本补足基础工程边界，并让正式 JAR
和镜像只由语义化版本 Tag 驱动，避免分支构建被误认为正式发布。

## 二、范围

### 纳入

- 移除无用缓存 starter，增加 JaCoCo 质量门；
- Maven CI-friendly `revision`；
- Maven Wrapper 下载归档 SHA-512 校验；
- Maven、GitHub Actions、Docker 的 Dependabot 分组更新；
- Docker 构建上下文排除秘密文件；
- JAR 和镜像由 `vMAJOR.MINOR.PATCH` Tag 注入同一版本。

### 排除

- 应用运行时代码和文档；
- 自动合并 Dependabot PR；
- 本地 Docker 镜像构建；
- 创建或推送未经用户指定的版本 Tag。

## 三、实施内容

### 1. 依赖精简与覆盖率门槛

源码没有 `@Cacheable`、`CacheManager` 或实际缓存实现，因此移除
`spring-boot-starter-cache`。JaCoCo 在 `verify` 阶段检查行覆盖率不低于 70%、
分支不低于 45%。最终基线为行 76.1%、分支 51.6%、指令 71.7%，门槛用于阻止
明显回退，不追求用无意义测试堆高数字。

### 2. Dependabot 的作用

`.github/dependabot.yml` 每周检查并分组 Maven、GitHub Actions 和 Docker 更新。
它只创建升级 PR，便于看到过期版本和安全修复；不会参与应用运行、不会自动证明
兼容性，也不等于漏洞扫描或依赖锁定。每个 PR 仍需代码审查和完整 CI。

### 3. Maven Wrapper 指纹校验

`.mvn/wrapper/maven-wrapper.properties` 固定 Maven 3.9.11 的 HTTPS 下载地址和
Apache 发布 ZIP 的 SHA-512。项目使用 `only-script` Wrapper，因此 Unix/Windows
脚本在下载后比对哈希，不一致则拒绝解压执行。

该校验能发现下载损坏、镜像返回错误内容或归档字节被替换，但它不是 Java 依赖
锁文件，也不能证明 POM 依赖无漏洞，更不能防止一开始就记录了恶意归档的指纹。

### 4. 正式版本统一

POM 使用 CI-friendly `${revision}`，本地默认 `0.0.1-SNAPSHOT`。以
`-Drevision=9.8.7` 构建的 JAR 已确认
`META-INF/build-info.properties` 中 `build.version=9.8.7`。

JAR 与镜像工作流都由 Tag push 触发，并在工作流内部严格校验
`^v[0-9]+\.[0-9]+\.[0-9]+$`。去掉前缀 `v` 后，同一版本传给 Maven 和 Docker。
分支 push/PR 只运行普通 CI，不发布正式产物。

镜像流程保持“一次构建、扫描同一归档、通过后推送同一归档”，避免扫描对象与
发布对象不一致。

### 5. Docker 构建上下文

`.dockerignore` 排除 `.env`、`.env.*`、PEM、Key、JKS、P12/PFX 和本地数据库，
并显式保留 `.env.example`。这些文件不会被发送给本地或远程 Builder。
Dockerfile 接收 `APP_VERSION` 并传给 Maven `revision`。

正式策略是不在本地常规构建应用镜像：推送明确版本 Tag 后由 GitHub Actions
构建、测试、扫描和发布，再用 `gh run` 观察结果。本提交不会擅自创建 Tag。

## 四、验证证据

| 验证 | 结果 | 备注 |
| --- | --- | --- |
| `./mvnw --batch-mode clean verify` | 通过 | 108 tests、0 failures、0 errors、1 skipped |
| JaCoCo check | 通过 | 行 76.1%、分支 51.6%，高于 70%/45% |
| 正式版本 JAR | 通过 | `-Drevision=9.8.7`，build.version 一致 |
| YAML 解析 | 通过 | CI、JAR、镜像和 Dependabot |
| 工作流契约断言 | 通过 | Tag-only 且版本来自 `github.ref_name` |
| Wrapper shell 语法 | 通过 | `sh -n mvnw` |
| 差异检查 | 通过 | `git diff --check` |
| 本地 Docker 镜像 | 未执行 | 遵循用户明确约定 |

## 五、问题与过程复盘

- 从 Maven Central 临时下载归档计算哈希时 TLS 失败，改用 Apache 官方发布的
  SHA-512，不降低校验要求；
- 早期本地镜像验证在拉取 `docker/dockerfile:1.7` 元数据时被 DaoCloud 镜像源
  超时阻断，尚未进入项目构建；用户随后明确要求搁置本地镜像；
- 本机没有 `actionlint`，未临时引入新工具；本地使用 YAML/契约检查，工作流真实
  语义由 GitHub Actions 验证；
- Windows `mvnw.cmd` 已做静态复核，当前 macOS 未执行 Windows 实机测试。

## 六、风险与搁置项

- 首个正式 Tag 才会完整验证 JAR/GHCR 发布链路；
- Dependabot 不自动合并，升级仍可能存在行为不兼容；
- 未加入 SBOM、镜像签名、Kubernetes 和远程构建缓存，避免启动模板过度设计；
- 镜像扫描中不能自动修复的漏洞需要项目负责人单独做风险决策。

## 七、主要文件

| 范围 | 文件 |
| --- | --- |
| Maven/覆盖率 | `pom.xml` |
| Wrapper 完整性 | `.mvn/wrapper/maven-wrapper.properties`、`mvnw`、`mvnw.cmd` |
| 依赖维护 | `.github/dependabot.yml` |
| Tag 发布 | `.github/workflows/release-jar.yml`、`.github/workflows/image.yml` |
| 容器构建 | `Dockerfile`、`.dockerignore` |

## 八、结论与后续

构建版本、覆盖率和正式发布入口已经形成一致契约。本次推送 `main` 只触发普通
CI；等用户给出明确版本并授权 Tag 后，再观察 JAR 和镜像发布工作流。
