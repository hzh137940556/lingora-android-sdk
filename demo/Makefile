# Demo 只负责 App 验证和固定版本 CLI 的命令编排，不创建 SDK Tag 或发布包。
SHELL := /bin/sh
LINGORA_CONFIG ?= ci/lingora.dev.json
LINGORA_CLI_VERSION := $(shell tr -d '\r\n' < lingora-cli.version)
LINGORA := uvx --from "lingora-cli==$(LINGORA_CLI_VERSION)" lingora

.PHONY: check_cli cli-validate cli-push cli-pull mock_ci test-cli verify

check_cli:
	@command -v uv >/dev/null 2>&1 || { echo "错误：未安装 uv，请先安装 uv"; exit 127; }
	@command -v uvx >/dev/null 2>&1 || { echo "错误：未安装 uvx，请检查 uv 安装"; exit 127; }

cli-validate: check_cli
	$(LINGORA) --config "$(LINGORA_CONFIG)" validate

cli-push: check_cli
	$(LINGORA) --config "$(LINGORA_CONFIG)" push

cli-pull: check_cli
	$(LINGORA) --config "$(LINGORA_CONFIG)" pull

# 同一 recipe 内串行调用，make -j 或 MAKEFLAGS 不得并行上传和下载。
mock_ci: check_cli
	$(LINGORA) --config "$(LINGORA_CONFIG)" validate
	$(LINGORA) --config "$(LINGORA_CONFIG)" push
	$(LINGORA) --config "$(LINGORA_CONFIG)" pull

test-cli:
	python3 -m unittest discover -s tests -p 'test_*.py' -v

verify: test-cli
	./gradlew :app:testDebugUnitTest :app:assembleDebug :app:assembleDebugAndroidTest \
		-PlingoraSdkMode=local --no-daemon
