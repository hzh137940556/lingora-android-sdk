# 业务项目合并后的翻译同步与构建

将 [工作流模板](android-lingora.yml) 复制到业务仓库 `.github/workflows/android-lingora.yml`。
模板假设业务仓库根目录就是 Gradle 根目录；如果保留本仓库布局，需要相应调整工作目录、
配置路径、版本文件和产物路径。此目录中的模板不在 SDK 仓库自动执行。

## 必需配置

1. 按 [SDK 接入指南](../../../sdk/README.md) 引入已发布的固定 Maven 版本，并配置
   BuildConfig、Application、网络权限和生成资源目录。若复制整个 Demo，确认其
   `lingoraSdkMode`、`lingoraSdkVersion` 使用期望的远程包，而非 local。
2. 将 Demo 的 `lingora-cli.version` 复制到业务仓库根目录。当前为 `0.1.0rc4`，属于预发布版，
   生产使用前需团队验收；开发者本机不必安装 CLI。
3. 参考 [Demo 配置](../lingora.dev.json) 创建业务仓库的 `ci/lingora.production.json`，
   修改 API 地址、Project、Branch、源文件、基础语言、namespace、目标语言和审核状态。
   `production` 只是文件名；不要直接对 Demo 配置中的真实项目执行上传测试。
4. `pull.path` 为 `../generated/lingora/res`，相对于配置文件目录；Gradle 使用
   `res.srcDir(rootProject.file("generated/lingora/res"))`，Git 忽略 `/generated/lingora/`。
   无密钥配置与版本文件提交 Git，生成资源不提交。
5. 根据必须随 APK 提供的语言及 namespace 设置 `REQUIRED_TRANSLATION_FILES`。
   模板按日语、简体中文的 app/common XML 检查；若导出格式不同，需要同步调整路径。
   校验仅保证文件存在且包含资源，不等于每个 Key 都已翻译；按产品要求另行定义覆盖率门槛。
6. 调整目标分支、JDK/Android SDK、Gradle 任务和产物路径。默认 Debug 构建用于接入验收，
   正式签名与应用发布沿用业务项目已有流程。

在业务仓库 Actions 设置中提供：

| 类型 | 名称 | 作用 |
| --- | --- | --- |
| Secret | `LINGORA_API_KEY` | 对目标项目有上传、下载权限的 API Key |
| Secret | `LINGORA_MAVEN_TOKEN` | 对 SDK 包有读取权限的 PAT |
| Variable | `LINGORA_MAVEN_USER` | PAT 所属用户名 |
| Variable | `LINGORA_CONTENT_DELIVERY_URL` | 与 CLI 项目、分支和 namespace 对应的公开 URL |

模板使用独立 Maven 凭据变量，业务项目的仓库配置需要配套修改：

```kotlin
credentials {
    username = providers.gradleProperty("gpr.user")
        .orElse(providers.environmentVariable("LINGORA_MAVEN_USER"))
        .orNull
    password = providers.gradleProperty("gpr.key")
        .orElse(providers.environmentVariable("LINGORA_MAVEN_TOKEN"))
        .orNull
}
```

否则 Demo 原来的 `GITHUB_ACTOR/GITHUB_TOKEN` 读取逻辑不会使用模板凭据。
两个 Secret 都不进入 APK；公开 URL 显式通过 `-P` 传入，覆盖 Demo 默认开发地址。

## 执行与失败边界

- `main` 的 push 触发工作流，包括 PR 合并与直接推送。业务仓库要求 main 只能通过 PR 合并，
  即可用于合并后的同步。普通 PR 校验不使用该写入流程；合并后任务不能作为合并前门禁。
- `validate → push → pull → validate → 必需语言文件检查 → 构建` 在同一工作区串行执行，
  任一步失败就停止。validate 检查本地导出资源，空目录也可能成功，不能将上传前的 validate 当作源文案或凭据验收；必须保留后面的产出检查。
- 成功的 push 不会因后续失败自动回滚；重试前确认覆盖策略。当前示例 `KEEP` 保留已有非空值，
  并不自动同步已修改的英语文案。`TRANSLATED + REVIEWED` 允许未审核翻译，生产策略需要团队确认。
- 不恢复或缓存 `generated/`；Hosted Runner 从干净检出开始，CLI `emptyDir=true` 替换生成目录。
  并行环境使用独立工作区与各自配置，避免资源混用。
- 同一翻译目标的其他工作流应使用同一 concurrency group。默认只保留一个等待任务，
  新任务可能替代旧等待任务，不保证每个合并都产出 APK。上传前拒绝落后于分支的提交，
  不要绕过检查重跑旧提交覆盖新资源。
- push 不执行自动翻译或发布 Content Delivery。URL 与 CLI Branch 的对应关系需在平台确认，
  不能只凭 URL 可访问认定环境匹配。

先在专用测试项目跑通一次真实 push/pull、远程 Maven 下载与 APK 安装，再用于生产。
本模板不发布 SDK、不创建版本 Tag，也不会自动上传生产应用。

## 当前 Demo 的对应实现

本仓库已另行配置 [Demo 开发环境工作流](../../../.github/workflows/android-demo-translations.yml)，
使用 `demo` 工作目录、`ci/lingora.dev.json` 和 develop 触发分支。checkout 只包含 Demo，
上传 APK 的路径仍相对于仓库根目录，必须写成 `demo/app/build/outputs/apk/debug/*.apk`。
同仓库 SDK 包使用带 `packages: read` 的 `GITHUB_TOKEN`；本模板面向其他业务仓库，
保留独立 Maven 凭据。配置及验收步骤见 [Demo 文档](../../README.md#5-本仓库的真实-ci-同步)。
