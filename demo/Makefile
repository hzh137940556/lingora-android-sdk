# Demo 只负责 App 验证和固定版本 CLI 的命令编排，不创建 SDK Tag 或发布包。
SHELL := /bin/sh
LINGORA_CONFIG ?= ci/lingora.dev.json
LINGORA_CLI_VERSION := $(shell tr -d '\r\n' < lingora-cli.version)
LINGORA := uvx --from "lingora-cli==$(LINGORA_CLI_VERSION)" lingora

.PHONY: check_cli mock_ci verify

check_cli:
	@command -v uv >/dev/null 2>&1 || { echo "错误：未安装 uv，请先安装 uv"; exit 127; }

mock_ci: check_cli
	$(LINGORA) --config "$(LINGORA_CONFIG)" push
	$(LINGORA) --config "$(LINGORA_CONFIG)" pull

verify:
	./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
