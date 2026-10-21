# 根入口只转发命令，SDK 发布不依赖 Demo 校验。
SHELL := /bin/sh

.PHONY: verify verify-sdk verify-demo test test-release test-cli publish-local publish-dev publish check-version

verify: verify-sdk verify-demo

verify-sdk:
	$(MAKE) -C sdk verify

verify-demo:
	$(MAKE) -C demo verify

test:
	$(MAKE) -C sdk test

test-release:
	$(MAKE) -C sdk test-release

test-cli:
	$(MAKE) -C demo test-cli

publish-local publish-dev publish check-version:
	$(MAKE) -C sdk $@
