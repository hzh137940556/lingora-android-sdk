#!/usr/bin/env python3
"""Run mock CI, push, pull, and validation for Lingora Android resources."""

from __future__ import annotations

import argparse
import io
import json
import os
import re
import shutil
import sys
import tempfile
import uuid
import zipfile
from collections.abc import Callable, Mapping
from dataclasses import dataclass
from pathlib import Path, PurePosixPath
from typing import Any, Optional
from urllib.error import HTTPError, URLError
from urllib.parse import urlsplit
from urllib.request import Request, urlopen
from xml.etree import ElementTree as ET

DEFAULT_CONFIG = ".lingorarc"
DEFAULT_TIMEOUT_SECONDS = 30.0
MAX_RESPONSE_BYTES = 50 * 1024 * 1024
MAX_ZIP_ENTRIES = 1_000
MAX_EXPANDED_ZIP_BYTES = 100 * 1024 * 1024
ANDROID_PLURAL_QUANTITIES = {"zero", "one", "two", "few", "many", "other"}
JAVA_PLACEHOLDER_RE = re.compile(
    r"%(?:(\d+)\$)?([-#+ 0,(<]*)(\d*)(?:\.(\d+))?([tT]?[a-zA-Z%])"
)


class ScriptError(RuntimeError):
    """Expected configuration, validation, or remote API failure."""


@dataclass(frozen=True)
class PushFile:
    path: Path
    language: str
    namespace: str


@dataclass(frozen=True)
class Config:
    config_path: Path
    api_url: str
    project_id: str
    branch: Optional[str]
    format: str
    base_language: str
    target_languages: tuple[str, ...]
    namespace: str
    push_files: tuple[PushFile, ...]
    pull_path: Path
    push_force_mode: str
    push_override_mode: str
    push_error_on_unresolved_conflict: Optional[bool]
    push_override_key_descriptions: bool
    push_convert_placeholders_to_icu: bool
    push_tag_new_keys: tuple[str, ...]
    push_remove_other_keys: bool
    pull_namespaces: tuple[str, ...]
    pull_states: tuple[str, ...]
    pull_file_structure_template: str
    pull_empty_dir: bool
    content_deliveries: dict[str, str]


@dataclass(frozen=True)
class HttpResponse:
    status: int
    headers: Mapping[str, str]
    body: bytes


@dataclass(frozen=True)
class AndroidResource:
    kind: str
    values: Any


Requester = Callable[..., HttpResponse]


def _required_string(data: dict[str, Any], key: str) -> str:
    value = data.get(key)
    if not isinstance(value, str) or not value.strip():
        raise ScriptError(f"Configuration field {key!r} must be a non-empty string")
    return value.strip()


def _required_object(data: dict[str, Any], key: str) -> dict[str, Any]:
    value = data.get(key)
    if not isinstance(value, dict):
        raise ScriptError(f"Configuration field {key!r} must be an object")
    return value


def _string_list(data: dict[str, Any], key: str, *, required: bool) -> tuple[str, ...]:
    value = data.get(key)
    if value is None and not required:
        return ()
    if not isinstance(value, list) or (required and not value):
        qualifier = "non-empty " if required else ""
        raise ScriptError(
            f"Configuration field {key!r} must be a {qualifier}array of strings"
        )
    if any(not isinstance(item, str) or not item.strip() for item in value):
        raise ScriptError(f"Configuration field {key!r} must contain strings")
    normalized = tuple(item.strip() for item in value)
    if len(set(normalized)) != len(normalized):
        raise ScriptError(f"Configuration field {key!r} must contain unique values")
    return normalized


def _boolean(data: dict[str, Any], key: str, default: bool) -> bool:
    value = data.get(key, default)
    if not isinstance(value, bool):
        raise ScriptError(f"Configuration field {key!r} must be a boolean")
    return value


def _validate_url(value: str, field: str) -> str:
    normalized = value.rstrip("/")
    parsed = urlsplit(normalized)
    if parsed.scheme not in {"http", "https"} or not parsed.netloc:
        raise ScriptError(f"Configuration field {field!r} must be an HTTP(S) URL")
    return normalized


