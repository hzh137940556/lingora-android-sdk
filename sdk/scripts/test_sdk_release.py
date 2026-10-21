# 验证 SDK 渠道映射、Git 来源与审批门禁，远端访问均使用模拟响应。
from __future__ import annotations

import contextlib
import copy
import io
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import Mock, patch
from urllib.error import HTTPError, URLError

import sdk_release as sdk


# 覆盖标签与发布坐标的双向映射及不合法输入。
class ReleaseTests(unittest.TestCase):
    # 两渠道分别绑定来源、坐标和审批环境，不从实现常量生成预期规则。
    def test_channels_have_distinct_coordinates(self) -> None:
        for channel, branch, artifact, tag, environment, pattern in (
            ("dev", "develop", "lingora-android-dev", "android-sdk-dev-0.1.0",
             "android-sdk-dev", "android-sdk-dev-*"),
            ("release", "main", "lingora-android", "android-sdk-0.1.0",
             "android-sdk-release", "android-sdk-[0-9]*"),
        ):
            with self.subTest(channel=channel):
                release = sdk.Release(channel, "0.1.0")
                self.assertEqual((release.branch, release.artifact_id, release.tag), (branch, artifact, tag))
                self.assertEqual(release.coordinate, f"io.lingora:{artifact}:0.1.0")
                self.assertEqual(release.environment, environment)
                self.assertEqual(release.tag_pattern, pattern)
                self.assertEqual(sdk.parse_tag(tag), release)

    # 禁止旧标签格式、命令字符、前导零以及未约定的版本后缀。
    def test_invalid_tags_are_rejected(self) -> None:
        for tag in (
            "android-sdk-v0.1.0", "android-sdk-0.1", "android-sdk-dev-0.1.0-dev.1",
            "android-sdk-01.2.3", "android-sdk-0.1.0\n", "android-sdk-0.1.0;id",
            "other-sdk-0.1.0", "android-sdk-0.1.0-SNAPSHOT",
        ):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                sdk.parse_tag(tag)

    # 版本和渠道都在产生副作用之前校验。
    def test_invalid_version_and_channel_are_rejected(self) -> None:
        for version in ("", "1.0", "1.0.0-dev", "-1.0.0", "1.00.0", "1.0.0\n"):
            with self.subTest(version=version), self.assertRaises(ValueError):
                sdk.Release("release", version)
        with self.assertRaises(ValueError):
            sdk.Release("production", "0.1.0")

    # 审批前摘要显著标识两个渠道与版本，CI 输出仍保留坐标和构建 SHA。
    def test_report_records_coordinate_and_sha(self) -> None:
        for channel in ("dev", "release"):
            with self.subTest(channel=channel), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "outputs"
                summary = Path(directory) / "summary"
                release = sdk.Release(channel, "0.1.0")
                with patch.dict(sdk.os.environ, {"GITHUB_STEP_SUMMARY": str(summary)}):
                    with contextlib.redirect_stdout(io.StringIO()):
                        sdk.report(release, "abc123", output)
                self.assertIn("sha=abc123\n", output.read_text())
                self.assertIn(f"environment=android-sdk-{channel}\n", output.read_text())
                self.assertIn(release.coordinate, summary.read_text())
                self.assertIn(f"### SDK 发布信息 [{channel}] — 0.1.0", summary.read_text())




