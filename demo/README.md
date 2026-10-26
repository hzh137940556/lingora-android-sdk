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

按 `validate -> push -> pull` 上传并下载：

```bash
make mock_ci
```

也可以只执行某一步：

```bash
make cli-validate
make cli-push
make cli-pull
```

- `push` 分别把 `values/app.xml` 和 `values/common.xml` 上传到对应 namespace。
- `forceMode=KEEP` 保留服务端已有的非空值，只补充缺失 key 或空值，不删除其他 key。
- `pull` 只下载 `app`、`common` 中状态为 `TRANSLATED` 或 `REVIEWED` 的非空目标翻译。
- 输出写入 `generated/lingora/res`；该目录已加入 App 的 main resource source set，不提交 Git。

CLI 只负责同步资源，不会自动翻译，也不会在普通 Gradle 构建中自动运行。

`mock_ci` 即使通过 `make -j` 调用也会串行执行，任一步失败就停止。
`KEEP` 不覆盖服务端已有非空英语文案，因此本地修改已有文案不等于服务端会同步替换；
该策略保持不变，需要更新既有值时先确定团队的冲突处理规则。
业务项目可复制 [合并后同步与构建模板](ci/examples/README.md)，不必让开发者本地安装 CLI。

## 3. App 前端使用

Demo 的 `gradle.properties` 已提供默认开发环境的公开 Content Delivery URL。
需要切换时可以修改配置或通过命令行 `-P` 覆盖：

```properties
lingoraContentDeliveryUrl=https://example.com/lingora/localstrings/dev/v1
```

构建脚本优先读取 Gradle property `lingoraContentDeliveryUrl`，仅在该 property 不存在时
才读取环境变量 `LINGORA_CONTENT_DELIVERY_URL`。由于 Demo 已有默认 property，**单独设置
环境变量不会覆盖开发地址**；命令行 `-P` 可以覆盖配置文件中的值。

### 业务 CI 显式选择地址

在业务 CI 中配置环境变量，再明确将其传给 Gradle。以下步骤放在 JDK、Android SDK、
Maven 凭据及翻译同步已完成之后；在本仓库运行时工作目录为 `demo`，业务项目通常为仓库根目录：

```yaml
- name: Build Android App
  working-directory: demo # 业务项目按实际 Gradle 根目录调整
  env:
    LINGORA_CONTENT_DELIVERY_URL: ${{ vars.LINGORA_CONTENT_DELIVERY_URL }}
  shell: bash
  run: |
    set -euo pipefail
    delivery_url="${LINGORA_CONTENT_DELIVERY_URL:-}"
    if [[ -z "${delivery_url//[[:space:]]/}" ]]; then
      echo "Missing LINGORA_CONTENT_DELIVERY_URL; refusing to build with the default development URL."
      exit 1
    fi
    ./gradlew :app:assembleDebug \
      "-PlingoraContentDeliveryUrl=$delivery_url" \
      --no-daemon
```

变量缺失、为空或只有空白时，该步骤在 Gradle 启动前失败。实际业务项目替换为已有 flavor、
测试及签名打包任务，并保留同样的 `-P` 参数；这里不改变 SDK 依赖来源。
选择的地址必须与 CLI 同步所用 Project、Branch 和 namespace 对应。URL 是公开地址，
不要把 `LINGORA_API_KEY` 或 Maven Token 传给这个字段。

该示例供业务 CI 使用，不改变本仓库普通 Demo CI 的默认环境。未配置地址或远端不可用时，
SDK 使用 APK 内置文案；如需本地测试空地址，可显式传 `-PlingoraContentDeliveryUrl=`。

### 初始化与页面调用

App 在 [LingoraDemoApplication.kt](app/src/main/java/com/example/myapplication/LingoraDemoApplication.kt)
初始化 SDK。Compose 页面使用响应式 API：

```kotlin
val context = LocalContext.current
val text by Lingora.instance
    .tFlow(context, R.string.welcome)
    .collectAsState(initial = context.getString(R.string.welcome))

Text(text)

val commonText by Lingora.instance
    .tFlow(context, R.string.common_notice, namespace = "common")
    .collectAsState(initial = context.getString(R.string.common_notice))
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

## 4. 验证

`make test-cli` 使用临时命令替身检查并行 Make 的顺序、失败中止和固定 CLI 版本，不访问服务端。
`make verify` 先运行这些测试，再以 local SDK 模式执行 App 单元测试、Debug 构建和设备测试 APK 编译。
普通 Demo CI 因此也会检查设备测试能否编译，但不会自动启动设备测试。

设备测试通过 `DemoTestRunner` 使用空 Delivery URL 的测试 Application，不受开发环境文案变化影响。
原来的手工日语资源已移除，测试改为验证未提供语言的默认 APK 文案。设备可用时执行：

```bash
./gradlew :app:connectedDebugAndroidTest -PlingoraSdkMode=local
```

上述离线测试不代替真实 CLI push/pull、Maven 下载和生产配置验收。

## 5. 本仓库的真实 CI 同步

[Android Demo translations and build](../.github/workflows/android-demo-translations.yml)
在 `develop` 更新后执行：固定版本 CLI 安装 → validate → push → pull → validate →
四个必需 XML 检查 → 远程 Maven SDK 构建 → 上传 Debug APK。普通 PR 检查不上传文案。

仓库管理员需要在 Settings → Secrets and variables → Actions 配置：

- Secret `LINGORA_API_KEY`：可读写 `ci/lingora.dev.json` 中开发项目及 develop 分支的密钥。
- Variable `LINGORA_CONTENT_DELIVERY_URL`：当前 Demo 开发环境的公开 Delivery URL，必须与该翻译配置对应。

工作流只检出 `demo/`，使用配置文件中的固定 `lingoraSdkVersion`，并显式选择 dev Maven 包；
不存在本地 SDK 源码回退。同仓库包使用 `packages: read` 的 `GITHUB_TOKEN`，无需额外 Maven PAT。
跨仓库业务接入仍需按 [消费项目模板](ci/examples/README.md) 配置独立包读取凭据。

CLI 仅 push 英语 app/common 源文件，pull 日语与简体中文的两个 namespace；KEEP 策略不变。
生成资源只存在于 Runner，APK 从成功运行的 Actions Artifacts 下载，保留 7 天。
首次通过审核合入 develop 后自动验收；工作流进入默认分支后也可手动选择 develop 运行。
手动运行其他分支会跳过，历史提交落后于分支最新提交时会拒绝上传。

验收需确认同步步骤、四个非空资源文件检查和 Maven 构建均成功，再安装产出的 APK 检查文案。
自动构建不代表已完成设备安装或运行时 Delivery 发布验证。密钥缺失时工作流会在上传前失败。

本 Demo 的独立验收项目为 `Lingora Android Demo CI`
（Project ID：`711537ef-ce6e-442f-b92c-dbade5f090c2`），翻译分支为 `develop`，
基础语言 `en-US`，目标语言 `ja`、`zh-CN`，namespace 为 `app`、`common`。
公开交付配置 `Android Demo CI Develop` 使用 Android SDK JSON，文件结构为
`{namespace}/{languageTag}.{extension}`，URL 为：

```text
https://cosly-dev.s3.us-west-2.amazonaws.com/lingora/localstrings/android-demo-ci/develop/v1
```

已录入 `app/welcome` 和 `common/common_notice` 的中日验收译文；其他源词条由 CI push 导入，
后续在平台补充目标语言翻译。当前非空文件检查不代表全量翻译覆盖。
Content Delivery 使用手工发布：CLI push/pull 成功不会自动刷新该公开地址。
