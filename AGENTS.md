# 项目开发规范

本仓库包含两个独立 Gradle build：`sdk/` 发布 SDK，`demo/` 消费 SDK。

## 通用原则

- 默认使用中文说明，代码标识符、命令和协议字段保持原文。
- 先读现有实现和测试，做最小范围修改，不删除用户现有改动。
- 禁止提交 Secret、Token、本机配置、签名、缓存和生成物。
- 新增源码文件、类、函数和方法必须有简洁中文职责注释，职责变化时同步更新。
- 两个 build 分别维护 settings、wrapper、插件、依赖和构建输入；不得相互读取内部 Gradle 配置。
- 不更改现有 Java 17、Gradle 8.13、AGP 8.13.2、Kotlin 2.0.21 工具链，除非任务明确要求。

## SDK 边界

- `sdk/settings.gradle.kts` 只包含 `:lingora-android-sdk`，不包含 App 或 Demo。
- SDK 不依赖 App package、Compose、CLI、Delivery 环境值或 Demo 配置。
- 保持公共 API 与 Kotlin package `io.lingora.android` 兼容；公开协程类型需要 `api` 依赖。
- 多 namespace API 必须保留旧 JVM 方法签名；文档、缓存和失败状态按 `(localeTag, namespace)` 隔离。
- `sdk/scripts/sdk_release.py` 统一管理版本、渠道、分支、Tag 和 Maven 坐标；不得改写或构建 Demo。
- develop 的 `android-sdk-dev-X.Y.Z` -> `io.lingora:lingora-android-dev:X.Y.Z`。
- main 的 `android-sdk-X.Y.Z` -> `io.lingora:lingora-android:X.Y.Z`。
- 发布要求整个 Git 工作区干净并与来源分支同步；SDK 本地 verify 不调用根目录聚合 verify。
- 版本 Tag 先通过 `Android SDK CI`，再进入对应渠道的审批环境：dev 为 `android-sdk-dev`，release 为 `android-sdk-release`。
- 两个环境审批人均仅 `hzh137940556`，允许自审，禁止管理员绕过；各自只配置一条 Tag 规则，dev 为 `android-sdk-dev-*`，release 为 `android-sdk-[0-9]*`。
- 审批前后均校验当前渠道环境；配置通过后才导出环境名供工作流选择，缺失、宽泛或跨渠道规则必须停止，不回退到其他环境。
- 不允许审批缺失、重复版本、移动/删除版本 Tag；仅 publish Job 拥有包写权限。
- `check_publication.py` 验证 AAR/POM/metadata/sources、坐标、版本、校验和、API 依赖及无 App 类。

## Demo 边界

- `demo/settings.gradle.kts` 只包含 `:app`。通过 `lingoraSdkMode=local|dev|release` 选择 SDK。
- App 只声明一条 Maven dependency；local 模式用 Composite Build 显式替换成独立 SDK。
- dev/release 必须指定固定 `lingoraSdkVersion=X.Y.Z`，不得使用动态版本或自动回退源码。
- 只有 local 模式读取 `lingoraLocalSdkPath`（默认 ../sdk）；远端模式不检查该目录。
- 两个 Maven 包含相同的类，必须二选一，不修改 Kotlin import。
- App 独立配置 App ID、当前语言、公开 Delivery URL，不写入 API Key。
- Delivery URL 优先级为 Gradle property `lingoraContentDeliveryUrl` > 环境变量 `LINGORA_CONTENT_DELIVERY_URL`；缺失时保留 APK fallback。
- CLI 来自 Lingora 主仓库，本仓库只安装 `demo/lingora-cli.version` 固定版本并调用公开命令。
- `demo/Makefile` 的 `mock_ci` 目标通过 `uvx` 调用 `demo/lingora-cli.version` 固定版本的 validate/push/pull，失败立即退出；正常 Gradle 构建不运行 CLI。
- 本仓库不保留 CLI 的 push、pull、validate 或 delivery 实现副本；只通过固定版本的 PyPI 包调用公开命令，不误删 Maven 发布工具。
- CLI 配置位于 Demo，资源相对配置文件；生成资源仅写 `demo/generated/` 并忽略，API Key 只通过环境/Secret。
- Demo CI 独立于 SDK 发包：普通 CI 验证 local 和两个 Maven fixture；dispatch 验证真实已发布包。
- fixture init script 只用于测试，不用于正常接入。真实包 smoke 不加载 SDK 源码、不改写依赖文件。
- 调度 Job 仅 actions:write；dispatch 失败必须提示尚未验收，不能伪称下载成功或回滚已上传包。

## 标准命令与验证

仓库根目录只转发命令：

```bash
make verify-sdk
make verify-demo
make verify
make test-release
```

独立工程命令：

```bash
make -C sdk verify VERSION=0.1.0 SDK_CHANNEL=dev
make -C sdk verify VERSION=0.1.0 SDK_CHANNEL=release
cd demo
make mock_ci
./gradlew :app:assembleDebug -PlingoraSdkMode=local
./gradlew :app:assembleDebug -PlingoraSdkMode=dev -PlingoraSdkVersion=0.1.0
```

仅明确要求发版时执行根目录 `make publish-dev VERSION=X.Y.Z` / `make publish VERSION=X.Y.Z`。
这会推送 Tag 并触发真实审批。验证产物使用 sdk verify，产物仅写入 sdk/build/verified-maven。
已有版本不可覆盖，消费验收失败也必须用新版本修复。

修改后运行目标测试、必要构建与 git diff --check。结构变化同时更新文档和本文件。
无可用设备时不运行 connectedAndroidTest，并明确说明设备测试、真实发布或 Delivery 未验证。