def load_config(path: Path) -> Config:
    try:
        config_path = path.expanduser().resolve(strict=True)
        data = json.loads(config_path.read_text(encoding="utf-8"))
    except FileNotFoundError as exc:
        raise ScriptError(f"Configuration file not found: {path}") from exc
    except json.JSONDecodeError as exc:
        raise ScriptError(f"Invalid JSON configuration: {exc}") from exc
    if not isinstance(data, dict):
        raise ScriptError("Configuration root must be a JSON object")

    api_url = _validate_url(_required_string(data, "apiUrl"), "apiUrl")
    project_id = _required_string(data, "projectId")
    try:
        uuid.UUID(project_id)
    except ValueError as exc:
        raise ScriptError("Configuration field 'projectId' must be a UUID") from exc

    branch_value = data.get("branch")
    if branch_value is not None and (
        not isinstance(branch_value, str) or not branch_value.strip()
    ):
        raise ScriptError(
            "Configuration field 'branch' must be a non-empty string when set"
        )
    branch = branch_value.strip() if isinstance(branch_value, str) else None
    if "apiKey" in data:
        raise ScriptError(
            "Configuration field 'apiKey' is not allowed; use LINGORA_API_KEY or --api-key"
        )

    format_name = _required_string(data, "format")
    if format_name != "ANDROID_XML":
        raise ScriptError("Configuration field 'format' must be 'ANDROID_XML'")

    push = _required_object(data, "push")
    files = push.get("files")
    if not isinstance(files, list) or not files:
        raise ScriptError(
            "Configuration field 'push.files' must contain at least one Android base file"
        )
    push_file_values: list[tuple[str, str, str]] = []
    for index, item in enumerate(files):
        if not isinstance(item, dict):
            raise ScriptError(
                f"Configuration field 'push.files[{index}]' must be an object"
            )
        path_value = _required_string(item, "path")
        language = _required_string(item, "language")
        namespace_value = item.get("namespace", data.get("defaultNamespace", ""))
        if not isinstance(namespace_value, str):
            raise ScriptError(
                f"Configuration field 'push.files[{index}].namespace' must be a string"
            )
        push_file_values.append((path_value, language, namespace_value.strip()))

    base_language = push_file_values[0][1]
    namespace = push_file_values[0][2]
    if any(language != base_language for _, language, _ in push_file_values):
        raise ScriptError(
            "All Android base files in 'push.files' must use the same language"
        )
    if any(file_namespace != namespace for _, _, file_namespace in push_file_values):
        raise ScriptError(
            "All Android base files in 'push.files' must use the same namespace"
        )

    force_mode = str(push.get("forceMode", "NO_FORCE")).upper()
    if force_mode not in {"KEEP", "OVERRIDE", "NO_FORCE"}:
        raise ScriptError("Configuration field 'push.forceMode' is invalid")
    override_mode = str(push.get("overrideMode", "RECOMMENDED")).upper()
    if override_mode not in {"RECOMMENDED", "ALL"}:
        raise ScriptError("Configuration field 'push.overrideMode' is invalid")
    error_choice = push.get("errorOnUnresolvedConflict", "auto")
    error_mapping = {"yes": True, "no": False, "auto": None}
    if error_choice not in error_mapping:
        raise ScriptError(
            "Configuration field 'push.errorOnUnresolvedConflict' must be yes, no, or auto"
        )
    tag_new_keys = _string_list(push, "tagNewKeys", required=False)

    pull = _required_object(data, "pull")
    target_languages = _string_list(pull, "languages", required=True)
    if base_language in target_languages:
        raise ScriptError(
            "Configuration field 'pull.languages' must exclude the base language"
        )
    pull_namespaces = _string_list(pull, "namespaces", required=False) or (namespace,)
    if pull_namespaces != (namespace,):
        raise ScriptError(
            "Configuration field 'pull.namespaces' must match push.files[0].namespace"
        )
    pull_states = _string_list(pull, "states", required=False) or (
        "TRANSLATED",
        "REVIEWED",
    )
    if any(
        state not in {"UNTRANSLATED", "TRANSLATED", "REVIEWED"} for state in pull_states
    ):
        raise ScriptError("Configuration field 'pull.states' contains an invalid state")
    file_structure_template = _required_string(pull, "fileStructureTemplate")
    pull_empty_dir = _boolean(pull, "emptyDir", False)

    raw_deliveries = data.get("contentDeliveries", {})
    if not isinstance(raw_deliveries, dict):
        raise ScriptError("Configuration field 'contentDeliveries' must be an object")
    content_deliveries: dict[str, str] = {}
    for name, value in raw_deliveries.items():
        if not isinstance(name, str) or not name.strip() or not isinstance(value, str):
            raise ScriptError("Content Delivery names and URLs must be strings")
        content_deliveries[name] = _validate_url(value, f"contentDeliveries.{name}")

    base_dir = config_path.parent
    push_files = tuple(
        PushFile(
            path=(base_dir / path_value).resolve(),
            language=language,
            namespace=file_namespace,
        )
        for path_value, language, file_namespace in push_file_values
    )
    if len({item.path for item in push_files}) != len(push_files):
        raise ScriptError("Configuration field 'push.files' contains duplicate paths")
    if len({item.path.name for item in push_files}) != len(push_files):
        raise ScriptError(
            "Android base files in 'push.files' must have unique filenames"
        )
    pull_path = (base_dir / _required_string(pull, "path")).resolve()
    output_paths = [
        render_export_path(file_structure_template, language, namespace)
        for language in target_languages
    ]
    if len(set(output_paths)) != len(output_paths):
        raise ScriptError("Target languages resolve to duplicate pull output paths")
    if any(
        pull_path.joinpath(*output_path.parts) == push_file.path
        for output_path in output_paths
        for push_file in push_files
    ):
        raise ScriptError(
            "Pull output must not overwrite the base Android resource file"
        )

    return Config(
        config_path=config_path,
        api_url=api_url,
        project_id=project_id,
        branch=branch,
        format=format_name,
        base_language=base_language,
        target_languages=target_languages,
        namespace=namespace,
        push_files=push_files,
        pull_path=pull_path,
        push_force_mode=force_mode,
        push_override_mode=override_mode,
        push_error_on_unresolved_conflict=error_mapping[error_choice],
        push_override_key_descriptions=_boolean(push, "overrideKeyDescriptions", True),
        push_convert_placeholders_to_icu=_boolean(
            push, "convertPlaceholdersToIcu", True
        ),
        push_tag_new_keys=tag_new_keys,
        push_remove_other_keys=_boolean(push, "removeOtherKeys", False),
        pull_namespaces=pull_namespaces,
        pull_states=pull_states,
        pull_file_structure_template=file_structure_template,
        pull_empty_dir=pull_empty_dir,
        content_deliveries=content_deliveries,
    )


