# 使用隔离 Maven fixture 验证包坐标、完整性和公开 API 依赖，不访问网络。
from __future__ import annotations

import hashlib
import io
import json
import tempfile
import unittest
import zipfile
from pathlib import Path

from check_publication import check_publication


# 创建最小有效产物，并独立破坏每个关键契约。
class PublicationTests(unittest.TestCase):
    # 每个测试独占 Maven 目录，防止覆盖真实缓存或包版本。
    def setUp(self) -> None:
        temporary = tempfile.TemporaryDirectory()
        self.addCleanup(temporary.cleanup)
        self.repository = Path(temporary.name)

    # 写入可验证的 ZIP、POM 和 Gradle metadata。
    def fixture(self, channel: str = "dev", version: str = "0.1.0") -> tuple[Path, dict]:
        artifact = "lingora-android-dev" if channel == "dev" else "lingora-android"
        directory = self.repository / "io/lingora" / artifact / version
        directory.mkdir(parents=True)
        prefix = artifact + "-" + version
        (directory / f"{prefix}.pom").write_text(
            '<project xmlns="http://maven.apache.org/POM/4.0.0">'
            f"<groupId>io.lingora</groupId><artifactId>{artifact}</artifactId><version>{version}</version>"
            "<dependencies><dependency><artifactId>kotlinx-coroutines-android</artifactId>"
            "<scope>compile</scope></dependency></dependencies></project>", encoding="utf-8",
        )
        classes = io.BytesIO()
        with zipfile.ZipFile(classes, "w") as archive:
            archive.writestr("io/lingora/android/Lingora.class", b"fixture")
            archive.writestr("io/lingora/android/BuildConfig.class", version.encode())
        with zipfile.ZipFile(directory / f"{prefix}.aar", "w") as archive:
            archive.writestr("classes.jar", classes.getvalue())
        with zipfile.ZipFile(directory / f"{prefix}-sources.jar", "w") as archive:
            archive.writestr("io/lingora/android/Lingora.kt", "// fixture")
        files = []
        for name in (f"{prefix}.aar", f"{prefix}-sources.jar"):
            data = (directory / name).read_bytes()
            files.append({"url": name, "size": len(data), "sha256": hashlib.sha256(data).hexdigest()})
        metadata = {
            "component": {"group": "io.lingora", "module": artifact, "version": version},
            "variants": [{"attributes": {"org.gradle.usage": "java-api"},
                          "dependencies": [{"module": "kotlinx-coroutines-android"}], "files": files}],
        }
        self.write_metadata(directory, metadata)
        return directory, metadata

    # 按当前版本目录生成 metadata，便于逐项验证非法输入。
    def write_metadata(self, directory: Path, metadata: dict) -> None:
        path = directory / f"{directory.parent.name}-{directory.name}.module"
        path.write_text(json.dumps(metadata), encoding="utf-8")

    # 两个渠道的相同版本号可以共存，普通本地 SNAPSHOT 也可验收。
    def test_channels_and_snapshot(self) -> None:
        for channel, version in (("dev", "0.1.0"), ("release", "0.1.0"), ("release", "0.1.1-SNAPSHOT")):
            with self.subTest(channel=channel, version=version):
                self.fixture(channel, version)
                check_publication(self.repository, channel, version)

    # 拒绝元数据版本错配、非公开协程依赖、损坏文件与越界路径。
    def test_invalid_metadata(self) -> None:
        for case in ("coordinate", "api", "checksum", "path"):
            with self.subTest(case=case), tempfile.TemporaryDirectory() as temporary:
                self.repository = Path(temporary)
                directory, metadata = self.fixture()
                if case == "coordinate":
                    metadata["component"]["version"] = "9.9.9"
                elif case == "api":
                    metadata["variants"][0]["dependencies"] = []
                elif case == "checksum":
                    metadata["variants"][0]["files"][0]["sha256"] = "invalid"
                else:
                    metadata["variants"][0]["files"][0]["url"] = "../outside.aar"
                self.write_metadata(directory, metadata)
                with self.assertRaises(ValueError):
                    check_publication(self.repository, "dev", "0.1.0")

    # POM 不能把公共协程 API 降为 runtime，且所有必需产物必须存在。
    def test_pom_and_missing_artifacts(self) -> None:
        directory, _ = self.fixture()
        pom = next(directory.glob("*.pom"))
        content = pom.read_text()
        pom.write_text(content.replace("compile", "runtime"))
        with self.assertRaisesRegex(ValueError, "POM"):
            check_publication(self.repository, "dev", "0.1.0")
        pom.write_text(content)
        next(directory.glob("*.aar")).unlink()
        with self.assertRaises(FileNotFoundError):
            check_publication(self.repository, "dev", "0.1.0")

    # 即使 checksum 被重新计算，也不能把 App 类混入 AAR。
    def test_app_classes_are_rejected(self) -> None:
        directory, metadata = self.fixture()
        classes = io.BytesIO()
        with zipfile.ZipFile(classes, "w") as archive:
            archive.writestr("io/lingora/android/Lingora.class", b"fixture")
            archive.writestr("io/lingora/android/BuildConfig.class", b"0.1.0")
            archive.writestr("com/example/myapplication/MainActivity.class", b"app")
        aar = next(directory.glob("*.aar"))
        with zipfile.ZipFile(aar, "w") as archive:
            archive.writestr("classes.jar", classes.getvalue())
        data = aar.read_bytes()
        metadata["variants"][0]["files"][0].update(size=len(data), sha256=hashlib.sha256(data).hexdigest())
        self.write_metadata(directory, metadata)
        with self.assertRaisesRegex(ValueError, "package"):
            check_publication(self.repository, "dev", "0.1.0")


if __name__ == "__main__":
    unittest.main()
