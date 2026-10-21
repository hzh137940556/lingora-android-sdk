#!/usr/bin/env python3
# 使用真实 Gradle 配置验证非法模式、缺失版本和失效本地路径直接失败。
from __future__ import annotations

import subprocess
from pathlib import Path


# 只运行 help 配置阶段，不上传翻译、不发布包，也不依赖本机业务配置。
def main() -> None:
    root = Path(__file__).resolve().parents[1]
    cases = (
        (["-PlingoraSdkMode=unknown"], "lingoraSdkMode must be local, dev or release"),
        (["-PlingoraSdkMode=local", "-PlingoraLocalSdkPath=/nonexistent-sdk"], "Local SDK not found"),
        (["-PlingoraSdkMode=dev", "-PlingoraSdkVersion="], "Maven mode requires a fixed X.Y.Z"),
        (["-PlingoraSdkMode=release", "-PlingoraSdkVersion=1.+"], "Maven mode requires a fixed X.Y.Z"),
    )
    for arguments, message in cases:
        result = subprocess.run(
            [str(root / "gradlew"), "help", *arguments, "--no-daemon"],
            cwd=root, text=True, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
        )
        if result.returncode == 0 or message not in result.stdout:
            raise SystemExit(f"Unexpected Gradle configuration result for {arguments}:\n{result.stdout}")
        print(f"PASS: {arguments} -> {message}", flush=True)


if __name__ == "__main__":
    main()
