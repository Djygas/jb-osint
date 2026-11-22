"""Генерация клиента из уже замаскированного HAR.

Провайдер по умолчанию — детерминированный разбор HAR (без отправки данных наружу).
Если задан OPENAI_API_KEY, клиент генерирует модель по маскированному трафику.
"""
from __future__ import annotations

import json
import re
from typing import Any

import httpx

from .config import settings

SAFE_HEADERS = {"accept", "accept-language", "content-type", "user-agent"}


def fallback_client(har: dict) -> tuple[str, dict]:
    """Собирает рабочий клиент из request-ов HAR без внешней модели."""
    entries = []
    if isinstance(har, dict):
        entries = har.get("log", {}).get("entries", []) or []
    base = ""
    headers: dict[str, str] = {}
    endpoints: list[tuple[str, str]] = []

    for entry in entries[:200]:
        request = entry.get("request", {}) or {}
        url = str(request.get("url", ""))
        if not base and url:
            match = re.match(r"(https?://[^/]+)", url)
            if match:
                base = match.group(1)
        path = re.sub(r"^https?://[^/]+", "", url) or "/"
        method = str(request.get("method", "GET")).upper()
        if (method, path) not in endpoints:
            endpoints.append((method, path))
        for header in request.get("headers", []) or []:
            name = str(header.get("name", ""))
            if name.lower() in SAFE_HEADERS and name not in headers:
                headers[name] = str(header.get("value", ""))

    base = base or "https://example.com"
    lines = [
        '"""Клиент, собранный JB OSINT из HAR. Проверьте код перед запуском."""',
        "import requests",
        "",
        f'BASE_URL = "{base}"',
        "HEADERS = " + json.dumps(headers, ensure_ascii=False, indent=4),
        "",
        "",
        "def _get(path, **params):",
        "    return requests.get(BASE_URL + path, headers=HEADERS, params=params, timeout=20)",
        "",
    ]
    for index, (method, path) in enumerate(endpoints[:20]):
        name = "endpoint_" + re.sub(r"[^a-zA-Z0-9]+", "_", path).strip("_")[:40]
        name = name if name != "endpoint_" else f"endpoint_{index}"
        lines.append("")
        lines.append(f"def {name}(**params):")
        lines.append(f'    """{method} {path}"""')
        if method == "POST":
            lines.append(
                f'    return requests.post(BASE_URL + "{path}", headers=HEADERS, '
                "json=params, timeout=20)"
            )
        else:
            lines.append(f'    return _get("{path}", **params)')
    return "\n".join(lines) + "\n", {
        "input_tokens": 0,
        "output_tokens": 0,
        "total_cost": 0.0,
        "provider": "fallback",
    }


def _extract_code(text: str) -> str:
    match = re.search(r"```(?:python)?\s*(.*?)```", text, re.S)
    return (match.group(1) if match else text).strip()


async def generate_client(masked_har: dict, prompt: str) -> tuple[str, dict]:
    if not settings.openai_api_key:
        return fallback_client(masked_har)

    system = (
        "Ты reverse-инженер API. По замаскированному HAR сгенерируй минимальный "
        "Python-клиент requests с base_url, статичными заголовками и функциями эндпоинтов. "
        "Значения *** — плейсхолдеры, не выдумывай секреты. Верни только код."
    )
    payload: dict[str, Any] = {
        "model": settings.openai_model,
        "messages": [
            {"role": "system", "content": system},
            {
                "role": "user",
                "content": f"Задача: {prompt}\n\nHAR:\n"
                + json.dumps(masked_har, ensure_ascii=False)[:120_000],
            },
        ],
        "temperature": 0.1,
    }
    headers = {"Authorization": f"Bearer {settings.openai_api_key}"}
    async with httpx.AsyncClient(timeout=180) as client:
        response = await client.post(
            f"{settings.openai_base_url.rstrip('/')}/chat/completions",
            headers=headers,
            json=payload,
        )
        response.raise_for_status()
        data = response.json()
    content = data["choices"][0]["message"]["content"]
    usage = data.get("usage", {})
    return _extract_code(content), {
        "input_tokens": int(usage.get("prompt_tokens", 0)),
        "output_tokens": int(usage.get("completion_tokens", 0)),
        "total_cost": 0.0,
        "provider": "openai",
    }