# 使用隔离 Git 仓库验证真实提交关系，不访问工作仓库或远端。
class SourceTests(unittest.TestCase):
    # 构造 main、develop 和未合并提交，远端引用保存在本机临时仓库中。
    def setUp(self) -> None:
        directory = tempfile.TemporaryDirectory()
        self.addCleanup(directory.cleanup)
        self.root = Path(directory.name)
        self.addCleanup(patch.stopall)
        patch.object(sdk, "ROOT", self.root).start()
        self.run_git("init", "--initial-branch=main")
        self.run_git("config", "user.name", "Release Test")
        self.run_git("config", "user.email", "release-test@example.invalid")
        self.run_git("commit", "--allow-empty", "-m", "base")
        self.base_sha = self.run_git("rev-parse", "HEAD")
        self.run_git("update-ref", "refs/remotes/origin/main", self.base_sha)
        self.run_git("checkout", "-b", "develop")
        self.run_git("commit", "--allow-empty", "-m", "development")
        self.dev_sha = self.run_git("rev-parse", "HEAD")
        self.run_git("update-ref", "refs/remotes/origin/develop", self.dev_sha)

    # 所有 Git 读写只作用于测试创建的临时目录。
    def run_git(self, *args: str) -> str:
        return subprocess.check_output(
            ["git", *args], cwd=self.root, text=True, stderr=subprocess.DEVNULL,
        ).strip()

    # develop 上尚未合入 main 的提交不能作为正式版本发布。
    def test_branch_history_controls_release_channel(self) -> None:
        sdk.check_source(sdk.Release("dev", "0.1.0"), self.dev_sha)
        sdk.check_source(sdk.Release("release", "0.1.0"), self.base_sha)
        with self.assertRaisesRegex(ValueError, "origin/main"):
            sdk.check_source(sdk.Release("release", "0.1.0"), self.dev_sha)

    # PR 未合并的提交不属于 develop，因此不能直接发布测试包。
    def test_unmerged_commit_is_rejected(self) -> None:
        self.run_git("checkout", "-b", "feature")
        self.run_git("commit", "--allow-empty", "-m", "unmerged")
        with self.assertRaises(ValueError):
            sdk.check_source(sdk.Release("dev", "0.1.0"), self.run_git("rev-parse", "HEAD"))

    # 带说明的标签会解析到提交，并校验审批前记录的 SHA。
    def test_annotated_tag_is_pinned_to_commit(self) -> None:
        release = sdk.Release("dev", "0.1.0")
        self.run_git("tag", "-a", release.tag, "-m", "test")
        with patch.object(sdk, "fetch_source"):
            self.assertEqual(sdk.verify_tag(release, self.dev_sha), self.dev_sha)
            with self.assertRaisesRegex(ValueError, "SHA"):
                sdk.verify_tag(release, self.base_sha)

    # 审批期间标签移动后，旧 SHA 不能继续发布。
    def test_moved_tag_is_rejected(self) -> None:
        release = sdk.Release("dev", "0.1.0")
        self.run_git("tag", release.tag, self.base_sha)
        with self.assertRaisesRegex(ValueError, "SHA"):
            sdk.verify_tag(release, self.dev_sha)

    # 本地发版拒绝错误分支和未提交文件，不运行构建或创建标签。
    def test_local_publish_requires_correct_clean_branch(self) -> None:
        with self.assertRaisesRegex(ValueError, "main"):
            sdk.publish(sdk.Release("release", "0.1.0"))
        (self.root / "untracked.txt").write_text("pending", encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "未提交"):
            sdk.publish(sdk.Release("dev", "0.1.0"))
        self.assertEqual(self.run_git("tag", "--list"), "")

    # 重复标签和不同步分支都必须在测试与推送之前停止。
    def test_local_publish_rejects_duplicate_and_unsynced_code(self) -> None:
        release = sdk.Release("dev", "0.1.0")
        self.run_git("tag", release.tag)
        with patch.object(sdk, "fetch_source"):
            with self.assertRaisesRegex(ValueError, "Tag 已存在"):
                sdk.publish(release)
            self.run_git("commit", "--allow-empty", "-m", "local only")
            with self.assertRaisesRegex(ValueError, "不一致"):
                sdk.publish(release)

    # 构建失败不能留下新版本标签或触发远端推送。
    def test_failed_tests_do_not_create_tag(self) -> None:
        release = sdk.Release("dev", "0.1.0")
        run = subprocess.run

        # 仅模拟 make 校验失败，保留此前 Git 门禁的真实执行。
        def fail_verification(command: list[str], **kwargs):
            if command[0] == "make":
                raise subprocess.CalledProcessError(1, command)
            return run(command, **kwargs)

        with patch.object(sdk, "fetch_source"):
            with patch.object(sdk.subprocess, "run", side_effect=fail_verification) as execute:
                with self.assertRaises(subprocess.CalledProcessError):
                    sdk.publish(release)
                self.assertTrue(any(call.args[0][0] == "make" for call in execute.call_args_list))
        self.assertEqual(self.run_git("tag", "--list"), "")


