#!/usr/bin/env python3
# 统一 SDK 的版本映射、代码来源、审批门禁、发版与发布后消费验收。
from __future__ import annotations

import argparse
import base64
import json
import os
import re
import subprocess
import sys
from dataclasses import dataclass
from pathlib import Path
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

ROOT = Path(__file__).resolve().parents[1]
ENVIRONMENT = "android-sdk-release"
VERSION_PATTERN = r"(?:0|[1-9][0-9]*)(?:\.(?:0|[1-9][0-9]*)){2}"
REVIEWERS = {"hzhbrain", "hzh137940556"}


# 保持渠道、Tag、分支和 Maven 坐标的一致映射。
@dataclass(frozen=True)
class Release:
    channel: str
    version: str

    # 在任何 Git 或网络操作之前拒绝不合法的发布输入。
    def __post_init__(self) -> None:
        if self.channel not in {"dev", "release"}:
            raise ValueError("SDK_CHANNEL 必须为 dev 或 release")
        if not re.fullmatch(VERSION_PATTERN, self.version):
            raise ValueError("版本号必须为 X.Y.Z，例如 0.1.0，不接受前导零或后缀")

    # 将测试渠道限制在 develop，正式渠道限制在 main。
    @property
    def branch(self) -> str:
        return "develop" if self.channel == "dev" else "main"

    # 为两个渠道分配不同的 Maven 包名。
    @property
    def artifact_id(self) -> str:
        return "lingora-android-dev" if self.channel == "dev" else "lingora-android"

    # 生成维护者约定的版本标签。
    @property
    def tag(self) -> str:
        prefix = "android-sdk-dev-" if self.channel == "dev" else "android-sdk-"
        return prefix + self.version

    # 提供日志与审批界面需要的完整依赖坐标。
    @property
    def coordinate(self) -> str:
        return f"io.lingora:{self.artifact_id}:{self.version}"


# 严格解析两种标签，不把未知格式退化为正式发布。
def parse_tag(tag: str) -> Release:
    match = re.fullmatch(rf"android-sdk-(dev-)?({VERSION_PATTERN})", tag)
    if match is None:
        raise ValueError("Tag 必须为 android-sdk-X.Y.Z 或 android-sdk-dev-X.Y.Z")
    return Release("dev" if match.group(1) else "release", match.group(2))


# 在本仓库执行 Git，不通过 shell 拼接用户输入。
def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()


# 刷新对应分支，避免根据陈旧的远端引用判断代码来源。
def fetch_source(release: Release, *, tags: bool = False) -> None:
    command = ["git", "fetch", "origin"]
    if tags:
        command.append("--tags")
    command.append(f"refs/heads/{release.branch}:refs/remotes/origin/{release.branch}")
    subprocess.run(command, cwd=ROOT, check=True)


# 验证提交属于允许的远端分支历史，同时保留发布历史版本的能力。
def check_source(release: Release, sha: str) -> None:
    result = subprocess.run(
        ["git", "merge-base", "--is-ancestor", sha, f"origin/{release.branch}"],
        cwd=ROOT,
        check=False,
    )
    if result.returncode != 0:
        raise ValueError(f"提交 {sha} 不属于 origin/{release.branch}，禁止发布")


# 确保构建提交与标签一致，审批后也不能切换到新的分支或标签内容。
def verify_tag(release: Release, expected_sha: str | None = None) -> str:
    sha = git("rev-parse", "HEAD")
    tag_sha = git("rev-parse", f"refs/tags/{release.tag}^{{commit}}")
    if sha != tag_sha or (expected_sha is not None and sha != expected_sha):
        raise ValueError("Tag、当前代码与已校验的 commit SHA 不一致，禁止发布")
    fetch_source(release)
    check_source(release, sha)
    return sha


# 限定 GitHub 仓库标识，避免凭据被发送到外部地址。
def check_repository(repository: str) -> None:
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", repository):
        raise ValueError("repository 必须为 owner/repository")


