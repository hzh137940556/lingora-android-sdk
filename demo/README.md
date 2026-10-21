# Lingora Android Demo

Demo 展示一条最小接入链路：App 引入 SDK，CLI 同步静态翻译，页面通过 SDK 读取动态文案。
以下命令均在 `demo/` 下执行，Android Studio 也直接打开此目录。

## 1. 接入 SDK

Demo 支持三种 SDK 来源：

```properties
# 默认使用 GitHub Packages 测试包
lingoraSdkMode=dev
lingoraSdkVersion=0.1.4

# 本仓库源码联调
# lingoraSdkMode=local
# lingoraLocalSdkPath=../sdk

# GitHub Packages 正式包
# lingoraSdkMode=release
# lingoraSdkVersion=X.Y.Z
```

使用远端包时，在个人 `~/.gradle/gradle.properties` 配置读取凭据，不要提交：

```properties
gpr.user=<GitHub 用户名>
gpr.key=<具有 read:packages 权限的 classic PAT>
```

`app/build.gradle.kts` 只声明一次 SDK 依赖，`settings.gradle.kts` 根据
`lingoraSdkMode` 选择本地源码、dev 包或 release 包。完整的消费项目配置见
[SDK 接入与使用](../sdk/README.md)。

## 2. 接入 CLI

CLI 用于把 Android 基础资源上传到 Lingora，再把目标语言下载到生成目录。Demo 固定使用
`lingora-cli.version` 中的版本，本机只需安装 `uv`。

`ci/lingora.dev.json` 保存项目、分支、语言和资源路径，不保存 API Key。运行前注入密钥：

```bash
export LINGORA_API_KEY='<API Key>'
```

上传并下载：

```bash
make mock_ci
```

也可以只执行某一步：

```bash
CLI_VERSION="$(tr -d '\r\n' < lingora-cli.version)"
uvx --from "lingora-cli==$CLI_VERSION" lingora --config ci/lingora.dev.json push
uvx --from "lingora-cli==$CLI_VERSION" lingora --config ci/lingora.dev.json pull
```

- `push` 上传 `values/strings.xml` 和 `values/arrays.xml` 中的基础语言。
- `forceMode=NO_FORCE` 遇到服务端非空值冲突时停止，不静默覆盖。
- `pull` 只下载配置中状态为 `TRANSLATED` 或 `REVIEWED` 的非空目标翻译。
- 输出写入 `generated/lingora/res`；该目录已加入 App 的 main resource source set，不提交 Git。

CLI 只负责同步资源，不会自动翻译，也不会在普通 Gradle 构建中自动运行。

## 3. App 前端使用

先为 App 配置公开 Content Delivery URL：

```properties
lingoraContentDeliveryUrl=https://example.com/lingora/localstrings/dev/v1
```

也可以通过环境变量 `LINGORA_CONTENT_DELIVERY_URL` 注入。未配置或远端不可用时，SDK 自动使用
`app/src/main/res` 中的 APK 文案。

App 在 [LingoraDemoApplication.kt](app/src/main/java/com/example/myapplication/LingoraDemoApplication.kt)
初始化 SDK。Compose 页面使用响应式 API：

```kotlin
val context = LocalContext.current
val text by Lingora.instance
    .tFlow(context, R.string.welcome)
    .collectAsState(initial = context.getString(R.string.welcome))

Text(text)
```

切换语言或主动刷新：

```kotlin
Lingora.instance.setLocale("ja")
lifecycleScope.launch { Lingora.instance.refresh() }
```

最后构建 Demo：

```bash
./gradlew :app:assembleDebug
```
