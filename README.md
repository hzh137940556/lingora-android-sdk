# Lingora Android SDK

SDK 与 Demo 在同一个 Git 仓库内，使用两个独立 Gradle build。Android Studio 分别打开
`sdk/` 或 `demo/`；根目录不再是 Gradle 工程。

```text
sdk/                     SDK 的 settings、wrapper、依赖、源码、测试与 Maven 发布
  lingora-android-sdk/    :lingora-android-sdk，不依赖 Demo 或 Compose
  scripts/               发布门禁与 Maven 产物校验
demo/                    Demo 的 settings、wrapper、App、CLI 配置与生成资源
  app/                   :app，只消费 SDK 的公共 API
  ci/                    固定版本 CLI 的无 Secret 配置
Makefile                 仅转发常用命令
```

## 开发与依赖切换

需要 Java 17、Android SDK 36。配置 `ANDROID_HOME`，或分别在 `sdk/local.properties`、
`demo/local.properties` 填写 SDK 路径；本机配置不提交。

在 `demo/gradle.properties` 统一切换，或用 `-P` 临时覆盖：

| 模式 | 配置 | 来源 |
| --- | --- | --- |
| 本地源码 | `lingoraSdkMode=local` | `../sdk`，可用 `lingoraLocalSdkPath` 覆盖 |
| 测试包（当前默认） | `lingoraSdkMode=dev` 和 `lingoraSdkVersion=0.1.4` | `io.lingora:lingora-android-dev:0.1.4` |
| 正式包 | `lingoraSdkMode=release` 和 `lingoraSdkVersion=X.Y.Z` | `io.lingora:lingora-android:X.Y.Z` |

App 只有一条 dependency 声明，local 使用 Gradle Composite Build 替换；远端模式完全不加载
SDK 工程，不存在失败后偷偷回退本地的行为。默认 Maven 模式需要读取凭据；
源码联调或无远端凭据时显式选择 local。版本以 `demo/gradle.properties` 为准，
配置版本不等于本机已经完成下载验收。

```bash
make verify-sdk
make verify-demo
cd demo
./gradlew :app:assembleDebug -PlingoraSdkMode=dev -PlingoraSdkVersion=0.1.0
```

示例版本不代表已发布。远端下载需要 GitHub Packages 凭据，见 [SDK 接入指南](sdk/README.md)。

## CI 与发布

- `Android SDK CI` 只测试 SDK 和 Maven 产物，不加载 Demo。两渠道使用独立审批环境，审批保护、Tag 及 Maven 坐标保持不变。
- `Android Demo CI` 单独验证 local、dev/release Maven fixture；真实包发布后另行请求独立下载验收。
- Demo 下载验收失败不改变“SDK 包已上传”的事实，也不覆盖已发布版本。
- 业务项目合并后的翻译同步与构建可使用 [消费项目 CI 模板](demo/ci/examples/README.md)，
  开发者本地无需安装 CLI。当前 Demo 另有 [开发环境同步工作流](.github/workflows/android-demo-translations.yml)，
  在 develop 更新后同步文案并消费远程 SDK 构建 APK，需先配置翻译 Secret 和公开 URL 变量。
- 发布与权限步骤见 [GitHub 配置清单](GITHUB_SETUP.md)；Demo、Delivery 与 CLI 见 [Demo 文档](demo/README.md)。

## 从旧布局迁移

旧 SDK 公共 API 与 Kotlin package 保持兼容；多 namespace 通过新重载显式启用。个人配置需要手动迁移，避免覆盖已有值：

1. Android Studio 重新打开 `demo/` 或 `sdk/`，旧根目录 Gradle 配置不再使用。
2. 原根目录 `.lingorarc` 放到 `demo/.lingorarc`，检查资源路径；原 `scripts/` 配置放到
   `demo/scripts/`。均不提交 Git。根目录配置通常使用 `app/...` 与 `generated/...`；
   `scripts/` 或 `ci/` 内的配置通常使用 `../app/...` 与 `../generated/...`。
3. 原生成资源可重新 pull 到 `demo/generated/lingora/res`；不要提交旧生成物。
4. 不再从根目录调用 `./gradlew`。根目录 Make 命令仍可用；SDK 发布命令只调用 SDK 自身 verify。

本次拆分不更改 GitHub Settings、不自动发布版本，也不修改已有 Tag。未发布前可用普通 revert
回滚；发布后只能修复并递增版本，不移动 Tag 或覆盖 Maven 包。
