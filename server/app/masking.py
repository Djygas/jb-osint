"""Маскирование PII перед отправкой во внешние модели. Без сторонних зависимостей."""
from __future__ import annotations

import json
import re
from typing import Any

PLACEHOLDER = "***"

EMAIL = re.compile(r"(?i)\b[A-Z0-9._%+-]+@[A-Z0-9.-]+\.[A-Z]{2,}\b")
PHONE = re.compile(r"(?<!\d)(?:\+?\d[\d\s().-]{8,}\d)(?!\d)")
IPV4 = re.compile(r"\b(?:\d{1,3}\.){3}\d{1,3}\b")
TOKEN = re.compile(r"(?i)\b(?:bearer|token|api[_-]?key|secret)\s*[:=]?\s*([A-Za-z0-9_\-.]{16,})")

SENSITIVE_KEYS = {
    "email", "e-mail", "mail", "phone", "tel", "telephone", "authorization",
    "cookie", "set-cookie", "token", "access_token", "refresh_token", "password",
    "passwd", "secret", "api_key", "apikey", "x-api-key",
}


def mask_text(value: str) -> str:
    """Маскирует email, телефоны, IPv4 и длинные токены в произвольном тексте."""
    if not value:
        return value
    out = EMAIL.sub(PLACEHOLDER, value)
    out = PHONE.sub(PLACEHOLDER, out)
    out = IPV4.sub(PLACEHOLDER, out)
    out = TOKEN.sub(lambda m: m.group(0).replace(m.group(1), PLACEHOLDER), out)
    return out


def _mask_by_key(key: str, value: Any) -> Any:
    if isinstance(value, dict):
        return mask_json(value)
    if isinstance(value, list):
        return [_mask_by_key(key, item) for item in value]
    if isinstance(value, str):
        if key.lower() in SENSITIVE_KEYS:
            return PLACEHOLDER
        return mask_text(value)
    return value


def mask_json(obj: Any) -> Any:
    if isinstance(obj, dict):
        return {key: _mask_by_key(str(key), value) for key, value in obj.items()}
    if isinstance(obj, list):
        return [mask_json(item) for item in obj]
    if isinstance(obj, str):
        return mask_text(obj)
    return obj


def mask_har(har: dict) -> tuple[dict, dict]:
    """
    Маскирует HAR целиком и возвращает (masked, counts).
    counts пишется в лог: сырой трафик и PII в логи не попадают.
    """
    text = json.dumps(har, ensure_ascii=False)
    counts = {
        "emails": len(EMAIL.findall(text)),
        "phones": len(PHONE.findall(text)),
        "ips": len(IPV4.findall(text)),
        "tokens": len(TOKEN.findall(text)),
    }
    return mask_json(har), counts
