# Demo 只负责 App 和 CLI 编排测试，不创建 SDK Tag 或发布包。
SHELL := /bin/sh

.PHONY: test-cli verify

test-cli:
	python3 -m unittest discover -s scripts -p 'test_*.py' -v

verify: test-cli
	./gradlew :app:testDebugUnitTest :app:assembleDebug --no-daemon