def default_config_path() -> Path:
    android_config = Path(__file__).resolve().with_name(DEFAULT_CONFIG)
    return android_config if android_config.is_file() else Path(DEFAULT_CONFIG)


def select_branch(
    command_line: Optional[str],
    configured: Optional[str],
    environ: Mapping[str, str] = os.environ,
) -> str:
    candidate = command_line or environ.get("LINGORA_BRANCH") or configured
    if not candidate or not candidate.strip():
        raise ScriptError(
            "Branch is required: pass --branch, set LINGORA_BRANCH, or configure 'branch'"
        )
    return candidate.strip()


def require_api_key(
    command_line: Optional[str] = None,
    environ: Mapping[str, str] = os.environ,
) -> str:
    value = command_line or environ.get("LINGORA_API_KEY") or ""
    if not value.strip():
        raise ScriptError("API key is required: pass --api-key or set LINGORA_API_KEY")
    return value.strip()


def _read_limited(stream: Any, limit: int = MAX_RESPONSE_BYTES) -> bytes:
    body = stream.read(limit + 1)
    if len(body) > limit:
        raise ScriptError(f"Remote response exceeds {limit} bytes")
    return body


def _error_message(status: int, body: bytes) -> str:
    try:
        payload = json.loads(body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError):
        return f"HTTP {status}"
    if not isinstance(payload, dict):
        return f"HTTP {status}"
    message = payload.get("message")
    error = payload.get("error")
    code = error.get("code") if isinstance(error, dict) else None
    suffix = f" ({code})" if code is not None else ""
    details = error.get("details") if isinstance(error, dict) else None
    conflict_suffix = _format_conflict_keys(details)
    return f"HTTP {status}: {message or 'request failed'}{suffix}{conflict_suffix}"


def _format_conflict_keys(value: Any) -> str:
    if not isinstance(value, list):
        return ""
    keys = list(
        dict.fromkeys(
            str(item["key"])
            for item in value
            if isinstance(item, dict)
            and isinstance(item.get("key"), str)
            and item["key"]
        )
    )
    if not keys:
        return ""
    visible = ", ".join(keys[:10])
    remaining = max(0, len(keys) - 10)
    more = f" (+{remaining} more)" if remaining else ""
    return f"; conflicting keys: {visible}{more}"


