SHELL := /bin/sh

VERSION ?=
SDK_CHANNEL ?= release
BUILD_VERSION = $(if $(strip $(VERSION)),$(VERSION),0.1.0-SNAPSHOT)

.PHONY: test verify test-release check-version publish-local publish-dev publish

test-release:
	python3 -m unittest discover -s scripts -p 'test_*.py' -v

test: test-release
	./gradlew :lingora-android-sdk:test --no-daemon

verify: test-release
	./gradlew :lingora-android-sdk:test :lingora-android-sdk:assembleRelease \
		:lingora-android-sdk:publishToMavenLocal \
		-PVERSION_NAME="$(BUILD_VERSION)" -PSDK_CHANNEL="$(SDK_CHANNEL)" \
		-Dmaven.repo.local="$(CURDIR)/build/verified-maven" --no-daemon
	python3 scripts/check_publication.py --repository build/verified-maven \
		--version "$(BUILD_VERSION)" --channel "$(SDK_CHANNEL)"

check-version:
	@python3 scripts/sdk_release.py info --version "$(VERSION)" --channel "$(SDK_CHANNEL)"

publish-local: check-version
	./gradlew :lingora-android-sdk:test :lingora-android-sdk:publishToMavenLocal \
		-PVERSION_NAME="$(VERSION)" -PSDK_CHANNEL="$(SDK_CHANNEL)" --no-daemon

publish-dev:
	python3 scripts/sdk_release.py publish --channel dev --version "$(VERSION)"

publish:
	python3 scripts/sdk_release.py publish --channel release --version "$(VERSION)"
