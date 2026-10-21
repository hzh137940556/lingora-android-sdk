# Lingora Android SDK 与 Demo

本仓库包含 Kotlin 实现的 `:lingora-android-sdk` module 和示例 App。Demo 默认依赖仓库内
SDK，也可以切换为已发布的 Maven 包，不依赖 Lingora 服务端源码。SDK 不绑定 Compose；Demo App
在自己的 Kotlin 源码中收集 SDK 提供的 Flow。

代码仓库：[hzh137940556/lingora-android-sdk](https://github.com/hzh137940556/lingora-android-sdk)。
新仓库首次使用请按 [GitHub 与本机配置步骤](./GITHUB_SETUP.md) 完成权限、保护规则和发布审批。
迁移保留原有 CI 与发布门禁，不会自动迁移 GitHub Settings、旧包或本机配置。

## 切换 Demo 的 SDK 依赖

只修改 `app/build.gradle.kts` 的 SDK 依赖，以下三种方式只启用一行：

```kotlin
dependencies {
    implementation(project(":lingora-android-sdk")) // 本地源码，默认
    // implementation("io.lingora:lingora-android:0.1.0") // 远端正式包
    // implementation("io.lingora:lingora-android-dev:0.1.1") // 远端测试包
}
```

`settings.gradle.kts` 已配置 GitHub Maven 仓库。使用远端包前，在个人
`~/.gradle/gradle.properties` 中配置账号和具有 `read:packages` 权限的 PAT，不要提交到仓库：

```properties
gpr.user=<GitHub 用户名>
gpr.key=<GitHub PAT>
```

CI 也可以通过 `GITHUB_ACTOR`、`GITHUB_TOKEN` 环境变量提供凭据。本地源码模式不需要这些凭据。
切换后在 Android Studio 执行 Gradle Sync，或运行 `./gradlew :app:assembleDebug`；无需额外的
init script 或命令行切换参数，Kotlin import 和初始化代码保持不变。本地 module 可以继续保留，
只有被依赖时才会进入 Demo。

## SDK 版本 CI 与发布

普通 PR 和 `main`、`develop` 的推送执行 `Android SDK CI` 测试与构建检查。
`develop` 使用 `make publish-dev VERSION=0.1.0` 发布 `lingora-android-dev` 测试包；
`main` 使用 `make publish VERSION=0.1.0` 发布 `lingora-android` 正式包。
对应 Tag 分别为 `android-sdk-dev-0.1.0`、`android-sdk-0.1.0`，两者均需人工批准后上传
GitHub Packages。首次使用前由仓库所有者配置审批 Environment 和 Tag 保护规则。

接入方式、维护者操作与配置清单见
[`SDK 接入指南`](./lingora-android-sdk/README.md#发布-sdk维护者)。

## 多环境 CLI 配置

翻译工具由 Lingora 主仓库独立发布，Android 只调用固定版本的命令，不复制或导入实现。
首次 PyPI 发布并验证安装完成后使用：

```bash
CLI_VERSION="$(tr -d '\r\n' < lingora-cli.version)"
uv tool install --python 3.12 "lingora-cli==$CLI_VERSION"
lingora --version
lingora init
```

当前迁移阶段暂时保留旧翻译脚本；不要将它们继续复制到新接入项目。
CLI 首版发布和安装验收完成后再清理旧文件；Maven 发布脚本不受影响。
完整参数和无配置调用示例见 [CLI 命令手册](https://github.com/brain/lingora/blob/main/cli/COMMANDS.md)，
安装与发布说明见 [Lingora CLI](https://github.com/brain/lingora/tree/main/cli)。

`lingora init` 创建根目录配置，默认 namespace 为 `app`。本仓库也保留多文件配置模板：

Lingora 不预设环境名称。使用者从同一个模板复制任意数量的配置，并让 CI 用 `--config` 显式选择：

```bash
mkdir -p ci
cp scripts/lingora.android.example.json ci/lingora.environment-a.json
cp scripts/lingora.android.example.json ci/lingora.environment-b.json
cp scripts/lingora.android.example.json ci/lingora.environment-c.json
```

分别填写每份配置的 Lingora `apiUrl`、Project UUID、Branch、语言、namespace 和路径。配置使用
`format`、`push.files`、`push.*`、`pull.*` 分组；相对路径都以所选配置文件所在
目录为基准。所有配置必须把 `pull.path` 指向同一个 `../generated/lingora/res`。

API Key 不允许写入 JSON，只通过 CI Secret 注入：

```bash
export LINGORA_API_KEY='<CI secret>'
lingora --config ci/lingora.environment-a.json pull
```

更换环境时只替换配置路径；脚本不解析或硬编码环境名称。CLI 只提供独立操作，不提供
`lingora mock-ci`。需要模拟 push 后 pull 时，由本仓库的 Demo 脚本编排：

```bash
# 使用仓库根目录的 .lingorarc
./scripts/mock_ci.sh

# 或显式选择环境配置
./scripts/mock_ci.sh --config ci/lingora.environment-a.json
```

脚本回到仓库根目录，再依次调用已安装的 `lingora push`、`lingora pull`，不加载本仓库旧脚本
或主仓库源码。脚本参数仅限两步共用的公共参数，例如 `--config`、`--branch`、`--timeout`。
也可以通过 `LINGORA_BRANCH=your-branch ./scripts/mock_ci.sh` 注入分支。
`--force-mode`、`--languages` 等单步参数放在各自命令后，或写入配置，不传给组合脚本。
运行前需安装固定版本 CLI 并注入 `LINGORA_API_KEY`；脚本不自动读取 `.env`。

执行顺序为：

```text
push app/src/main/res/values/strings.xml + values/arrays.xml（同一次 import）
  -> 按名称精确解析 Branch UUID
  -> pull 配置中的 TRANSLATED / REVIEWED 目标语言
  -> 在临时目录校验
  -> 原子替换 generated/lingora/res
```

任一步失败都会返回非零并停止。`app/build.gradle.kts` 将
`generated/lingora/res` 加入 Android main resource source set，因此 pull 资源和开发者维护的
`app/src/main/res` 物理分离，但构建时仍由 Android 合并。`ja` 对应 `values-ja`，`zh-CN`
对应 `values-zh-rCN`。同步成功后可执行：

```bash
./gradlew :app:installDebug
```

生成目录不位于 `app/build/`，所以 `gradle clean` 不会删除；CI 仍应在构建前执行同步：

```bash
./gradlew clean
lingora --config ci/lingora.environment-a.json pull
./gradlew :app:installDebug
```

## Branch 和参数覆盖

Branch 优先级为：命令行 `--branch`、
`LINGORA_BRANCH`、配置文件 `branch`。

```bash
lingora --config ci/lingora.environment-a.json pull \
  --branch feature/payment-copy
LINGORA_BRANCH=feature/payment-copy \
  lingora --config ci/lingora.environment-a.json pull
```

也可以分别运行：

```bash
lingora --config ci/lingora.environment-a.json push
lingora --config ci/lingora.environment-a.json pull
lingora --config ci/lingora.environment-a.json validate
```

Demo 的 `mock_ci.sh` 会先上传再下载，具有服务端写入副作用；`push` 使用配置中的
`push.forceMode`，本仓库旧模板为 `OVERRIDE`，CLI 新模板的安全默认值为 `NO_FORCE`，因此本地基础文案发生变化后可重复执行；
`push.overrideMode=RECOMMENDED` 会继续保护不可覆盖的已审核翻译，
`push.removeOtherKeys=false` 不会删除服务端额外 Key。

`push.files` 是文件列表。Demo 同时配置 `strings.xml` 和
`arrays.xml`，脚本会把它们作为重复的 multipart `files` 字段一次性提交；这样普通 string、
plural 和 `string-array` 的 Key 会一起参与同一轮冲突处理和删除范围计算。

目标语言数组按整组处理：元素数量与基础语言一致时才写入生成 XML；缺少任一元素时，pull 会
移除该目标语言的整个 `string-array`，让 Android 回退到 `values/arrays.xml`。SDK 对 Content
Delivery 数组执行相同的长度检查，避免部分数组覆盖 APK fallback 后造成下标错位。

`pull.states` 默认只包含 `TRANSLATED`、`REVIEWED`。只在 Lingora 中新增 `ja`、`zh-CN`
语言但尚未填写翻译时，导出不会生成空 XML；脚本会显示 `exported=none`。
至少一条目标语言翻译进入上述状态后，对应文件才会写入
`generated/lingora/res/values-*`。该目录由 pull 独占并被 Git 忽略，`pull.emptyDir=true`
会在校验成功后替换完整目录，避免已移除语言或翻译留下旧文件。

## 本地资源与 pull 资源

- 开发者维护的资源放在 `app/src/main/res` 并提交 Git，例如
  `values-ja/local_strings.xml`。
- Lingora 管理的资源只写入 `generated/lingora/res`，不要手工修改或提交。
- 两边可以包含不同的 Key；代码继续使用同一个 `R.string.<key>`。
- 两边不能定义相同语言下的同名 Key，否则同属 main source set，Android 资源合并会失败。
- 本地 Key 应同时在默认的 `app/src/main/res/values/strings.xml` 中提供 fallback。

## Content Delivery

配置中的 `contentDeliveries` 别名完全由使用者定义，不是 Branch 名称。示例使用 `current`：

```bash
lingora --config ci/lingora.environment-a.json delivery check --alias current
```

App 的 Delivery URL 独立于 CLI 配置，由 Gradle property 或构建环境变量注入。
优先级为 `lingoraContentDeliveryUrl` > `LINGORA_CONTENT_DELIVERY_URL` > 空 URL：

```bash
./gradlew :app:assembleDebug -PlingoraContentDeliveryUrl=https://cdn.example.com/your-prefix
LINGORA_CONTENT_DELIVERY_URL=https://cdn.example.com/your-prefix ./gradlew :app:assembleDebug
```

个人也可以写在被忽略的 `~/.gradle/gradle.properties` 中：

```properties
lingoraContentDeliveryUrl=https://cdn.example.com/your-prefix
```

Application 使用 `BuildConfig.LINGORA_CONTENT_DELIVERY_URL` 初始化 SDK，不读取 CLI 配置。
未配置时构建给出提示，Demo 使用 APK 本地资源。当前语言默认跟随系统，
`defaultLanguage = "en-US"` 是 SDK 的基础回退语言。
`demoApplicationId` 仍可改变 App ID，但不再负责选择 URL。
Gradle 正常构建不会执行 CLI，也不会上传翻译。

Branch 由服务端 Content Delivery 配置绑定；App 不接收 Branch 名称，也不包含 API Key。CI 配置中的
Branch 必须与该 APK 选择的 Content Delivery URL 所绑定的 Branch 一致。

服务端给 Android SDK 使用的 Content Delivery 必须设置为：

```text
format = ANDROID_SDK
zip = false
filterNamespace = ["app"]
filterState = ["REVIEWED"]
fileStructureTemplate = {namespace}/{languageTag}.{extension}
```

`app` 是 Demo 的默认 namespace。SDK 和探测脚本访问
`<publicUrl>/manifest.json` 与 `<publicUrl>/app/<locale>.json`。独立 AAR：

```bash
./gradlew :lingora-android-sdk:assembleRelease
```

### 动态文案测试页

Demo 首页提供 `welcome`、`welcome_name`、`local_notice`、`items_count`、`weekdays`、`en_month`
按钮。`local_notice` 只由 App 的 `values-ja/local_strings.xml` 管理，用于验证 Lingora 和 pull
都没有该 Key 时的 APK fallback。点击按钮会弹窗
显示当前 Lingora 值；在后台重新发布后，先点 `Refresh published values`，再点同一个 Key，
即可对比新旧值。刷新沿用 SDK 的条件请求和本地 fallback，不需要重新构建 APK。

Demo App 自己在 `MainActivity.kt` 中把 SDK 的 `tFlow`、`tPluralFlow`、`tArrayFlow` 收集为
Compose State。该封装属于使用方项目，不属于 SDK；其他项目可以按自己的 Compose 架构封装。
其中 `en_month` 直接读取独立 `values/arrays.xml` 中的 `R.array.en_month`，用于验证该文件也被
Android 编译并通过 CI push 到 Lingora。

SDK 的 GitHub Packages 接入和运行时流程见
[`lingora-android-sdk/README.md`](./lingora-android-sdk/README.md)。

CLI 使用 Lingora `/api/v1` 的 Branch、Import、Export 等公开接口；
Project ID 使用 UUID。命令支持独立调用及 `--json` 输出，不需要消费项目了解实现。