def http_request(
    url: str,
    *,
    method: str = "GET",
    api_key: Optional[str] = None,
    data: Optional[bytes] = None,
    content_type: Optional[str] = None,
    timeout: float = DEFAULT_TIMEOUT_SECONDS,
) -> HttpResponse:
    headers = {"Accept": "application/json"}
    if api_key:
        headers["X-API-Key"] = api_key
    if content_type:
        headers["Content-Type"] = content_type
    request = Request(url, data=data, headers=headers, method=method)
    try:
        with urlopen(request, timeout=timeout) as response:
            return HttpResponse(
                status=response.status,
                headers=dict(response.headers.items()),
                body=_read_limited(response),
            )
    except HTTPError as exc:
        body = _read_limited(exc)
        raise ScriptError(_error_message(exc.code, body)) from exc
    except URLError as exc:
        raise ScriptError(f"Request failed: {exc.reason}") from exc


def api_data(response: HttpResponse) -> Any:
    try:
        payload = json.loads(response.body.decode("utf-8"))
    except (UnicodeDecodeError, json.JSONDecodeError) as exc:
        raise ScriptError("Lingora returned an invalid JSON response") from exc
    if not isinstance(payload, dict) or payload.get("code") != response.status:
        raise ScriptError("Lingora returned an invalid API response envelope")
    return payload.get("data")


def resolve_branch(
    config: Config,
    api_key: str,
    branch_name: str,
    *,
    timeout: float = DEFAULT_TIMEOUT_SECONDS,
    requester: Requester = http_request,
) -> str:
    response = requester(
        f"{config.api_url}/api/v1/projects/{config.project_id}/branches",
        api_key=api_key,
        timeout=timeout,
    )
    branches = api_data(response)
    if not isinstance(branches, list):
        raise ScriptError("Branch list response must contain an array")
    matches = [
        item
        for item in branches
        if isinstance(item, dict) and item.get("name") == branch_name
    ]
    if len(matches) > 1:
        raise ScriptError(
            f"Branch name {branch_name!r} is not unique in Project {config.project_id}"
        )
    if not matches:
        available = sorted(
            str(item["name"])
            for item in branches
            if isinstance(item, dict) and isinstance(item.get("name"), str)
        )
        choices = ", ".join(available) if available else "(none)"
        raise ScriptError(
            f"Branch {branch_name!r} not found. Available branches: {choices}"
        )
    branch_id = matches[0].get("id")
    try:
        return str(uuid.UUID(str(branch_id)))
    except ValueError as exc:
        raise ScriptError(f"Branch {branch_name!r} has an invalid UUID") from exc


def build_multipart(
    fields: dict[str, str], files: tuple[tuple[str, bytes], ...]
) -> tuple[bytes, str]:
    boundary = f"----lingora-{uuid.uuid4().hex}"
    chunks: list[bytes] = []
    for name, value in fields.items():
        chunks.extend(
            [
                f"--{boundary}\r\n".encode(),
                f'Content-Disposition: form-data; name="{name}"\r\n\r\n'.encode(),
                value.encode("utf-8"),
                b"\r\n",
            ]
        )
    for filename, content in files:
        safe_filename = filename.replace('"', "").replace("\r", "").replace("\n", "")
        chunks.extend(
            [
                f"--{boundary}\r\n".encode(),
                (
                    'Content-Disposition: form-data; name="files"; '
                    f'filename="{safe_filename}"\r\n'
                ).encode(),
                b"Content-Type: application/xml\r\n\r\n",
                content,
                b"\r\n",
            ]
        )
    chunks.append(f"--{boundary}--\r\n".encode())
    return b"".join(chunks), f"multipart/form-data; boundary={boundary}"


