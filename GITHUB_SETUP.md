# 仓库配置与发布步骤

仓库：[hzh137940556/lingora-android-sdk](https://github.com/hzh137940556/lingora-android-sdk)。
下面是所需配置清单，不代表平台设置已自动完成；本次目录拆分不修改 GitHub Settings。

## 1. 确认仓库与 Actions

在 [Settings](https://github.com/hzh137940556/lingora-android-sdk/settings) 确认仓库可见性，
在 [Actions 设置](https://github.com/hzh137940556/lingora-android-sdk/settings/actions) 启用工作流。
保持默认 Token 只读，不需要全局打开写权限。

本仓库使用 GitHub Packages，不是 PyPI。GitHub Actions 自动提供每个 Job 的 `GITHUB_TOKEN`，
不需要手动保存同名 Secret，也不需要为 SDK 发包配置长期 PAT。
只有 SDK publish Job 申请 `packages: write`，独立 Demo 调度 Job 申请 `actions: write`；
PR 校验不具有这些写权限。

若将仓库改为私有，先核对套餐是否支持所需 Environment 审批，不要通过移除门禁解决。
参考：[部署环境与保护规则](https://docs.github.com/en/actions/reference/workflows-and-actions/deployments-and-environments)。

## 2. 配置分支保护

让新工作流至少成功运行一次，再到
[Rulesets](https://github.com/hzh137940556/lingora-android-sdk/settings/rules) 为 main、develop 设置规则：

1. 要求通过 PR 合并，保留原有审核策略；审核人数由所有者确认。
2. 必需检查设置为 **`Android SDK CI`**，不要使用内部标识 verify。
   若旧规则要求 `Test and build SDK and Demo`，需替换成新名称，避免一直等待 Expected。
3. 不要要求 publish、Demo smoke 或 Publish Android SDK；发布只在 Tag 上执行。
4. 禁止强推、删除受保护分支，不配置管理员绕过。

Demo CI 独立展示结果；SDK 包不因 Demo 编译或配置而无法发布。
是否把普通 Demo 检查加入 PR 规则由所有者另行决定。

## 3. 保护版本 Tag

创建 Active 的 Tag ruleset，模式 `android-sdk-*`，同时覆盖两渠道：

- 启用 Restrict updates 和 Restrict deletions。
- 不限制创建新 Tag；不配置绕过。
- 已发布 Tag 不删除、不移动，已发布版本不覆盖。

## 4. 配置人工审批

在 [Environments](https://github.com/hzh137940556/lingora-android-sdk/settings/environments)
创建两个 Environment，名称与发布脚本导出的环境名一致：

| 渠道 | Environment | 唯一允许的 Tag 模式 |
| --- | --- | --- |
| dev | `android-sdk-dev` | `android-sdk-dev-*` |
| release | `android-sdk-release` | `android-sdk-[0-9]*` |

这两个模式是通配规则，不是正则表达式；版本格式仍由发布脚本严格校验为 `X.Y.Z`。
Tag 保护 Ruleset 的 `android-sdk-*` 继续覆盖两渠道，但 Environment 不得沿用这个宽泛模式。
两个环境分别配置以下相同的保护：

1. Required reviewers **仅添加 hzh137940556**。
2. 不勾 Prevent self-review，允许自己批准发布。
3. 关闭 Allow administrators to bypass configured protection rules。
4. Deployment branches and tags 选 Selected branches and tags。
5. 各自只添加表中的一条 **Tag** 规则，不是 Branch，不额外放行另一渠道。
6. 不需要额外 Environment Secret。

`sdk/scripts/sdk_release.py` 在审批前和审批后检查当前渠道的真实配置，缺少或放宽任何门禁都停止。
只有配置校验通过后，工作流才使用导出的环境名进入审批；不依赖 GitHub 自动创建环境，
也不允许在环境缺失时回退到另一渠道。Summary 会记录当前审批环境。
更换审批人必须同步更新脚本、测试与文档，不能只修改网页。

审批弹窗会分别显示 `android-sdk-dev` 或 `android-sdk-release`；渠道仍由已验证 Tag 决定。
从旧的共用环境迁移时，先完成正在执行的旧发布，再切换环境规则并合并配套代码；
已有 Tag 固定的旧工作流不会自动切换环境，不移动 Tag，后续验证使用新的版本。

## 5. 配置本机与 Demo

安装 Java 17、Android SDK 36、Python 3 和 Make。配置 `ANDROID_HOME`，或分别写入
被忽略的 `sdk/local.properties`、`demo/local.properties`，不能依赖旧根目录的配置。
Android Studio 分别打开 sdk/ 或 demo/。

Demo 的 `demo/gradle.properties` 配置 `lingoraSdkMode=local|dev|release`；
远端模式必须指定 `lingoraSdkVersion=X.Y.Z`。首次真实包下载验收前默认保留 local，
不要把“Tag 已存在”当作“版本可下载”。见 [Demo 接入说明](demo/README.md)。

远端下载仓库：

```text
https://maven.pkg.github.com/hzh137940556/lingora-android-sdk
```

在个人 `~/.gradle/gradle.properties` 中填写，不提交：

```properties
gpr.user=<GitHub 用户名>
gpr.key=<具有 read:packages 权限的 classic PAT>
```

包首次发布后，在 [Packages](https://github.com/hzh137940556?tab=packages&repo_name=lingora-android-sdk)
确认包关联仓库和消费账号/仓库的读取权限。其他仓库的 GITHUB_TOKEN 不会天然拥有访问权。
不要同时配置旧 Maven 仓库以免读取到旧来源。

Delivery URL 只在 Demo 注入：

```bash
cd demo
./gradlew :app:assembleDebug -PlingoraContentDeliveryUrl=https://cosly-dev.s3.us-west-2.amazonaws.com/lingora/localstrings/dev/v1
```

无 URL 时仍可构建并使用 APK fallback。CLI 配置、固定版本和生成资源均属于 demo/。
API Key 只通过 `LINGORA_API_KEY` 环境/Secret 注入；正常 SDK/Demo Gradle CI 不调用 push/pull。
`make -C demo mock_ci` 会写服务端，只能在明确授权的项目上运行。

## 6. 发布测试包，再发布正式包

以下命令会推送 Tag，只在代码已合并、工作区干净且决定发布时执行。
根 Makefile 会转发到 sdk/；SDK verify 不加载 Demo：

```bash
# 在已同步的 develop 上，使用尚未发布的数字版本
make publish-dev VERSION=X.Y.Z
# 在已同步的 main 上
make publish VERSION=X.Y.Z
```

| 渠道 | 来源 | Tag | Maven 坐标 |
| --- | --- | --- | --- |
| dev | develop | android-sdk-dev-X.Y.Z | io.lingora:lingora-android-dev:X.Y.Z |
| release | main | android-sdk-X.Y.Z | io.lingora:lingora-android:X.Y.Z |

在 Actions 等 Android SDK CI 通过，核对渠道、坐标与 SHA，再点击 Review deployments →
Approve and deploy。包上传成功后 Summary 显示上传地址、Tag 和 SHA。
审批页没有按钮时，先看 verify 是否失败、Job 是否真正进入 waiting，以及 Environment 配置。
两包独立管理版本，但包含同样的类，使用者必须二选一。

## 7. 启用独立 Demo 验收

`android-demo-ci.yml` 需要先通过正常 PR 流程进入**默认分支**，GitHub 才允许 workflow_dispatch。
只进入 develop 而未进入默认 main 时，SDK 可以发布，但调度会警告 Demo smoke 尚未触发；
不要为通过调度而绕过 PR 合并。

- 普通 PR/分支 CI：构建 local，并用独立 producer 生成 dev/release 本地 Maven fixture；
  consumer 只检出 demo，不存在 SDK 源码。这验证 Maven 隔离，不代表真实远端下载成功。
- 发布成功后：独立调度 Job 用渠道、版本、已验证 SHA 请求真实下载 smoke，无长期 PAT。
- 如自动调度未成功或 Maven 下载暂时失败，在
  [Android Demo CI](https://github.com/hzh137940556/lingora-android-sdk/actions/workflows/android-demo-ci.yml)
  点 Run workflow，选择含工作流的来源分支，填写 dev/release、固定 X.Y.Z 和完整 40 位 SHA。
- 真实 smoke 只检出该 SHA 的 Demo，使用 GITHUB_TOKEN 下载包并构建。
  需要包已发布、关联仓库且允许该仓库 Actions 读取。
- SDK 上传和 Demo smoke 分别看结果；Demo 失败不能覆盖已发布包，修复用新版本。
  首次下载验收后，再用普通 PR 固定 Demo 默认 Maven 渠道和已确认版本。

## 8. 回退边界

目录拆分不改 SDK 公共 API 或已有发布记录。不复制凭据、本机配置、缓存与生成物。
未发布前可以通过 revert 普通提交撤回结构变更；发布后不改 Tag 或覆盖包，
修复只能递增版本。真实 Delivery、设备行为与包下载验收需单独确认，不能用编译通过代替。