# 检查人工审批配置缺失或被放宽时必须失败。
class EnvironmentTests(unittest.TestCase):
    # 构造当前唯一审批人和 Tag 白名单，不从实现常量生成预期配置。
    def setUp(self) -> None:
        self.release = sdk.Release("dev", "0.1.0")
        self.environment = {
            "can_admins_bypass": False,
            "protection_rules": [{
                "type": "required_reviewers", "prevent_self_review": False,
                "reviewers": [{"type": "User", "reviewer": {"login": "hzh137940556"}}],
            }],
            "deployment_branch_policy": {"custom_branch_policies": True, "protected_branches": False},
        }
        self.policies = {"branch_policies": [{"type": "tag", "name": "android-sdk-dev-*"}]}

    # 正确配置允许进入等待审批的发布 Job。
    def test_valid_environment(self) -> None:
        for channel, pattern in (("dev", "android-sdk-dev-*"), ("release", "android-sdk-[0-9]*")):
            with self.subTest(channel=channel):
                policies = {"branch_policies": [{"type": "tag", "name": pattern}]}
                sdk.validate_environment(sdk.Release(channel, "0.1.0"), self.environment, policies)

    # 必须读取当前渠道的实际环境和策略，不回退到另一渠道的审批环境。
    def test_check_environment_uses_channel(self) -> None:
        repository = "hzh137940556/lingora-android-sdk"
        for channel, environment, pattern in (
            ("dev", "android-sdk-dev", "android-sdk-dev-*"),
            ("release", "android-sdk-release", "android-sdk-[0-9]*"),
        ):
            policies = {"branch_policies": [{"type": "tag", "name": pattern}]}
            with self.subTest(channel=channel), patch.object(
                sdk, "github_json", side_effect=[self.environment, policies],
            ) as fetch:
                sdk.check_environment(sdk.Release(channel, "0.1.0"), repository)
                self.assertEqual([call.args for call in fetch.call_args_list], [
                    (repository, f"environments/{environment}"),
                    (repository, f"environments/{environment}/deployment-branch-policies"),
                ])

    # 旧账号、替代账号以及额外审批人都不能改变已确认的唯一审批名单。
    def test_other_reviewers_are_rejected(self) -> None:
        for names in (
            ["hzhbrain"], ["another-reviewer"], ["hzh137940556", "hzhbrain"],
        ):
            environment = copy.deepcopy(self.environment)
            environment["protection_rules"][0]["reviewers"] = [
                {"type": "User", "reviewer": {"login": name}} for name in names
            ]
            with self.subTest(names=names), self.assertRaises(ValueError):
                sdk.validate_environment(self.release, environment, self.policies)

    # 自动创建的无保护环境不满足发版条件。
    def test_missing_reviewers_is_rejected(self) -> None:
        for environment in ({}, {"protection_rules": []}):
            with self.subTest(environment=environment), self.assertRaises(ValueError):
                sdk.validate_environment(self.release, environment, self.policies)

    # 少配审批人、禁止自审或允许管理员绕过都不是确认的配置。
    def test_incorrect_approval_policy_is_rejected(self) -> None:
        for kind in ("reviewer", "self_review", "bypass"):
            environment = copy.deepcopy(self.environment)
            if kind == "reviewer":
                environment["protection_rules"][0]["reviewers"].pop()
            elif kind == "self_review":
                environment["protection_rules"][0]["prevent_self_review"] = True
            else:
                environment["can_admins_bypass"] = True
            with self.subTest(kind=kind), self.assertRaises(ValueError):
                sdk.validate_environment(self.release, environment, self.policies)

    # 广泛规则、跨渠道规则、同名 Branch 和额外放行规则均不能通过审批校验。
    def test_only_release_tags_are_allowed(self) -> None:
        for channel, pattern, other in (
            ("dev", "android-sdk-dev-*", "android-sdk-[0-9]*"),
            ("release", "android-sdk-[0-9]*", "android-sdk-dev-*"),
        ):
            for branches in (
                [], [{"type": "branch", "name": pattern}],
                [{"type": "tag", "name": "*"}],
                [{"type": "tag", "name": "android-sdk-*"}],
                [{"type": "tag", "name": other}],
                [{"type": "tag", "name": pattern}, {"type": "branch", "name": "main"}],
            ):
                with self.subTest(channel=channel, branches=branches), self.assertRaises(ValueError):
                    sdk.validate_environment(
                        sdk.Release(channel, "0.1.0"), self.environment, {"branch_policies": branches},
                    )

    # GitHub 返回环境不存在或无权限时，不能继续等待一个无保护环境。
    def test_missing_environment_or_api_failure_stops_release(self) -> None:
        with patch.dict(sdk.os.environ, {"GH_TOKEN": "test-only-token"}):
            for channel in ("dev", "release"):
                for code in (401, 403, 404, 500):
                    error = HTTPError("https://api.github.com", code, "test", {}, None)
                    with self.subTest(channel=channel, code=code), patch.object(sdk, "urlopen", side_effect=error):
                        with self.assertRaisesRegex(ValueError, str(code)):
                            sdk.check_environment(sdk.Release(channel, "0.1.0"), "hzh137940556/lingora-android-sdk")

    # 缺少审批时不能导出供下游发布使用的版本与提交信息。
    def test_cli_does_not_export_release_without_approval_configuration(self) -> None:
        repository = "hzh137940556/lingora-android-sdk"
        for channel in ("dev", "release"):
            with self.subTest(channel=channel), tempfile.TemporaryDirectory() as directory:
                output = Path(directory) / "outputs"
                release = sdk.Release(channel, "0.1.0")
                with patch.object(sdk, "verify_tag", return_value="abc123"):
                    with patch.object(sdk, "check_environment", side_effect=ValueError("missing environment")) as check:
                        with contextlib.redirect_stderr(io.StringIO()):
                            result = sdk.main([
                                "verify-tag", "--tag", release.tag, "--repository", repository,
                                "--github-output", str(output),
                            ])
                self.assertEqual(result, 1)
                self.assertFalse(output.exists())
                check.assert_called_once_with(release, repository)

    # 审批前后都校验当前渠道，只有配置通过后才导出供工作流选择的环境。
    def test_cli_exports_validated_environment(self) -> None:
        repository = "hzh137940556/lingora-android-sdk"
        for channel, environment, pattern in (
            ("dev", "android-sdk-dev", "android-sdk-dev-*"),
            ("release", "android-sdk-release", "android-sdk-[0-9]*"),
        ):
            for expected_sha in (None, "abc123"):
                with self.subTest(channel=channel, expected_sha=expected_sha), tempfile.TemporaryDirectory() as directory:
                    output = Path(directory) / "outputs"
                    summary = Path(directory) / "summary"
                    release = sdk.Release(channel, "0.1.0")
                    policies = {"branch_policies": [{"type": "tag", "name": pattern}]}
                    args = ["verify-tag", "--tag", release.tag, "--repository", repository, "--github-output", str(output)]
                    if expected_sha is not None:
                        args.extend(["--expected-sha", expected_sha])
                    with patch.dict(sdk.os.environ, {"GITHUB_STEP_SUMMARY": str(summary)}):
                        with patch.object(sdk, "verify_tag", return_value="abc123") as verify:
                            with patch.object(sdk, "github_json", side_effect=[self.environment, policies]) as fetch:
                                with contextlib.redirect_stdout(io.StringIO()):
                                    self.assertEqual(sdk.main(args), 0)
                    verify.assert_called_once_with(release, expected_sha)
                    self.assertEqual([call.args for call in fetch.call_args_list], [
                        (repository, f"environments/{environment}"),
                        (repository, f"environments/{environment}/deployment-branch-policies"),
                    ])
                    self.assertIn(f"environment={environment}\n", output.read_text())
                    self.assertIn(environment, summary.read_text())


