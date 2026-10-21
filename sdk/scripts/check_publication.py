#!/usr/bin/env python3
# 验证独立 SDK 的 Maven 产物、公开依赖和包边界，不加载 Demo。
from __future__ import annotations

import argparse
import hashlib
import io
import json
import re
import sys
import xml.etree.ElementTree as ET
import zipfile
from pathlib import Path

from sdk_release import Release, VERSION_PATTERN


# 对两个渠道使用同一套产物检查，SNAPSHOT 仅用于本地校验。
def check_publication(repository: Path, channel: str, version: str) -> None:
    if not re.fullmatch(VERSION_PATTERN + r"(?:-SNAPSHOT)?", version):
        raise ValueError("版本必须为 X.Y.Z 或 X.Y.Z-SNAPSHOT")
    release = Release(channel, version.removesuffix("-SNAPSHOT"))
    artifact = release.artifact_id
    directory = repository / "io" / "lingora" / artifact / version
    prefix = f"{artifact}-{version}"
    namespace = {"m": "http://maven.apache.org/POM/4.0.0"}
    pom = ET.parse(directory / f"{prefix}.pom").getroot()
    for field, expected in (("groupId", "io.lingora"), ("artifactId", artifact), ("version", version)):
        if pom.findtext(f"m:{field}", namespaces=namespace) != expected:
            raise ValueError(f"POM {field} 不一致")
    if not any(
        dependency.findtext("m:artifactId", namespaces=namespace) == "kotlinx-coroutines-android"
        and dependency.findtext("m:scope", namespaces=namespace) == "compile"
        for dependency in pom.findall("m:dependencies/m:dependency", namespace)
    ):
        raise ValueError("POM 必须公开传递协程 API 依赖")

    metadata = json.loads((directory / f"{prefix}.module").read_text(encoding="utf-8"))
    component = metadata["component"]
    if (component["group"], component["module"], component["version"]) != ("io.lingora", artifact, version):
        raise ValueError("Gradle 元数据坐标不一致")
    variants = metadata["variants"]
    if not any(
        variant.get("attributes", {}).get("org.gradle.usage") == "java-api"
        and any(dependency.get("module") == "kotlinx-coroutines-android" for dependency in variant.get("dependencies", []))
        for variant in variants
    ):
        raise ValueError("Gradle 元数据必须公开传递协程 API 依赖")
    for variant in variants:
        for item in variant.get("files", []):
            path = directory / item["url"]
            if path.resolve().parent != directory.resolve():
                raise ValueError("产物路径超出当前版本目录")
            data = path.read_bytes()
            if len(data) != item["size"] or hashlib.sha256(data).hexdigest() != item["sha256"]:
                raise ValueError(f"产物校验和不一致：{path.name}")

    with zipfile.ZipFile(directory / f"{prefix}.aar") as aar:
        with zipfile.ZipFile(io.BytesIO(aar.read("classes.jar"))) as classes:
            names = classes.namelist()
            if "io/lingora/android/Lingora.class" not in names:
                raise ValueError("AAR 缺少 SDK 入口")
            if any(name.endswith(".class") and not name.startswith("io/lingora/android/") for name in names):
                raise ValueError("AAR 包含 SDK package 之外的类")
            if version.encode() not in classes.read("io/lingora/android/BuildConfig.class"):
                raise ValueError("BuildConfig 版本与 Maven 版本不一致")
    with zipfile.ZipFile(directory / f"{prefix}-sources.jar") as sources:
        if not any(name.endswith("/Lingora.kt") for name in sources.namelist()):
            raise ValueError("sources.jar 缺少 SDK 源码")
    print(f"Verified io.lingora:{artifact}:{version} at {directory}")


# 提供 SDK Makefile 的离线产物验收入口。
def main() -> int:
    parser = argparse.ArgumentParser(description="验证 SDK Maven 产物")
    parser.add_argument("--repository", type=Path, required=True)
    parser.add_argument("--channel", choices=("dev", "release"), required=True)
    parser.add_argument("--version", required=True)
    args = parser.parse_args()
    try:
        check_publication(args.repository, args.channel, args.version)
    except (ValueError, OSError, KeyError, ET.ParseError, zipfile.BadZipFile) as exc:
        print(f"Publication check failed: {exc}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
