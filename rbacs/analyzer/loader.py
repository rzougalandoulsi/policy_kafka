"""File loading and configuration parsing. No network, no Confluent calls."""

from __future__ import annotations

import copy
import json
from pathlib import Path
from typing import Any, Dict, Mapping, Optional

from .errors import ConfigError, InputError
from .models import RULE_IDS, Config

DEFAULT_CONFIG: Dict[str, Any] = {
    "analysis": {"readOnly": True, "failOnAmbiguousScope": True},
    "rules": {rule_id: True for rule_id in RULE_IDS},
    "consolidation": {
        "minimumBindingsToMerge": 3,
        "minimumPrefixesToMerge": 2,
        "minimumPrefixLength": 3,
        "requireAuthorizedPrefix": True,
        "allowAccessExpansion": False,
        "includeReviewCandidates": True,
    },
    "authorizedPrefixes": {},
    "verifiedPatternSemantics": ["Topic", "Group", "TransactionalId"],
}


def default_config_path() -> Path:
    return Path(__file__).resolve().parent.parent / "config" / "analyzer.json"


def read_json(path: Path, error_type: type = InputError, what: str = "input") -> Any:
    """Parse a JSON file, turning any failure into a one-line error."""
    if not path.exists():
        raise error_type(f"{what} file not found: {path}")
    if not path.is_file():
        raise error_type(f"{what} path is not a file: {path}")
    try:
        text = path.read_text(encoding="utf-8")
    except OSError as exc:
        raise error_type(f"cannot read {what} file {path}: {exc.strerror or exc}") from exc
    try:
        return json.loads(text)
    except json.JSONDecodeError as exc:
        raise error_type(
            f"{what} file {path} is not valid JSON: {exc.msg} (line {exc.lineno}, column {exc.colno})"
        ) from exc


def load_inventory(path: Path) -> Any:
    return read_json(path, InputError, "input")


def load_config(path: Optional[Path]) -> Config:
    config_path = path or default_config_path()
    raw = read_json(config_path, ConfigError, "config")
    if not isinstance(raw, dict):
        raise ConfigError(f"config file {config_path} must contain a JSON object")
    return parse_config(raw)


def _merged(raw: Mapping[str, Any]) -> Dict[str, Any]:
    merged = copy.deepcopy(DEFAULT_CONFIG)
    for section, value in raw.items():
        if isinstance(value, dict) and isinstance(merged.get(section), dict):
            merged[section].update(value)
        else:
            merged[section] = value
    return merged


def _require_bool(section: Mapping[str, Any], key: str, where: str) -> bool:
    value = section.get(key)
    if not isinstance(value, bool):
        raise ConfigError(f"config {where}.{key} must be true or false")
    return value


def _require_int(section: Mapping[str, Any], key: str, where: str, minimum: int) -> int:
    value = section.get(key)
    if isinstance(value, bool) or not isinstance(value, int) or value < minimum:
        raise ConfigError(f"config {where}.{key} must be an integer >= {minimum}")
    return value


def parse_config(raw: Mapping[str, Any]) -> Config:
    merged = _merged(raw)
    analysis = merged["analysis"]
    rules = merged["rules"]
    consolidation = merged["consolidation"]

    for section_name in ("analysis", "rules", "consolidation", "authorizedPrefixes"):
        if not isinstance(merged[section_name], dict):
            raise ConfigError(f"config {section_name} must be a JSON object")

    read_only = _require_bool(analysis, "readOnly", "analysis")
    if not read_only:
        raise ConfigError("analysis.readOnly must be true; this analyzer never modifies Confluent Cloud")

    enabled = []
    for rule_id, flag in rules.items():
        if rule_id not in RULE_IDS:
            raise ConfigError(f"config rules contains unknown rule id {rule_id!r}")
        if not isinstance(flag, bool):
            raise ConfigError(f"config rules.{rule_id} must be true or false")
        if flag:
            enabled.append(rule_id)

    prefixes = []
    for owner, values in merged["authorizedPrefixes"].items():
        if not isinstance(values, list):
            raise ConfigError(f"config authorizedPrefixes.{owner} must be a list of strings")
        for value in values:
            if not isinstance(value, str) or not value:
                raise ConfigError(f"config authorizedPrefixes.{owner} contains an empty or non-string prefix")
            prefixes.append(value)

    verified = merged["verifiedPatternSemantics"]
    if not isinstance(verified, list) or not all(isinstance(v, str) and v for v in verified):
        raise ConfigError("config verifiedPatternSemantics must be a list of resource type names")

    return Config(
        read_only=read_only,
        fail_on_ambiguous_scope=_require_bool(analysis, "failOnAmbiguousScope", "analysis"),
        enabled_rules=frozenset(enabled),
        minimum_bindings_to_merge=_require_int(consolidation, "minimumBindingsToMerge", "consolidation", 2),
        minimum_prefixes_to_merge=_require_int(consolidation, "minimumPrefixesToMerge", "consolidation", 2),
        minimum_prefix_length=_require_int(consolidation, "minimumPrefixLength", "consolidation", 1),
        require_authorized_prefix=_require_bool(consolidation, "requireAuthorizedPrefix", "consolidation"),
        allow_access_expansion=_require_bool(consolidation, "allowAccessExpansion", "consolidation"),
        include_review_candidates=_require_bool(consolidation, "includeReviewCandidates", "consolidation"),
        authorized_prefixes=tuple(sorted(set(prefixes))),
        verified_pattern_semantics=frozenset(verified),
        raw=merged,
    )
