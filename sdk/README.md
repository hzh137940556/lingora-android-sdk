# Lingora Android SDK

Lingora Android SDK 从公开 Content Delivery 加载翻译，并在远端不可用时回退到 APK 资源。
SDK 要求 `minSdk >= 24`，不依赖 Demo、Compose 或 CLI。

## 1. 接入 SDK

### 配置 GitHub Packages

将 GitHub 用户名和具有 `read:packages` 权限的 classic PAT 写入个人
`~/.gradle/gradle.properties`，不要提交到项目：

```properties
gpr.user=<GitHub 用户名>
gpr.key=<GitHub PAT>
```

在消费项目的 `settings.gradle.kts` 添加仓库：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
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

在 App 的 `build.gradle.kts` 添加固定版本依赖：

```kotlin
dependencies {
    implementation("io.lingora:lingora-android:X.Y.Z")
    // 测试渠道使用：implementation("io.lingora:lingora-android-dev:X.Y.Z")
}
```

正式包和 dev 包包含相同的类，必须二选一，不能使用动态版本。

## 2. 初始化

Content Delivery URL 是公开地址，不要把 API Key 放入 APK。建议通过 BuildConfig、flavor 或
CI 环境注入 URL。

在 App 的 `build.gradle.kts` 中加入以下配置（变量声明放在已有 `plugins` 块之后）：

```kotlin
val deliveryUrl = providers.gradleProperty("lingoraContentDeliveryUrl")
    .orElse(providers.environmentVariable("LINGORA_CONTENT_DELIVERY_URL"))
    .getOrElse("")

android {
    buildFeatures {
        buildConfig = true
    }
    defaultConfig {
        buildConfigField(
            "String", "LINGORA_CONTENT_DELIVERY_URL",
            groovy.json.JsonOutput.toJson(deliveryUrl),
        )
    }
    // 使用 CLI 生成资源时配置；路径相对于消费项目根目录。
    sourceSets.getByName("main") {
        res.srcDir(rootProject.file("generated/lingora/res"))
    }
}
```

`buildConfig = true` 只开启生成，字段由 `buildConfigField` 定义；仅传 `-P` 不会创建字段。
初始化代码应使用 App 的 `android.namespace` 对应的 BuildConfig，不要导入 SDK 的 BuildConfig。
生成目录加入 `.gitignore`，不要与源码目录重复定义同语言、同类型、同名资源。

如果沿用 Demo 构建配置，Gradle property 优先于 `LINGORA_CONTENT_DELIVERY_URL` 环境变量。
Demo 已配置默认开发地址，业务 CI 必须先检查环境变量非空，再通过
`"-PlingoraContentDeliveryUrl=$LINGORA_CONTENT_DELIVERY_URL"` 显式传入，避免误用开发地址。
完整步骤见 [Demo 的业务 CI 地址配置](../demo/README.md#业务-ci-显式选择地址)。

在自定义 `Application` 中初始化一次：

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Lingora.init(
            context = this,
            config = LingoraConfig(
                contentDeliveryUrl = BuildConfig.LINGORA_CONTENT_DELIVERY_URL,
                appVersionCode = BuildConfig.VERSION_CODE.toLong(),
                defaultLanguage = "en-US",
                path = { languageTag -> "app/$languageTag.json" },
                manifestPath = "manifest.json",
            ),
            namespaces = LingoraNamespaces(
                defaultNamespace = "app",
                additionalNamespaces = setOf("common"),
            ),
        )
    }
}
```

在 App 的 `AndroidManifest.xml` 声明网络权限，并注册初始化所在的 Application：

```xml
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <application android:name=".App">
        <!-- 保留项目已有的 Activity 等组件。 -->
    </application>
</manifest>
```

已有 Application 时直接在其 `onCreate()` 中初始化，保留原来的类和 Manifest 属性；
SDK 不声明网络权限，也不会自动注册 Application。

对应的 Content Delivery 根目录需要提供：

```text
manifest.json
app/en-US.json
app/ja.json
app/zh-CN.json
common/en-US.json
common/ja.json
common/zh-CN.json
```

`manifest.json` 决定可用语言；不存在完全匹配的语言时，SDK 按语言回退规则选择，并最终尝试
`defaultLanguage`。

## 3. 读取文案

### 普通调用

```kotlin
val lingora = Lingora.instance

lifecycleScope.launch {
    lingora.preload()
}

val title = lingora.t(this, R.string.welcome)
val count = lingora.tPlural(resources, R.plurals.items_count, quantity)
val weekdays = lingora.tArray(resources, R.array.weekdays)

val sharedNotice = lingora.t(
    this,
    R.string.common_notice,
    namespace = "common",
)
```

远端 JSON 的 Key 与 Android 资源名一致，例如 `welcome` 对应 `R.string.welcome`。不同 namespace
可以包含同名远端 Key；Android APK 的单一资源表不能隔离同名资源，因此本地 fallback 仍应使用
不重复的资源名。调用时始终
保留本地 `strings.xml`、`plurals` 和 `string-array`，它们是远端不可用时的 fallback。

### Compose 或其他响应式页面

```kotlin
val context = LocalContext.current
val title by Lingora.instance
    .tFlow(context, R.string.welcome)
    .collectAsState(initial = context.getString(R.string.welcome))

Text(title)

val sharedNotice by Lingora.instance
    .tFlow(context, R.string.common_notice, namespace = "common")
    .collectAsState(initial = context.getString(R.string.common_notice))
```

复数和数组分别使用 `tPluralFlow`、`tArrayFlow`。Flow 会先返回 APK 文案，加载完成后再发出
远端值。

## 4. 切换语言与刷新

```kotlin
Lingora.instance.setLocale("ja")

lifecycleScope.launch {
    Lingora.instance.refresh()
}
```

- `setLocale()` 切换当前语言，相关 Flow 会重新加载。
- `preload()` 按当前语言预加载默认和全部附加 namespace。
- `refresh()` 跳过当前内存命中和失败冷却，主动检查全部 namespace 的最新内容。
- `availableLocaleTags` 提供 manifest 中的语言列表。
- `loadState` 提供 `Idle`、`Loading`、`Ready` 和 `UsingApkFallback` 状态。

## 5. 回退与缓存

- URL 为空、语言未发布、网络失败或响应无效时，读取 API 返回 APK 资源。
- 有有效磁盘缓存时可以离线使用缓存。
- 内存文档、磁盘缓存和失败冷却均按 `(language, namespace)` 隔离；单个 namespace 失败不会清空其他有效文档。
- 可重试的网络错误会自动重试一次；`refresh()` 可随时手动重试。
- 远端数组长度必须与 APK 数组一致，否则使用完整 APK 数组，避免索引错位。

SDK 运行时不要求安装 CLI。Demo、CLI 静态资源同步和完整示例见
[Demo 接入步骤](../demo/README.md)。维护者的发布、审批和仓库配置见
[GitHub 配置清单](../GITHUB_SETUP.md)。