# 模拟 GitHub Packages，只对明确不存在的坐标开放首次上传。
class PackageTests(unittest.TestCase):
    # 使用无权限的固定测试值，保证不读取真实凭据。
    def setUp(self) -> None:
        self.credentials = patch.dict(sdk.os.environ, {"GITHUB_ACTOR": "test", "GITHUB_TOKEN": "test-only-token"})
        self.credentials.start()
        self.addCleanup(self.credentials.stop)

    # 两个渠道查询各自的包路径，只有 404 被视为尚未发布。
    def test_missing_package_allows_each_channel(self) -> None:
        for channel in ("dev", "release"):
            release = sdk.Release(channel, "0.1.0")
            error = HTTPError("https://maven.pkg.github.com", 404, "test", {}, None)
            with self.subTest(channel=channel), patch.object(sdk, "urlopen", side_effect=error) as fetch:
                sdk.check_package(release, "hzh137940556/lingora-android-sdk")
                url = fetch.call_args.args[0].full_url
                self.assertTrue(url.startswith("https://maven.pkg.github.com/hzh137940556/lingora-android-sdk/"))
                self.assertIn(f"/{release.artifact_id}/0.1.0/{release.artifact_id}-0.1.0.pom", url)

    # 已存在的版本不能覆盖。
    def test_existing_package_is_rejected(self) -> None:
        with patch.object(sdk, "urlopen", return_value=contextlib.nullcontext(Mock())):
            with self.assertRaisesRegex(ValueError, "版本已存在"):
                sdk.check_package(sdk.Release("dev", "0.1.0"), "hzh137940556/lingora-android-sdk")

    # 鉴权、服务故障和网络失败都不能被误判成版本不存在。
    def test_authentication_and_network_errors_stop_publish(self) -> None:
        failures = [HTTPError("https://maven.pkg.github.com", code, "test", {}, None) for code in (401, 403, 500)]
        failures.append(URLError("offline"))
        for failure in failures:
            with self.subTest(failure=failure), patch.object(sdk, "urlopen", side_effect=failure):
                with self.assertRaises(ValueError):
                    sdk.check_package(sdk.Release("release", "0.1.0"), "hzh137940556/lingora-android-sdk")


if __name__ == "__main__":
    unittest.main()
