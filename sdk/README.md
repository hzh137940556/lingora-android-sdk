# Lingora Android SDK

SDK 是独立 Android Library，要求 `minSdk >= 24`，不依赖 Demo、Compose 或 CLI。
源码联调与 Demo 配置见 [Demo 文档](../demo/README.md)。

## 接入 Maven 包

下载 GitHub Packages 需要认证。将账号和具有 `read:packages` 权限的 classic PAT
放入个人 `~/.gradle/gradle.properties`，不要提交到项目：

```properties
gpr.user=<GitHub 用户名>
gpr.key=<GitHub PAT>
```

消费项目的 `settings.gradle.kts`：

```kotlin
dependencyResolutionManagement {
    repositories {
        google()
        mavenCentral()
        maven {
            url = uri("https://maven.pkg.github.com/hzh137940556/lingora-android-sdk")
            credentials {
                username = providers.gradleProperty("gpr.user")
                    .orElse(providers.environmentVariable("GITHUB_ACTOR")).orNull
                password = providers.gradleProperty("gpr.key")
                    .orElse(providers.environmentVariable("GITHUB_TOKEN")).orNull
            }
            content { includeGroup("io.lingora") }
        }
    }
}
```

App 的 `build.gradle.kts` 中二选一，填写已经发布的固定版本：

```kotlin
dependencies {
    implementation("io.lingora:lingora-android:<version>") // 正式包
    // implementation("io.lingora:lingora-android-dev:<version>") // 测试包
}
```

两个包包含相同的 `io.lingora.android` 类，不能同时引入；切换包不需要改 import。
其他仓库的 Actions 还需要获得包读取权限，不要使用动态版本或依赖旧仓库同名坐标。

## 初始化与读取翻译

App 自行决定公开 Delivery URL 和当前语言，不把 API Key 放进 APK。
下面的 BuildConfig 字段由 App 的构建配置提供，见 [Demo 配置](../demo/README.md#content-delivery)：

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

当前语言默认跟随系统，`defaultLanguage` 是基础回退语言；App 可调用
`Lingora.instance.setLocale("ja")` 切换。未配置 URL 或远程内容不可用时保留 APK fallback。
SDK 不管理环境、Branch、flavor 或 App ID。

```kotlin
lifecycleScope.launch {
    Lingora.instance.preload()
}
val title = Lingora.instance.t(this, R.string.welcome)

// 后台重新发布后主动刷新
lifecycleScope.launch {
    Lingora.instance.refresh()
}
```

响应式页面可使用 `tFlow`、`tPluralFlow` 和 `tArrayFlow`，由 App 自行收集状态。
可选的 CLI 静态资源同步、生成目录和 Delivery 服务端配置统一见 [Demo 文档](../demo/README.md)；
SDK 正常构建与运行不要求安装 CLI，也不自动上传翻译。

## 发布 SDK（维护者）

在干净且与远端同步的来源分支上，从仓库根目录执行；`X.Y.Z` 必须替换为未发布的新版本：

```bash
# develop：android-sdk-dev-X.Y.Z -> io.lingora:lingora-android-dev:X.Y.Z
make publish-dev VERSION=X.Y.Z

# main：android-sdk-X.Y.Z -> io.lingora:lingora-android:X.Y.Z
make publish VERSION=X.Y.Z
```

命令仅调用 SDK 自身 verify，通过后创建 annotated Tag 并推送。推送失败时保留本地 Tag，
可重试 `git push origin <tag>`；不得移动、删除已发布 Tag 或覆盖包版本。

发布流程为 SDK 校验 → 人工审批 → 同一 SHA 发包。仅 publish Job 有包写权限，
使用 Actions 自动提供的 GITHUB_TOKEN，不需要另存发布 PAT。
SDK CI 检查 AAR、POM、sources、metadata、坐标、版本、校验和、公共协程依赖及无 App 类。

Demo 在独立工作流中下载已发布包，失败不影响包已上传的事实，也不能覆盖已发布版本。
审批、分支/Tag 保护和 Demo dispatch 的设置只维护在 [GitHub 配置清单](../GITHUB_SETUP.md)，
本页不重复列出；未合并 PR 不得发包，缺少审批配置必须停止。

## 本地验证

以下命令在 `sdk/` 下执行，需要 Java 17、Android SDK 36、Python 3 和 Make：

```bash
make test-release
make verify
# 同版本的两个渠道产物写入不同目录，仅生成本地校验包
make verify VERSION=0.1.0 SDK_CHANNEL=dev
make verify VERSION=0.1.0 SDK_CHANNEL=release
```

verify 仅写入 `sdk/build/verified-maven`，不读取 Demo、不上传远端包。
默认 `0.1.0-SNAPSHOT` 仅用于源码构建，不允许远端发布。
若需要安装到用户本地 Maven 缓存，可单独执行 `make publish-local VERSION=0.1.0 SDK_CHANNEL=dev`，正式渠道改为 `release`。

## 加载失败与重试

同一语言的文本、复数和数组共用加载结果。没有可用缓存时，网络连接错误或 HTTP
408、500、502、503、504 会等待 1 秒重试一次；仍然失败则使用 APK 文案，并在接下来的
5 秒内共用失败结果，避免每处文字重复下载。冷却结束后，新订阅或 `preload()` 可以重新加载；
`refresh()` 不受冷却限制。404、无效 JSON 等错误不自动重试。有有效磁盘缓存时直接使用缓存，
304 响应缺少有效缓存时仍执行必要的无条件请求。

设备/模拟器回归在 sdk/ 下执行：

```bash
./gradlew :lingora-android-sdk:connectedDebugAndroidTest
```

测试使用独立 instrumentation APK 内的本地 HTTP 服务，不访问真实 Content Delivery，
测试 APK 的明文 HTTP 配置不会进入 SDK AAR 或 Demo APK。