def push_resources(
    config: Config,
    api_key: str,
    branch_name: str,
    force_mode: str,
    remove_other_keys: bool,
    *,
    timeout: float = DEFAULT_TIMEOUT_SECONDS,
    requester: Requester = http_request,
) -> dict[str, Any]:
    resources = parse_push_resources(config)
    if not resources:
        raise ScriptError(
            "Configured Android base files contain no supported resources: "
            + ", ".join(str(item.path) for item in config.push_files)
        )
    branch_id = resolve_branch(
        config, api_key, branch_name, timeout=timeout, requester=requester
    )
    params = {
        "forceMode": force_mode,
        "overrideMode": config.push_override_mode,
        "errorOnUnresolvedConflict": config.push_error_on_unresolved_conflict,
        "languageMappings": None,
        "fileMappings": [
            {
                "fileName": item.path.name,
                "namespace": item.namespace,
                "format": config.format,
                "languageTag": item.language,
                "languageTagsToImport": [],
            }
            for item in config.push_files
        ],
        "overrideKeyDescriptions": config.push_override_key_descriptions,
        "convertPlaceholdersToIcu": config.push_convert_placeholders_to_icu,
        "createNewKeys": True,
        "tagNewKeys": list(config.push_tag_new_keys),
        "removeOtherKeys": remove_other_keys,
    }
    body, content_type = build_multipart(
        {"params": json.dumps(params, separators=(",", ":"))},
        tuple((item.path.name, item.path.read_bytes()) for item in config.push_files),
    )
    response = requester(
        f"{config.api_url}/api/v1/projects/{config.project_id}/branches/"
        f"{branch_id}/imports/single-step",
        method="POST",
        api_key=api_key,
        data=body,
        content_type=content_type,
        timeout=timeout,
    )
    result = api_data(response)
    if not isinstance(result, dict):
        raise ScriptError("Import response data must be an object")
    conflicts = result.get("unresolvedConflicts")
    conflict_summary = _format_conflict_keys(conflicts)
    if conflict_summary:
        print(
            "Push warning: Lingora kept protected or disabled server values"
            f"{conflict_summary}",
            file=sys.stderr,
        )
    print(
        f"Push completed: branch={branch_name} branchId={branch_id} "
        f"affected={result.get('affected', 0)} deleted={result.get('deleted', 0)}"
    )
    return result


def android_language_tag(language: str) -> str:
    parts = language.replace("_", "-").split("-")
    base = parts[0].lower()
    region = next(
        (
            part
            for part in parts[1:]
            if (len(part) == 2 and part.isalpha())
            or (len(part) == 3 and part.isdigit())
        ),
        None,
    )
    return base if region is None else f"{base}-r{region.upper()}"


def render_export_path(
    template: str,
    language: str,
    namespace: str,
) -> PurePosixPath:
    try:
        rendered = template.format(
            namespace=namespace,
            languageTag=language,
            androidLanguageTag=android_language_tag(language),
            snakeLanguageTag=language.replace("-", "_"),
            extension="xml",
        )
    except (KeyError, ValueError) as exc:
        raise ScriptError(
            "Configuration field 'pull.fileStructureTemplate' is invalid"
        ) from exc
    return _safe_zip_path(rendered)


def _safe_zip_path(name: str) -> PurePosixPath:
    if not name or "\\" in name or name.startswith(("/", "\\")):
        raise ScriptError(f"Unsafe ZIP entry path: {name!r}")
    path = PurePosixPath(name)
    if any(part in {"", ".", ".."} for part in path.parts):
        raise ScriptError(f"Unsafe ZIP entry path: {name!r}")
    if path.parts and path.parts[0].endswith(":"):
        raise ScriptError(f"Unsafe ZIP entry path: {name!r}")
    return path


def extract_export(
    response: HttpResponse,
    destination: Path,
    languages: tuple[str, ...],
    file_structure_template: str,
    namespace: str,
) -> None:
    response_headers = {
        key.casefold(): value for key, value in response.headers.items()
    }
    content_type = response_headers.get("content-type", "").split(";", 1)[0].lower()
    archive = io.BytesIO(response.body)
    if content_type == "application/zip" or zipfile.is_zipfile(archive):
        archive.seek(0)
        with zipfile.ZipFile(archive) as bundle:
            entries = bundle.infolist()
            if len(entries) > MAX_ZIP_ENTRIES:
                raise ScriptError("Export ZIP contains too many entries")
            if sum(item.file_size for item in entries) > MAX_EXPANDED_ZIP_BYTES:
                raise ScriptError("Expanded export ZIP is too large")
            for item in entries:
                path = _safe_zip_path(item.filename)
                mode = (item.external_attr >> 16) & 0o170000
                if mode == 0o120000:
                    raise ScriptError(
                        f"ZIP symbolic links are not allowed: {item.filename}"
                    )
                target = destination.joinpath(*path.parts)
                if item.is_dir():
                    target.mkdir(parents=True, exist_ok=True)
                    continue
                target.parent.mkdir(parents=True, exist_ok=True)
                with bundle.open(item) as source, target.open("wb") as output:
                    shutil.copyfileobj(source, output)
        return

    if len(languages) != 1:
        raise ScriptError("Lingora returned one XML file for a multi-language export")
    try:
        ET.fromstring(response.body)
    except ET.ParseError as exc:
        raise ScriptError(
            "Lingora export is neither a ZIP nor valid Android XML"
        ) from exc
    relative = render_export_path(file_structure_template, languages[0], namespace)
    target = destination.joinpath(*relative.parts)
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_bytes(response.body)


