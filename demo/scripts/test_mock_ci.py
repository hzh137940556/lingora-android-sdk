# 验证 Demo 只编排已安装 CLI，并在失败时停止；测试不访问真实服务。
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import tempfile
import unittest
from pathlib import Path


# 用临时 CLI 替身验证命令顺序、参数、工作目录和退出码。
class MockCiTests(unittest.TestCase):
    # 复制 Demo 入口并创建替身，不依赖真实 CLI 或本机项目配置。
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.root = Path(temporary.name)
        self.project = self.root / "demo project"
        scripts = self.project / "scripts"
        scripts.mkdir(parents=True)
        self.wrapper = scripts / "mock_ci.sh"
        shutil.copy2(Path(__file__).with_name("mock_ci.sh"), self.wrapper)
        binary = self.root / "bin"
        binary.mkdir()
        fake = binary / "lingora"
        fake.write_text(
            f"#!{sys.executable}\n"
            "import json, os, sys\n"
            "with open(os.environ['MOCK_CI_LOG'], 'a', encoding='utf-8') as output:\n"
            "    output.write(json.dumps([os.getcwd(), sys.argv[1:], "
            "os.environ.get('LINGORA_BRANCH')]) + '\\n')\n"
            "code = os.environ.get('MOCK_CI_' + sys.argv[-1].upper() + '_STATUS', '0')\n"
            "sys.exit(int(code))\n",
            encoding="utf-8",
        )
        fake.chmod(0o755)
        self.log = self.root / "calls.jsonl"
        self.env = {
            **os.environ,
            "PATH": str(binary) + os.pathsep + os.environ.get("PATH", ""),
            "MOCK_CI_LOG": str(self.log),
            "LINGORA_BRANCH": "feature/demo",
            "MOCK_CI_PUSH_STATUS": "0",
            "MOCK_CI_PULL_STATUS": "0",
        }

    # 三种结果都要保留顺序、公共参数、环境分支及原始退出码。
    def test_push_then_pull_and_stop_on_failure(self) -> None:
        for push_status, pull_status, commands, status in (
            (0, 0, ["push", "pull"], 0),
            (7, 0, ["push"], 7),
            (0, 9, ["push", "pull"], 9),
        ):
            with self.subTest(push=push_status, pull=pull_status):
                self.log.write_text("", encoding="utf-8")
                env = {
                    **self.env,
                    "MOCK_CI_PUSH_STATUS": str(push_status),
                    "MOCK_CI_PULL_STATUS": str(pull_status),
                }
                args = ["--config", "ci/test environment.json", "--timeout", "5"]
                result = subprocess.run(
                    ["sh", str(self.wrapper), *args],
                    cwd=self.root,
                    env=env,
                    capture_output=True,
                    text=True,
                )
                self.assertEqual(result.returncode, status, result.stderr)
                calls = [json.loads(line) for line in self.log.read_text().splitlines()]
                self.assertEqual(
                    calls,
                    [
                        [str(self.project.resolve()), [*args, command], "feature/demo"]
                        for command in commands
                    ],
                )

    # 不传参数时，让 CLI 从 Demo 根目录读取默认配置。
    def test_default_config_uses_demo_directory(self) -> None:
        result = subprocess.run(
            ["sh", str(self.wrapper)], cwd=self.root, env=self.env, capture_output=True
        )
        self.assertEqual(result.returncode, 0, result.stderr)
        calls = [json.loads(line) for line in self.log.read_text().splitlines()]
        self.assertEqual(
            calls,
            [
                [str(self.project.resolve()), [command], "feature/demo"]
                for command in ("push", "pull")
            ],
        )


if __name__ == "__main__":
    unittest.main()
