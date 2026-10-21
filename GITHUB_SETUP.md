# 新仓库配置与首次发布

仓库：<https://github.com/hzh137940556/lingora-android-sdk>。
按照以下顺序操作；不要在审批和保护规则完成前创建版本 Tag。

## 迁移范围与现状

- 源码来自原 SDK/Demo 最新已提交版本 `f49f53dd3b338fe0b5e32c1ac915a137fe7a796d`，
  以新仓库初始提交迁入，不复制旧 Git 历史、Tag、Release 或已发布的 Maven 包。
- 初始化 `main` 与 `develop`，默认分支为 `main`；两者从同一份代码开始。
- 已替换 Maven 下载地址、发布地址与 POM 项目链接；Kotlin package 和 Maven 坐标不变。
- 保留 `Test and build SDK and Demo` 检查、两个发布渠道、人工审批校验、Tag 来源校验、拒绝覆盖版本和发布后消费验收。
- 未复制原目录未提交的 `gradle.properties`、API Key、`.lingorarc`、`local.properties`、
  签名文件、缓存或生成资源；原目录与用户改动保持不变。
- 旧仓库的 Settings 当前无法读取，因此不能声称已完整复制远端保护设置；
  以下列出源码明确要求的规则，PR 审核人数等额外策略仍需所有者按原配置确认。
- 初始化时仓库为私有，Actions 已启用、默认 Token 为只读；分支保护与 Rulesets API 返回套餐限制，
  `android-sdk-release` 尚不存在。这些平台设置不会随 Git 文件复制。

## 1. 先确认仓库可见性与套餐