def _replace_directory(staging: Path, destination: Path) -> None:
    destination.parent.mkdir(parents=True, exist_ok=True)
    backup = destination.parent / f".{destination.name}.backup-{uuid.uuid4().hex}"
    moved_old = False
    try:
        if destination.exists():
            os.replace(destination, backup)
            moved_old = True
        os.replace(staging, destination)
    except OSError:
        if moved_old and not destination.exists() and backup.exists():
            os.replace(backup, destination)
        raise
    finally:
        if backup.exists():
            shutil.rmtree(backup)


def _install_export(staging: Path, destination: Path, empty_dir: bool) -> None:
    if empty_dir:
        _replace_directory(staging, destination)
        return
    destination.mkdir(parents=True, exist_ok=True)
    for source in sorted(path for path in staging.rglob("*") if path.is_file()):
        relative = source.relative_to(staging)
        target = destination / relative
        target.parent.mkdir(parents=True, exist_ok=True)
        os.replace(source, target)


def pull_resources(
    config: Config,
    api_key: str,
    branch_name: str,
    *,
    timeout: float = DEFAULT_TIMEOUT_SECONDS,
    requester: Requester = http_request,
) -> None:
    branch_id = resolve_branch(
        config, api_key, branch_name, timeout=timeout, requester=requester
    )
    payload = {
        "languages": list(config.target_languages),
        "namespaces": list(config.pull_namespaces),
        "states": list(config.pull_states),
        "includeEmptyTranslations": False,
        "format": config.format,
        "fileStructureTemplate": config.pull_file_structure_template,
        "zip": True,
    }
    response = requester(
        f"{config.api_url}/api/v1/projects/{config.project_id}/branches/{branch_id}/exports",
        method="POST",
        api_key=api_key,
        data=json.dumps(payload, separators=(",", ":")).encode("utf-8"),
        content_type="application/json",
        timeout=timeout,
    )

    destination = config.pull_path
    if destination.exists() and not destination.is_dir():
        raise ScriptError(f"Pull path is not a directory: {destination}")
    destination.parent.mkdir(parents=True, exist_ok=True)
    staging = Path(
        tempfile.mkdtemp(prefix=f".{destination.name}.staging-", dir=destination.parent)
    )
    try:
        extract_export(
            response,
            staging,
            config.target_languages,
            config.pull_file_structure_template,
            config.namespace,
        )
        exported_languages = validate_resources(
            config,
            pull_path=staging,
            fallback_incomplete_arrays=True,
        )
        _install_export(staging, destination, config.pull_empty_dir)
    finally:
        if staging.exists():
            shutil.rmtree(staging)
    print(
        f"Pull completed: branch={branch_name} branchId={branch_id} "
        f"requested={','.join(config.target_languages)} "
        f"exported={','.join(exported_languages) or 'none'} output={destination}"
    )
    if not exported_languages:
        print(
            "Pull notice: no non-empty translations matched states="
            f"{','.join(config.pull_states)}; adding a language alone does not create "
            "an Android locale file."
        )


def _local_name(tag: str) -> str:
    return tag.rsplit("}", 1)[-1]


def _element_text(element: ET.Element) -> str:
    return "".join(element.itertext())


def parse_android_resources(path: Path) -> dict[str, AndroidResource]:
    try:
        root = ET.parse(path).getroot()
    except FileNotFoundError as exc:
        raise ScriptError(f"Android resource file not found: {path}") from exc
    except ET.ParseError as exc:
        raise ScriptError(f"Invalid Android XML {path}: {exc}") from exc
    if _local_name(root.tag) != "resources":
        raise ScriptError(f"Android XML root must be <resources>: {path}")

    resources: dict[str, AndroidResource] = {}
    for element in root:
        kind = _local_name(element.tag)
        if kind not in {"string", "plurals", "string-array"}:
            continue
        name = element.get("name", "").strip()
        if not name:
            raise ScriptError(f"Android resource without a name in {path}")
        if name in resources:
            raise ScriptError(f"Duplicate Android resource {name!r} in {path}")
        if kind == "string":
            values: Any = _element_text(element)
        elif kind == "string-array":
            values = tuple(
                _element_text(item)
                for item in element
                if _local_name(item.tag) == "item"
            )
        else:
            forms: dict[str, str] = {}
            for item in element:
                if _local_name(item.tag) != "item":
                    continue
                quantity = item.get("quantity", "")
                if quantity not in ANDROID_PLURAL_QUANTITIES:
                    raise ScriptError(
                        f"Unsupported plural quantity {quantity!r} for {name!r} in {path}"
                    )
                if quantity in forms:
                    raise ScriptError(
                        f"Duplicate plural quantity {quantity!r} for {name!r}"
                    )
                forms[quantity] = _element_text(item)
            if "other" not in forms:
                raise ScriptError(
                    f"Plural resource {name!r} must include quantity 'other'"
                )
            values = forms
        resources[name] = AndroidResource(kind=kind, values=values)
    return resources


