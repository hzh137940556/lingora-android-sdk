#!/usr/bin/env python3
# 验证 Gradle SDK 模式和 Demo CLI 的安全 push 策略。
from __future__ import annotations

import json
import subprocess
from pathlib import Path


# 只检查本地配置，不上传翻译、不发布包，也不依赖本机业务配置。
def main() -> None:
    root = Path(__file__).resolve().parents[1]
    makefile = (root / "Makefile").read_text(encoding="utf-8")
    if "-PlingoraSdkMode=local" not in makefile:
        raise SystemExit("Demo verify must compile against the local SDK source")
    push = json.loads((root / "ci/lingora.dev.json").read_text(encoding="utf-8"))["push"]
    if push.get("forceMode") != "KEEP" or push.get("removeOtherKeys") is not False:
        raise SystemExit("Demo push must keep existing values and preserve other server keys")
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
