# 项目开发规范

本文件适用于整个 Lingora Android SDK 与 Demo 仓库。

## 通用原则

- 默认使用中文说明和文档；代码标识符、命令、库名与协议字段保持原文。
- 先阅读现有代码、测试和配置，再进行最小范围修改，不删除用户已有改动。
- 优先复用现有实现、标准库和已有依赖，非必要不新增抽象或依赖。
- 禁止提交 Secret、Token、密码、本机配置、签名文件、生成物和缓存。
- 新增源码文件时，在文件顶部添加简洁的中文模块职责注释；新增类、函数或方法时，在声明上方添加简洁的中文职责注释。

## 模块边界

```text
app -> lingora-android-sdk
lingora-android-sdk -> Android/Kotlin runtime dependencies
scripts -> public HTTP/file boundaries
```

- `app` 是示例应用，可以依赖 SDK；SDK 不得依赖 App、App package 或 Compose UI。
- SDK 保持 UI 框架无关，只暴露 Android/Kotlin 类型和明确的异步状态边界。
- `scripts/` 是独立构建工具，不导入 App 或 SDK 的运行时代码。
- 翻译 CLI 在 Lingora 主仓库实现并独立发布；本仓库只安装固定版本并调用 `lingora`，不复制或导入实现。
- `scripts/mock_ci.sh` 是本仓库的 Demo 编排入口，只串联已安装 CLI 的 `push`、`pull`，上传失败不得继续下载；不依赖 `lingora mock-ci` 或旧翻译脚本。
- CLI 首版发布并完成安装验收前暂留旧翻译脚本，之后再清理；`sdk_release.py` 等 Maven 发布工具必须保留。
- App 通过 `lingoraContentDeliveryUrl` Gradle property 或 `LINGORA_CONTENT_DELIVERY_URL` 构建环境变量配置公开 URL，前者优先；不读取 CLI 配置，正常 Gradle 构建不执行 CLI。
- 开发者维护的资源保留在 App 源码目录；同步生成的资源保留在被忽略的 `generated/`，不手工修改或提交。
- API Key 只允许通过 CI Secret 注入，不写入版本化配置；App 运行时只使用公开的 Content Delivery URL。
- SDK 两个发布渠道使用相同源码与 Kotlin package：`develop` 的 `android-sdk-dev-X.Y.Z` 发布
  `io.lingora:lingora-android-dev:X.Y.Z`；`main` 的 `android-sdk-X.Y.Z` 发布
  `io.lingora:lingora-android:X.Y.Z`。版本映射统一维护在 `scripts/sdk_release.py`。
- 发布必须先通过 `verify` 检查，再经过 `android-sdk-release` Environment 人工批准；
  不允许缺少审批配置、覆盖包版本、移动版本 Tag，或把发布 Job 设为分支必需检查。
- 发布环境仅配置 `hzh137940556` 为审批人，允许自审，禁止管理员绕过，且只允许 `android-sdk-*` Tag。
- Demo 的 SDK 依赖统一在 `app/build.gradle.kts` 中切换，本地 module、正式 Maven 包和测试
  Maven 包只能启用其中一项，不使用额外的依赖替换脚本或开关。
- 发布后 CI 只修改临时工作区中启用的 SDK 依赖，让 Demo 消费刚上传的 Maven 包并构建；
  默认仍使用本地 module，消费验收失败不回滚或覆盖已经发布的版本。

## 命名与测试

- Kotlin 类和接口使用 `PascalCase`，函数和变量使用 `camelCase`，常量使用 `UPPER_SNAKE_CASE`；package 使用小写分段名称。
- App 单元测试放在 `app/src/test/`，设备测试放在 `app/src/androidTest/`，SDK 单元测试放在 `lingora-android-sdk/src/test/`。
- SDK 变更优先使用 JVM 单元测试覆盖纯逻辑；依赖 Android 运行时或 UI 的行为再使用 Android 测试。
- 没有可用设备或模拟器时不运行 `connectedAndroidTest`，并明确说明设备测试未验证。

## 标准命令

```bash
make test-cli
make test-release
make verify
./gradlew :lingora-android-sdk:test
./gradlew :app:testDebugUnitTest
./gradlew :app:assembleDebug
./gradlew :lingora-android-sdk:assembleRelease
```

维护者命令：`make publish-dev VERSION=X.Y.Z`（develop）与 `make publish VERSION=X.Y.Z`（main）。
发布命令会创建并推送 Tag、触发远端 CI；仅在明确要求发版时执行。仅验证 Maven 产物时使用
`make publish-local VERSION=X.Y.Z SDK_CHANNEL=dev|release`，不会推送 Tag 或远端包。

修改后运行最小充分验证并检查实际 diff；无法运行的检查需要说明原因和风险。