def parse_push_resources(config: Config) -> dict[str, AndroidResource]:
    resources: dict[str, AndroidResource] = {}
    resource_paths: dict[str, Path] = {}
    for item in config.push_files:
        parsed = parse_android_resources(item.path)
        duplicate_names = sorted(set(resources) & set(parsed))
        if duplicate_names:
            name = duplicate_names[0]
            raise ScriptError(
                f"Duplicate Android resource {name!r} across "
                f"{resource_paths[name]} and {item.path}"
            )
        resources.update(parsed)
        resource_paths.update({name: item.path for name in parsed})
    return resources


def placeholder_signature(value: str) -> tuple[tuple[int, str], ...]:
    implicit_position = 1
    previous_position: Optional[int] = None
    signature: list[tuple[int, str]] = []
    for match in JAVA_PLACEHOLDER_RE.finditer(value):
        specifier = match.group(5).lower()
        if specifier in {"%", "n"}:
            continue
        flags = match.group(2)
        if match.group(1):
            position = int(match.group(1))
        elif "<" in flags:
            if previous_position is None:
                raise ScriptError(f"Invalid relative printf placeholder in {value!r}")
            position = previous_position
        else:
            position = implicit_position
            implicit_position += 1
        previous_position = position
        if specifier.startswith("t"):
            category = "datetime"
        elif specifier[-1:] in {"d", "o", "x"}:
            category = "integer"
        elif specifier[-1:] in {"a", "e", "f", "g"}:
            category = "float"
        elif specifier[-1:] == "c":
            category = "character"
        elif specifier[-1:] == "b":
            category = "boolean"
        else:
            category = "string"
        signature.append((position, category))
    return tuple(sorted(signature))


def _validate_resource_value(
    key: str, base: AndroidResource, target: AndroidResource, target_path: Path
) -> None:
    if base.kind != target.kind:
        raise ScriptError(
            f"Resource type mismatch for {key!r} in {target_path}: "
            f"expected {base.kind}, got {target.kind}"
        )
    if base.kind == "string":
        if placeholder_signature(base.values) != placeholder_signature(target.values):
            raise ScriptError(f"Placeholder mismatch for {key!r} in {target_path}")
        return
    if base.kind == "string-array":
        if len(base.values) != len(target.values):
            raise ScriptError(f"Array length mismatch for {key!r} in {target_path}")
        for index, (base_value, target_value) in enumerate(
            zip(base.values, target.values)
        ):
            if placeholder_signature(base_value) != placeholder_signature(target_value):
                raise ScriptError(
                    f"Placeholder mismatch for {key!r}[{index}] in {target_path}"
                )
        return

    base_signatures = {placeholder_signature(value) for value in base.values.values()}
    if len(base_signatures) != 1:
        raise ScriptError(
            f"Base plural {key!r} has inconsistent placeholder signatures"
        )
    expected = next(iter(base_signatures))
    for quantity, value in target.values.items():
        if placeholder_signature(value) != expected:
            raise ScriptError(
                f"Placeholder mismatch for {key!r}[{quantity}] in {target_path}"
            )