进入 [Settings → General](https://github.com/hzh137940556/lingora-android-sdk/settings)。

- 当前仓库已由所有者设为 **Public**，可配置下文的人工审批；若以后改回私有，请先确认套餐支持所需门禁。
- GitHub Free 的公开仓库支持分支/Tag Rulesets；私有仓库需要 Pro、Team 或 Enterprise 等支持的套餐。
- **仅升级 Pro 不能满足私有仓库的 Environment 人工审批要求**：Free、Pro、Team 的
  `Required reviewers` 仅支持公开仓库。保留私有且保留原生人工审批，需要支持该能力的 Enterprise 方案。
- 如果准备开源，先确认源码、资源和依赖授权并完成泄密检查，再由所有者决定是否公开；
  如果不公开，也不升级到满足审批要求的方案，普通 CI 可用，但当前发布门禁会拒绝发包。
  不要通过删除校验或管理员绕过来解决。

参考：[Rulesets 可用范围](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-rulesets/about-rulesets)、
[Environment 审批可用范围](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)。

## 2. 确认发布审批账号

当前唯一发布审批账号为 `hzh137940556`，仓库所有者已具备管理员权限，
无需为了发布审批邀请其他账号。若后续增加协作者，可在
[Settings → Collaborators](https://github.com/hzh137940556/lingora-android-sdk/settings/access) 单独配置访问权限。

源码严格要求审批人集合仅为 `hzh137940556`，并允许该账号批准自己发起的发布。
后续若更换审批人，需要同步修改 `scripts/sdk_release.py`、测试和文档；
不能只在网页上变更名单，否则发布校验会失败。

## 3. 确认 Actions 与权限

进入 [Settings → Actions → General](https://github.com/hzh137940556/lingora-android-sdk/settings/actions)：

1. 启用 GitHub Actions，允许本工作流引用的 Actions。
2. `Workflow permissions` 保持默认只读；无需全局打开读写权限，也无需允许 Actions 审批 PR。
3. 查看 [Actions](https://github.com/hzh137940556/lingora-android-sdk/actions)，等待初始化的
   `Test and build SDK and Demo` 运行完成。PR 和 `main`、`develop` 推送只测试构建，不发包。

发布 Job 在 YAML 内单独申请 `packages: write`，使用自动生成的 `GITHUB_TOKEN`，
不需要配置 `GPR_TOKEN`、GitHub PAT 或 PyPI Token。Lingora CLI 的 PyPI 发布仍属于主仓库，
不是本仓库的职责。

## 4. 配置 main、develop 分支保护

先完成第 1 步并让 `Test and build SDK and Demo` 成功运行，再进入
[Settings → Rules → Rulesets](https://github.com/hzh137940556/lingora-android-sdk/settings/rules)。
创建 Active 的 Branch ruleset，目标明确选择 `main`、`develop`：

1. 要求通过 Pull Request 合并；PR 审核人数和额外审核策略按原仓库配置填写。
   如果原设置无法恢复，建议至少 1 人审核，但需所有者确认；PR 作者不能审批自己的 PR。
2. `Require status checks to pass` 只添加 **`Test and build SDK and Demo`**，与 Job 的显示名完全一致，
   不使用内部标识 `verify`，避免一直等待 `Expected`。
   不要添加 `publish` 或 `Publish Android SDK`，它们只在版本 Tag 上执行。
3. 禁止强制推送和删除受保护分支；如需保持合并前最新代码，启用分支 up-to-date 检查。
4. 不配置管理员绕过。如果旧规则还有额外限制，应在这里补回，不能视为已迁移。

日常开发从 `develop` 创建功能分支，PR 合回 `develop`；正式发布前再通过 PR 将已验收代码合入 `main`。
初次初始化不是发布，不会创建任何版本 Tag。

## 5. 配置版本 Tag 保护

在同一页面创建 Active 的 **Tag** ruleset：

- 目标模式：`android-sdk-*`，同时覆盖测试与正式 Tag。
- 启用 `Restrict updates`、`Restrict deletions`。
- 不启用 `Restrict creations`，允许维护者创建新版本 Tag。
- 不设置绕过权限。已发布 Tag 不移动、不覆盖、不删除。

## 6. 配置发布审批 Environment

进入 [Settings → Environments](https://github.com/hzh137940556/lingora-android-sdk/settings/environments)，
创建 **`android-sdk-release`**，名称必须完全一致：

1. 勾选 `Required reviewers`，仅添加 `hzh137940556`，由该账号批准发布。
2. **不勾选** `Prevent self-review`，允许发布者批准自己的发布；这是发布审批，不是 PR 审核。
3. **关闭** `Allow administrators to bypass configured protection rules`。
4. `Deployment branches and tags` 选择 `Selected branches and tags`。
5. 只添加一条规则：类型 **Tag**，名称 **`android-sdk-*`**；不要添加 Branch 或其他规则。
6. 不需要添加 Environment Secret。若没有 `Required reviewers` 控件，请回到第 1 步确认套餐。

`verify-tag` 会在等待审批前以及审批后再次读取并校验上述配置；少配或放宽配置都会失败。

## 7. 配置本地开发与 Demo

本机安装 Java 17、Android SDK 36、Python 3、Make；在个人环境设置 `ANDROID_HOME`，
或在被忽略的 `local.properties` 中填写本机 SDK 路径。

Demo 默认启用 `implementation(project(":lingora-android-sdk"))`，本地模式不需要 Maven 凭据。
新仓库发布成功后，远端包从以下 Maven 仓库读取：

```text
https://maven.pkg.github.com/hzh137940556/lingora-android-sdk
```

外部消费项目也需要更新仓库 URL；保留依赖坐标不代表旧仓库的包会自动迁移。
如果同时配置了旧仓库，移除旧地址，避免从错误仓库解析相同坐标。
读取私有仓库包时，账号需有仓库访问权限，使用具有 `read:packages` 权限的 classic PAT；
只写入个人 `~/.gradle/gradle.properties`：

```properties
gpr.user=<GitHub 用户名>
gpr.key=<GitHub PAT>
```

不要写进仓库内的 `gradle.properties`。包首次发布后在
[Packages](https://github.com/hzh137940556?tab=packages&repo_name=lingora-android-sdk)
确认包关联本仓库，以及消费仓库/账号的读取权限；其他仓库的 `GITHUB_TOKEN` 不是天然具有读取权限。

运行时 Delivery URL 由 App 单独注入，不放 API Key：

```bash
./gradlew :app:assembleDebug -PlingoraContentDeliveryUrl=https://cdn.example.com/your-prefix
```

未配置时仍可构建并使用 APK 本地资源。使用翻译同步时，等固定版本 CLI 已发布后再安装，
复制 `scripts/lingora.android.example.json` 填写项目配置；API Key 只通过 `LINGORA_API_KEY` 注入。
若未来给 CI 增加同步步骤，再添加同名 Actions Secret；当前普通 SDK CI 不执行上传/下载，
无需为了构建而添加该 Secret。配置完成可用 `./scripts/mock_ci.sh` 编排已安装 CLI 的 push/pull，
它会写入服务端，不能拿真实项目做无授权测试。

## 8. 配置完成后，先测试包再正式包

以下命令会触发真实发布，只在前面步骤完成、工作区干净且决定发版时执行：

```bash
git switch develop
git pull --ff-only origin develop
make publish-dev VERSION=0.1.0
```

在 Actions 等待 `Test and build SDK and Demo` 通过，然后点击 `Review deployments` → `Approve and deploy`。
测试包为 `io.lingora:lingora-android-dev:0.1.0`，Tag 为 `android-sdk-dev-0.1.0`。
批准后 CI 发布并让 Demo 下载该 Maven 包构建；即使消费验收失败也不能覆盖已上传版本。

测试验收通过、正式代码通过 PR 同步到 `main` 后，再执行：

```bash
git switch main
git pull --ff-only origin main
make publish VERSION=0.1.0
```

正式包为 `io.lingora:lingora-android:0.1.0`，Tag 为 `android-sdk-0.1.0`。
两包分别管理版本、只能二选一引入；已用版本不可覆盖，再次发布递增版本。
不要为迁移而重新推送旧 Tag，也不要在当前配置未完成时试发正式包。