# 读取 GitHub 审批配置；权限、网络或响应异常都不能当作配置已通过。
def github_json(repository: str, path: str) -> dict:
    check_repository(repository)
    token = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if not token:
        raise ValueError("读取审批配置需要 GH_TOKEN 或 GITHUB_TOKEN")
    request = Request(
        f"https://api.github.com/repos/{repository}/{path}",
        headers={"Accept": "application/vnd.github+json", "Authorization": f"Bearer {token}"},
    )
    try:
        with urlopen(request, timeout=30) as response:
            data = json.load(response)
    except HTTPError as exc:
        raise ValueError(f"无法读取发布审批配置：HTTP {exc.code}，请由仓库所有者完成设置") from exc
    except URLError as exc:
        raise ValueError("无法连接 GitHub 验证审批配置，发布已停止") from exc
    if not isinstance(data, dict):
        raise ValueError("GitHub 审批配置响应格式错误")
    return data


# 要求两个指定审批人、允许自审、禁止管理员跳过，并且仅开放版本 Tag。
def validate_environment(environment: dict, policies: dict) -> None:
    rules = environment.get("protection_rules", [])
    reviewers_rule = next((rule for rule in rules if rule.get("type") == "required_reviewers"), {})
    reviewers = {
        item.get("reviewer", {}).get("login")
        for item in reviewers_rule.get("reviewers", [])
        if item.get("type") == "User"
    }
    if reviewers != REVIEWERS or reviewers_rule.get("prevent_self_review") is not False:
        raise ValueError("发布环境必须配置 hzhbrain 和 hzh137940556 为审批人，并允许自己批准")
    if environment.get("can_admins_bypass") is not False:
        raise ValueError("发布环境必须关闭管理员跳过审批")
    policy = environment.get("deployment_branch_policy") or {}
    branches = policies.get("branch_policies", [])
    if (
        policy.get("custom_branch_policies") is not True
        or policy.get("protected_branches") is not False
        or len(branches) != 1
        or branches[0].get("type") != "tag"
        or branches[0].get("name") != "android-sdk-*"
    ):
        raise ValueError("发布环境必须只允许类型为 Tag 的 android-sdk-* 规则")


# 在进入审批 Job 之前确认真实门禁存在，防止自动创建无保护环境。
def check_environment(repository: str) -> None:
    path = f"environments/{ENVIRONMENT}"
    validate_environment(
        github_json(repository, path),
        github_json(repository, f"{path}/deployment-branch-policies"),
    )


# 在有包权限的发布 Job 中检查目标 POM；只有明确的 404 才允许首次发布。
def check_package(release: Release, repository: str) -> None:
    check_repository(repository)
    actor, token = os.environ.get("GITHUB_ACTOR"), os.environ.get("GITHUB_TOKEN")
    if not actor or not token:
        raise ValueError("检查包版本需要 GITHUB_ACTOR 和 GITHUB_TOKEN")
    path = f"io/lingora/{release.artifact_id}/{release.version}/{release.artifact_id}-{release.version}.pom"
    authorization = base64.b64encode(f"{actor}:{token}".encode()).decode()
    request = Request(
        f"https://maven.pkg.github.com/{repository.lower()}/{path}",
        headers={"Authorization": f"Basic {authorization}"},
    )
    try:
        with urlopen(request, timeout=30):
            pass
    except HTTPError as exc:
        if exc.code == 404:
            return
        raise ValueError(f"无法确认包版本是否存在：HTTP {exc.code}，发布已停止") from exc
    except URLError as exc:
        raise ValueError("无法连接 GitHub Packages 检查版本，发布已停止") from exc
    raise ValueError(f"版本已存在，禁止覆盖：{release.coordinate}")


