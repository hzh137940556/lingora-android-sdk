#!/usr/bin/env python3
"""Verify one published Lingora Content Delivery endpoint."""

from __future__ import annotations

import argparse
import json
import re
import sys
from collections.abc import Mapping
from pathlib import Path
from typing import Any, Optional
from urllib.error import HTTPError, URLError
from urllib.request import Request, urlopen

from lingora import (
    DEFAULT_TIMEOUT_SECONDS,
    ScriptError,
    default_config_path,
    load_config,
)

MAX_JSON_BYTES = 10 * 1024 * 1024
CACHE_HEADERS = ("Cache-Control", "ETag", "Last-Modified")
LANGUAGE_TAG_RE = re.compile(r"^[A-Za-z0-9]+(?:-[A-Za-z0-9]+)*$")


def fetch_json(url: str, timeout: float) -> tuple[Any, Mapping[str, str]]:
    request = Request(url, headers={"Accept": "application/json"}, method="GET")
    try:
        with urlopen(request, timeout=timeout) as response:
            body = response.read(MAX_JSON_BYTES + 1)
            if len(body) > MAX_JSON_BYTES:
                raise ScriptError(f"Content Delivery response is too large: {url}")
            if not 200 <= response.status < 300:
                raise ScriptError(
                    f"Content Delivery returned HTTP {response.status}: {url}"
                )
            headers = dict(response.headers.items())
    except HTTPError as exc:
        raise ScriptError(f"Content Delivery returned HTTP {exc.code}: {url}") from exc
    except URLError as exc:
        raise ScriptError(
            f"Content Delivery request failed for {url}: {exc.reason}"
        ) from exc
    try:
        return json.loads(body.decode("utf-8")), headers
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ScriptError(f"Content Delivery returned invalid JSON: {url}") from exc


def cache_warnings(url: str, headers: Mapping[str, str]) -> list[str]:
    lowered = {key.casefold() for key in headers}
    return [
        f"{url}: missing {name}"
        for name in CACHE_HEADERS
        if name.casefold() not in lowered
    ]


def _validate_translation_value(locale: str, key: str, value: Any) -> None:
    if isinstance(value, str):
        return
    if isinstance(value, list) and all(isinstance(item, str) for item in value):
        return
    if isinstance(value, dict) and value:
        for child_key, child_value in value.items():
            if not isinstance(child_key, str):
                raise ScriptError(f"Locale {locale!r} contains a non-string key")
            _validate_translation_value(locale, f"{key}.{child_key}", child_value)
        return
    raise ScriptError(f"Locale {locale!r} has an invalid value for key {key!r}")


def validate_locale_document(locale: str, document: Any) -> None:
    if not isinstance(document, dict):
        raise ScriptError(f"Locale {locale!r} JSON root must be an object")
    for key, value in document.items():
        if not isinstance(key, str) or not key:
            raise ScriptError(f"Locale {locale!r} contains an invalid key")
        _validate_translation_value(locale, key, value)


def check_delivery(config_path: Path, delivery: str, timeout: float) -> None:
    config = load_config(config_path)
    base_url = config.content_deliveries.get(delivery)
    if base_url is None:
        available = ", ".join(sorted(config.content_deliveries)) or "(none)"
        raise ScriptError(
            f"Content Delivery {delivery!r} is not configured. Available: {available}"
        )

    manifest_url = f"{base_url}/manifest.json"
    manifest, headers = fetch_json(manifest_url, timeout)
    warnings = cache_warnings(manifest_url, headers)
    if not isinstance(manifest, dict) or not isinstance(manifest.get("locales"), list):
        raise ScriptError("Content Delivery manifest must contain a locales array")
    locales = manifest["locales"]
    if any(not isinstance(item, str) for item in locales) or len(locales) != len(
        set(locales)
    ):
        raise ScriptError("Content Delivery manifest locales must be unique strings")
    if any(not LANGUAGE_TAG_RE.fullmatch(item) for item in locales):
        raise ScriptError("Content Delivery manifest contains an invalid locale")
    expected_locales = set(config.target_languages) | {config.base_language}
    if set(locales) != expected_locales:
        raise ScriptError(
            "Content Delivery locales do not match the base and pull languages: "
            f"expected={sorted(expected_locales)} actual={sorted(locales)}"
        )

    for locale in sorted(locales):
        namespace_prefix = f"{config.namespace}/" if config.namespace else ""
        locale_url = f"{base_url}/{namespace_prefix}{locale}.json"
        document, locale_headers = fetch_json(locale_url, timeout)
        validate_locale_document(locale, document)
        warnings.extend(cache_warnings(locale_url, locale_headers))

    for warning in warnings:
        print(f"warning: {warning}", file=sys.stderr)
    print(
        f"Content Delivery check completed: delivery={delivery} "
        f"locales={','.join(sorted(locales))} warnings={len(warnings)}"
    )


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=default_config_path())
    parser.add_argument("--delivery", required=True)
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECONDS)
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.timeout <= 0:
            raise ScriptError("--timeout must be greater than zero")
        check_delivery(args.config, args.delivery, args.timeout)
        return 0
    except (ScriptError, OSError) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
