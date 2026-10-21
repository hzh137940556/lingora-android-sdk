SHELL := /bin/sh

VERSION ?=
SDK_CHANNEL ?= release
BUILD_VERSION = $(if $(strip $(VERSION)),$(VERSION),0.1.0-SNAPSHOT)

.PHONY: test verify test-cli test-release check-version publish-local publish-dev publish

test-release:
	python3 -m unittest discover -s scripts -p 'test_sdk_release.py' -v

test-cli:
	python3 -m unittest discover -s scripts -p 'test_mock_ci.py' -v

test: test-cli test-release
	./gradlew :lingora-android-sdk:test --no-daemon

verify: test-cli test-release
	./gradlew :lingora-android-sdk:test :app:testDebugUnitTest \
		:app:assembleDebug :lingora-android-sdk:assembleRelease \
		-PVERSION_NAME="$(BUILD_VERSION)" -PSDK_CHANNEL="$(SDK_CHANNEL)" --no-daemon

check-version:
	@python3 scripts/sdk_release.py info --version "$(VERSION)" --channel "$(SDK_CHANNEL)"

publish-local: check-version
	./gradlew :lingora-android-sdk:test :lingora-android-sdk:publishToMavenLocal \
		-PVERSION_NAME="$(VERSION)" -PSDK_CHANNEL="$(SDK_CHANNEL)" --no-daemon

publish-dev:
	python3 scripts/sdk_release.py publish --channel dev --version "$(VERSION)"

publish:
	python3 scripts/sdk_release.py publish --channel release --version "$(VERSION)"