# 维护者从干净且已同步的分支测试、创建不可重用的标签并推送。
def publish(release: Release) -> None:
    if git("branch", "--show-current") != release.branch:
        raise ValueError(f"{release.channel} 渠道只能从 {release.branch} 发布")
    if git("status", "--porcelain"):
        raise ValueError("工作区存在未提交修改，请先提交或暂存")
    fetch_source(release, tags=True)
    sha = git("rev-parse", "HEAD")
    if sha != git("rev-parse", f"origin/{release.branch}"):
        raise ValueError(f"本地 {release.branch} 与 origin/{release.branch} 不一致，请先同步")
    if release.tag in git("tag", "--list").splitlines():
        raise ValueError(f"Tag 已存在，必须使用新版本：{release.tag}")
    subprocess.run(
        ["make", "verify", f"VERSION={release.version}", f"SDK_CHANNEL={release.channel}"],
        cwd=ROOT,
        check=True,
    )
    if git("rev-parse", "HEAD") != sha or git("status", "--porcelain"):
        raise ValueError("测试期间代码发生变化，请重新发布")
    git("tag", "-a", release.tag, "-m", f"发布 {release.coordinate} ({sha})", sha)
    print(f"已创建 {release.tag}，推送失败时可重试 git push origin {release.tag}", flush=True)
    git("push", "origin", f"refs/tags/{release.tag}")


# 在 CI 临时工作区切换 Demo 的唯一 SDK 依赖，不修改注释中的备选依赖。
def prepare_demo(release: Release) -> None:
    build_file = ROOT / "app" / "build.gradle.kts"
    pattern = r'(?m)^( *)implementation\((?:project\(":lingora-android-sdk"\)|"io\.lingora:lingora-android(?:-dev)?:[^"\n]+")\)'
    source, count = re.subn(
        pattern,
        lambda match: f'{match[1]}implementation("{release.coordinate}")',
        build_file.read_text(encoding="utf-8"),
    )
    if count != 1:
        raise ValueError("Demo 必须且只能启用一条本地或 Maven SDK 依赖")
    build_file.write_text(source, encoding="utf-8")


# 向 CI 输出已验证的渠道与提交，同时展示人工审批需要核对的信息。
def report(release: Release, sha: str = "", output: Path | None = None) -> None:
    fields = {
        "version": release.version, "channel": release.channel, "branch": release.branch,
        "artifact_id": release.artifact_id, "tag": release.tag,
        "coordinate": release.coordinate, "sha": sha,
    }
    print(json.dumps(fields, ensure_ascii=False))
    if output:
        with output.open("a", encoding="utf-8") as stream:
            stream.writelines(f"{key}={value}\n" for key, value in fields.items())
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with Path(summary).open("a", encoding="utf-8") as stream:
            stream.write("\n### SDK 发布信息\n\n")
            stream.writelines(f"- {key}: `{value}`\n" for key, value in fields.items())


# 提供 Makefile 和 GitHub Actions 共用的发版与消费验收入口。
def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Lingora Android SDK 发布校验与发版工具")
    commands = parser.add_subparsers(dest="command", required=True)
    for name in ("info", "publish", "check-package", "prepare-demo"):
        command = commands.add_parser(name)
        command.add_argument("--channel", choices=("dev", "release"), required=True)
        command.add_argument("--version", required=True)
        if name == "check-package":
            command.add_argument("--repository", required=True)
    verify = commands.add_parser("verify-tag")
    verify.add_argument("--tag", required=True)
    verify.add_argument("--repository", required=True)
    verify.add_argument("--expected-sha")
    verify.add_argument("--github-output", type=Path)
    args = parser.parse_args(argv)
    try:
        release = parse_tag(args.tag) if args.command == "verify-tag" else Release(args.channel, args.version)
        if args.command == "verify-tag":
            sha = verify_tag(release, args.expected_sha)
            check_environment(args.repository)
            report(release, sha, args.github_output)
        elif args.command == "publish":
            publish(release)
        elif args.command == "check-package":
            check_package(release, args.repository)
        elif args.command == "prepare-demo":
            prepare_demo(release)
        else:
            report(release)
        return 0
    except (ValueError, OSError, subprocess.CalledProcessError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