def _remove_incomplete_arrays(
    path: Path, base: dict[str, AndroidResource]
) -> tuple[tuple[str, int, int], ...]:
    parser = ET.XMLParser(target=ET.TreeBuilder(insert_comments=True))
    try:
        tree = ET.parse(path, parser=parser)
    except ET.ParseError as exc:
        raise ScriptError(f"Invalid Android XML {path}: {exc}") from exc
    root = tree.getroot()
    removed: list[tuple[str, int, int]] = []
    for element in list(root):
        if (
            not isinstance(element.tag, str)
            or _local_name(element.tag) != "string-array"
        ):
            continue
        name = element.get("name", "").strip()
        base_resource = base.get(name)
        if base_resource is None or base_resource.kind != "string-array":
            continue
        item_count = sum(
            1
            for item in element
            if isinstance(item.tag, str) and _local_name(item.tag) == "item"
        )
        expected_count = len(base_resource.values)
        if item_count < expected_count:
            root.remove(element)
            removed.append((name, item_count, expected_count))
    if removed:
        ET.indent(tree, space="  ")
        tree.write(path, encoding="utf-8", xml_declaration=True)
    return tuple(removed)


def validate_resources(
    config: Config,
    *,
    pull_path: Optional[Path] = None,
    fallback_incomplete_arrays: bool = False,
) -> tuple[str, ...]:
    destination = pull_path or config.pull_path
    base = parse_push_resources(config)
    expected = {
        render_export_path(
            config.pull_file_structure_template,
            language,
            config.namespace,
        ): language
        for language in config.target_languages
    }
    validated_languages = []
    fallbacks = []
    for relative, language in sorted(expected.items(), key=lambda item: str(item[0])):
        path = destination.joinpath(*relative.parts)
        if not path.is_file():
            continue
        if fallback_incomplete_arrays:
            fallbacks.extend(
                (language, name, actual, expected_count)
                for name, actual, expected_count in _remove_incomplete_arrays(
                    path, base
                )
            )
        target = parse_android_resources(path)
        unknown = sorted(set(target) - set(base))
        if unknown:
            raise ScriptError(
                f"Generated resource contains unknown keys in {path}: {', '.join(unknown)}"
            )
        for key, resource in target.items():
            _validate_resource_value(key, base[key], resource, path)
        validated_languages.append(language)
    if fallbacks:
        details = ", ".join(
            f"{language}:{name}({actual}/{expected_count})"
            for language, name, actual, expected_count in fallbacks
        )
        print(f"Array fallback applied: {details}")
    print(
        f"Validation completed: languages={','.join(validated_languages) or 'none'} "
        f"directory={destination}"
    )
    return tuple(validated_languages)


def build_parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--config", type=Path, default=default_config_path())
    parser.add_argument("--api-key")
    parser.add_argument("--timeout", type=float, default=DEFAULT_TIMEOUT_SECONDS)
    subparsers = parser.add_subparsers(dest="command", required=True)

    push = subparsers.add_parser("push", help="Push the base Android XML to a Branch")
    push.add_argument("--branch")
    push.add_argument("--force-mode", choices=("KEEP", "OVERRIDE", "NO_FORCE"))
    push.add_argument("--remove-other-keys", action="store_true", default=None)

    pull = subparsers.add_parser(
        "pull", help="Pull configured Android XML from a Branch"
    )
    pull.add_argument("--branch")

    mock_ci = subparsers.add_parser(
        "mock-ci", help="Run the local CI sequence: push, then pull"
    )
    mock_ci.add_argument("--branch")

    subparsers.add_parser("validate", help="Validate the generated Android resources")
    return parser


def main(argv: Optional[list[str]] = None) -> int:
    args = build_parser().parse_args(argv)
    try:
        if args.timeout <= 0:
            raise ScriptError("--timeout must be greater than zero")
        config = load_config(args.config)
        if args.command == "validate":
            validate_resources(config)
            return 0
        branch = select_branch(args.branch, config.branch, os.environ)
        api_key = require_api_key(args.api_key, os.environ)
        if args.command == "push":
            force_mode = args.force_mode or config.push_force_mode
            remove_other_keys = (
                config.push_remove_other_keys
                if args.remove_other_keys is None
                else args.remove_other_keys
            )
            push_resources(
                config,
                api_key,
                branch,
                force_mode,
                remove_other_keys,
                timeout=args.timeout,
            )
        elif args.command == "pull":
            pull_resources(config, api_key, branch, timeout=args.timeout)
        else:
            push_resources(
                config,
                api_key,
                branch,
                config.push_force_mode,
                config.push_remove_other_keys,
                timeout=args.timeout,
            )
            pull_resources(config, api_key, branch, timeout=args.timeout)
        return 0
    except (ScriptError, OSError, zipfile.BadZipFile) as exc:
        print(f"error: {exc}", file=sys.stderr)
        return 1


if __name__ == "__main__":
    raise SystemExit(main())
