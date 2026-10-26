# 用隔离的命令替身验证 Make 在并行模式下的同步顺序与失败边界。
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest


# 不联网、不使用真实凭据，只执行复制到临时目录的 Makefile。
class CliOrchestrationTest(unittest.TestCase):
    # 校验固定版本、带空格的配置路径和任一步失败时的执行范围。
    def test_parallel_make_preserves_sequence_and_stops_on_failure(self):
        source = Path(__file__).resolve().parents[1]
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            shutil.copy(source / "Makefile", root)
            shutil.copy(source / "lingora-cli.version", root)
            binary = root / "bin"
            binary.mkdir()
            (binary / "uv").write_text("#!/bin/sh\nexit 0\n")
            (binary / "uvx").write_text(
                f"#!{sys.executable}\n"
                "import json, os, sys, time\n"
                "with open(os.environ['TEST_LOG'], 'a') as log:\n"
                "    log.write(json.dumps(sys.argv[1:]) + '\\n')\n"
                "time.sleep(0.1)\n"
                "sys.exit(7 if sys.argv[-1] == os.environ['FAIL_STEP'] else 0)\n"
            )
            for executable in binary.iterdir():
                executable.chmod(0o755)
            log = root / "calls.jsonl"
            version = (root / "lingora-cli.version").read_text().strip()
            for fail, commands in (
                ("", ["validate", "push", "pull"]),
                ("validate", ["validate"]),
                ("push", ["validate", "push"]),
                ("pull", ["validate", "push", "pull"]),
            ):
                with self.subTest(fail=fail):
                    log.write_text("")
                    env = {key: value for key, value in os.environ.items()
                           if not key.startswith("LINGORA_") and key not in ("MAKEFLAGS", "MFLAGS")}
                    env.update(PATH=str(binary) + os.pathsep + env["PATH"],
                               TEST_LOG=str(log), FAIL_STEP=fail)
                    result = subprocess.run(
                        ["make", "-j3", "mock_ci", "LINGORA_CONFIG=ci/test config.json"],
                        cwd=root, env=env, capture_output=True, text=True,
                    )
                    self.assertEqual(result.returncode, 2 if fail else 0, result.stderr)
                    calls = [json.loads(line) for line in log.read_text().splitlines()]
                    self.assertEqual(calls, [
                        ["--from", f"lingora-cli=={version}", "lingora", "--config",
                         "ci/test config.json", command] for command in commands
                    ])


if __name__ == "__main__":
    unittest.main()
