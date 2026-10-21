# Demo 只负责 App 验证和已安装 CLI 的命令编排，不创建 SDK Tag 或发布包。
SHELL := /bin/sh
LINGORA_CONFIG ?= ci/lingora.dev.json

.PHONY: mock_ci verify

mock_ci:
	lingora --config "$(LINGORA_CONFIG)" push
	lingora --config "$(LINGORA_CONFIG)" pull

verify:
	./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
