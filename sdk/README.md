# Lingora Android SDK 接入指南

SDK 通过本仓库的 GitHub Packages 发布；Android CI 仍负责在构建 APK 前同步对应环境的静态资源。

## 第一步：准备 GitHub Packages 凭据

GitHub Packages 当前要求安装公开包时也进行身份认证。创建具有 `read:packages` 权限的
Personal Access Token（classic），并将账号和 Token 写入个人的
`~/.gradle/gradle.properties`，不要提交到项目：

```properties
gpr.user=<GitHub 用户名>
gpr.key=<GitHub PAT>
```

## 第二步：引入 Android SDK

在 Android 项目的 `settings.gradle.kts` 中增加 GitHub Packages 仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            name = "LingoraGitHubPackages"
            url = uri("https://maven.pkg.github.com/hzh137940556/lingora-android-sdk")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR"))
                    .orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN"))
                    .orNull
            }
            content { includeGroup("io.lingora") }
        }
    }
}
```

在 `app/build.gradle.kts` 中增加 SDK 依赖和生成资源目录：

```kotlin
android {
    sourceSets.getByName("main") {
        res.srcDir("../generated/lingora/res")
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    implementation("io.lingora:lingora-android:<version>")
}
```

SDK 要求 App 的 `minSdk >= 24`。

测试与正式使用不同的 Maven 包名；同为 `0.1.0` 时二选一：

| 用途 | 依赖 |
| --- | --- |
| 正式项目 | `implementation("io.lingora:lingora-android:0.1.0")` |
| 验证测试 SDK | `implementation("io.lingora:lingora-android-dev:0.1.0")` |

两者的 Kotlin `package` 都是 `io.lingora.android`，切换依赖不需要修改 import。
两个包分别管理版本；每个包已发布的版本不可重用。不要使用动态版本号作为正式项目的依赖。

本仓库有独立的 SDK 和 Demo build，不再从 Demo 中 include SDK module。
本地开发通过 Composite Build 替换同一 Maven 坐标；切换方法见 [Demo 文档](../demo/README.md#切换-sdk-来源)。
SDK 本身不读取 Demo 配置，也不要求安装 CLI。以下 CLI 与 App 配置仅适用于消费项目，不属于 SDK 发布输入。

## 第三步：安装固定版本 CLI

CLI 在 Lingora 主仓库实现并独立发布；消费项目不复制 Python 脚本。
首版 PyPI 发布并安装验收后：

```bash
uv tool install --python 3.12 "lingora-cli==0.1.0"
lingora --version
lingora init
```

编辑 `.lingorarc` 中的 API URL、Project UUID、Branch、文件和目标语言。
多环境可维护多份无密钥 JSON，并通过 `--config` 选择；资源相对路径以配置文件目录为基准。
根目录配置使用 `generated/lingora/res`，位于 `ci/` 下的配置使用 `../generated/lingora/res`。
默认 namespace 为 `app`，增加数组文件时将 `arrays.xml` 加入同一 `push.files` 列表。
API Key 只通过环境或 CI Secret 注入。

迁移期间仓库保留旧翻译脚本，待 CLI 首版发布并验证后删除；不要删除 `sdk_release.py`。

## 第四步：在 CI 中同步资源

在 CI Secret 中配置 `LINGORA_API_KEY`，然后根据当前构建环境选择配置文件：

```bash
export LINGORA_API_KEY='<CI secret>'

lingora \
  --config ci/lingora.environment-a.json \
  pull

./gradlew :app:assembleDebug
```

其他环境只替换 `--config`：

```bash
lingora --config ci/lingora.environment-b.json pull
lingora --config ci/lingora.environment-c.json pull
```

每次 pull 都会更新同一个 `generated/lingora/res`，Android 构建时会自动将其合并进 APK。

## 第五步：创建 Content Delivery

在 Lingora 中为每个 Android 环境选择对应的 Project 和 Branch，创建 Content Delivery：

```text
format = ANDROID_SDK
zip = false
filterNamespace = ["app"]
filterState = ["REVIEWED"]
fileStructureTemplate = {namespace}/{languageTag}.{extension}
```

发布后记录每个环境的公开 URL。CI 配置中的 Branch 必须与该 URL 所绑定的 Branch 一致。

## 第六步：初始化 SDK

App 单独配置公开 Delivery URL，不读取 CLI 配置：

```bash
./gradlew :app:assembleDebug -PlingoraContentDeliveryUrl=https://cdn.example.com/your-prefix
```

也可通过 `LINGORA_CONTENT_DELIVERY_URL` 环境变量或个人 Gradle properties 提供，
Gradle property 优先。Application 使用：

```kotlin
Lingora.init(
    context = this,
    config = LingoraConfig(
        contentDeliveryUrl = BuildConfig.LINGORA_CONTENT_DELIVERY_URL,
        appVersionCode = BuildConfig.VERSION_CODE.toLong(),
        defaultLanguage = "en-US",
        path = { languageTag -> "app/$languageTag.json" },
        manifestPath = "manifest.json",
    ),
)
```

当前语言默认跟随系统；`defaultLanguage` 是基础回退语言，不是强制当前语言。
未提供 URL 时使用 APK fallback 并在构建中提示。SDK 不管理环境、Branch、flavor 或 App ID。
正常 Gradle 构建不运行 CLI。

## 第七步：读取翻译

首次进入页面时可以预加载当前语言：

```kotlin
lifecycleScope.launch {
    Lingora.instance.preload()
}
```

读取翻译时传入原有 Android resource ID；远程内容不可用时会回退到 APK 资源：

```kotlin
val title = Lingora.instance.t(this, R.string.welcome)
```

后台重新发布 Content Delivery 后，可以刷新：

```kotlin
lifecycleScope.launch {
    Lingora.instance.refresh()
}
```

响应式页面可以使用 `tFlow`、`tPluralFlow` 和 `tArrayFlow`。

## 第八步：验证配置

分别用对应 `--config` 同步资源，再通过 App 自己的构建输入选择同一 Delivery：

```bash
lingora --config ci/lingora.environment-a.json pull
lingora --config ci/lingora.environment-a.json delivery check --alias current
./gradlew :app:assembleDebug -PlingoraContentDeliveryUrl=https://cdn.example.com/your-prefix
```

URL、namespace 和语言应与服务端 Delivery 一致；未配置 URL 时验证 APK fallback。
本仓库 Demo 通过 Gradle 属性切换 SDK 来源，与 CLI 版本无关。

## 发布 SDK（维护者）

发布由 GitHub Actions 完成，使用仓库自动生成的 `GITHUB_TOKEN`，不需要额外发布 Token。
维护者在仓库根目录执行对应命令（根 Makefile 只转发到 sdk/）：

| 渠道 | 分支 | 命令 | Tag | Maven 坐标 |
| --- | --- | --- | --- | --- |
| 测试 | `develop` | `make publish-dev VERSION=0.1.0` | `android-sdk-dev-0.1.0` | `io.lingora:lingora-android-dev:0.1.0` |
| 正式 | `main` | `make publish VERSION=0.1.0` | `android-sdk-0.1.0` | `io.lingora:lingora-android:0.1.0` |

两种命令都要求工作区干净、本地分支与远端同步、Tag 尚未使用。版本号为没有前导零的
`X.Y.Z`；再次测试使用新的测试版本，例如 `0.1.1`，不会占用正式包的同版本号。
`sdk/scripts/sdk_release.py` 统一处理渠道、版本、分支、Tag 与 Maven 包名映射。

命令会先执行测试和构建，再创建带说明的 Tag 并推送。推送失败时保留本地 Tag，并提示
`git push origin <tag>` 重试；不要删除或移动已经发布的 Tag。

SDK 发布与 Demo 验收分开运行：

1. `verify`（固定检查名 `Android SDK CI`）：仅运行发布门禁回归、SDK 单元测试、Release 构建，
   并向 `sdk/build/verified-maven` 生成 Maven 产物，检查 AAR、POM、sources、metadata、版本、
   校验和及协程 API 依赖。没有 Demo 也可运行。
2. `publish`：Tag 来源校验通过后等待人工批准，在相同 SHA 重新核对来源与审批配置，
   拒绝重复版本，随后上传 SDK；只有此 Job 有 `packages: write`。
3. 独立调度 Job 只有 `actions: write`，请求 `Android Demo CI` 下载刚发布包。SDK 发布不加载、
   改写或编译 Demo。调度失败只提示未验收，不伪称包上传失败；Demo 失败不会覆盖或回滚已发布包。

发布运行标题和审批 Job 显示渠道与版本。Environment `android-sdk-release` 只是共用的审批门禁名称，
不决定包渠道。在 Actions 核对 Maven 坐标、Tag、SHA，然后点击 `Review deployments` →
`Approve and deploy`。批准后只使用已校验 SHA，上传成功后 Summary 提供上传地址、包列表和依赖声明。

Tag 提交必须已存在于对应远端分支历史，未合并 PR 不能直接发包。两个 Maven 包含相同类，消费项目必须二选一。
独立 Demo smoke 的开通与手动重试见 [配置清单](../GITHUB_SETUP.md#7-启用独立-demo-验收)。

### 首次发布前：仓库所有者配置

新仓库的完整操作顺序、套餐限制和本机配置见 [仓库配置清单](../GITHUB_SETUP.md)。
GitHub Settings、旧仓库的包和本机凭据不会随源码迁移；审批配置未完成时发布会停止。

代码中的 `environment` 名称本身不会创建审批要求，以下设置必须由仓库所有者先完成。
当前发布校验会拒绝不存在或不符合以下要求的 Environment，防止无保护发布。

1. 进入 `Settings` → `Environments`，创建 `android-sdk-release`。
2. 勾选 `Required reviewers`，仅添加 `hzh137940556`；由该账号批准发布。
   不勾选 `Prevent self-review`，允许自己批准；关闭 `Allow administrators to bypass configured protection rules`。
3. `Deployment branches and tags` 选择 `Selected branches and tags`，只添加一条规则：
   类型为 **Tag**，名称为 `android-sdk-*`。不要添加同名的 Branch 规则。
4. `Settings` → `Rules` → `Rulesets` 新建 Active 的 Tag ruleset，目标为 `android-sdk-*`，
   启用 `Restrict updates` 和 `Restrict deletions`，不配置绕过权限，不限制新 Tag 的创建。
5. `Android SDK CI` 在 PR 中成功运行后，将 `main`、`develop` 分支规则的必需检查设置为
   `Android SDK CI`，不使用内部标识 `verify`；保留已有 PR 审核规则，
   不将 `publish` 或 `Publish Android SDK` 设为必需检查。

审批配置完成后合并发布 PR，并通过 PR 将同一份发布配置同步到 `develop`。先发布测试包并在
外部消费项目验证下载、引用和构建，再从 `main` 发布正式包。

### 本地验证

本机需要 Java 17、Python 3、Android SDK 36（通过 `ANDROID_HOME` 或被忽略的 `local.properties`
指定）和 GNU Make。以下命令在 sdk/ 下执行；只运行发布门禁与产物回归测试：

```bash
make test-release
```

完整校验只读取 SDK，不加载 Demo；校验 Maven 产物写入 sdk/build/verified-maven，不写用户 Maven 缓存：

```bash
make verify
```

分别发布两个渠道到本地 Maven，版本号可以相同：

```bash
make publish-local VERSION=0.1.0 SDK_CHANNEL=dev
make publish-local VERSION=0.1.0 SDK_CHANNEL=release
```

发布时 Gradle 显式接收 `-PVERSION_NAME=0.1.0 -PSDK_CHANNEL=dev|release`；默认的
`0.1.0-SNAPSHOT` 仅用于普通源码构建，不允许远端发布。两个渠道的 `BuildConfig.SDK_VERSION`
都等于各自依赖版本 `0.1.0`，发布记录通过完整 Maven 坐标区分渠道。

如果已有版本、权限不足、网络错误或审批配置不完整，工作流会失败，不删除已有包或强行重发。
发布后的消费构建失败不回滚已上传的版本，应修复后使用新版本；不要重跑整个发布 Job 覆盖包。
重复版本必须更换版本号。其他仓库的 CI 消费包时，将具有 `read:packages` 权限的 PAT 保存为
Secret，并通过 `GITHUB_ACTOR`、`GITHUB_TOKEN` 传给 Gradle。

完整 Demo 说明见 [Demo 文档](../demo/README.md)。

## 加载失败与重试

同一语言的文本、复数和数组共用加载结果。没有可用缓存时，网络连接错误或 HTTP
408、500、502、503、504 会等待 1 秒重试一次；仍然失败则使用 APK 文案，并在接下来的
5 秒内共用失败结果，避免每处文字重复下载。冷却结束后，新订阅或 `preload()` 可以重新加载；
`refresh()` 不受冷却限制。404、无效 JSON 等错误不自动重试。有有效磁盘缓存时直接使用缓存，
304 响应缺少有效缓存时仍执行必要的无条件请求。

SDK 的请求计数与恢复回归可在模拟器或测试设备运行：

```bash
./gradlew :lingora-android-sdk:connectedDebugAndroidTest
```

测试使用独立 instrumentation APK 内的本地 HTTP 服务，不访问真实 Content Delivery，
测试 APK 的明文 HTTP 配置不会进入 SDK AAR 或 Demo APK。
