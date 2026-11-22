"""JB OSINT server: /scrape, /ai/analyze, /api/discovery, /api/v1/rae/generate."""
from __future__ import annotations

import asyncio
import json
import logging
import re
import shutil
import subprocess
import time
import uuid
from collections import defaultdict, deque
from pathlib import Path
from typing import Any, Literal
from urllib.parse import urljoin

import httpx
from fastapi import Depends, FastAPI, Header, HTTPException, Request
from fastapi.responses import JSONResponse
from pydantic import BaseModel, HttpUrl

from .config import settings
from .llm import generate_client
from .masking import mask_har, mask_json

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger("jb-osint")

app = FastAPI(title="JB OSINT Server", version="1.0.0")
STARTED_AT = time.time()

# Простое окно rate limit на процесс: 10 запросов/сек на IP по умолчанию.
_hits: dict[str, deque[float]] = defaultdict(deque)


@app.middleware("http")
async def rate_limit(request: Request, call_next):
    if request.url.path == "/healthz":
        return await call_next(request)
    ip = request.client.host if request.client else "unknown"
    now = time.monotonic()
    window = _hits[ip]
    while window and now - window[0] > 1.0:
        window.popleft()
    if len(window) >= settings.rate_limit_per_sec:
        return JSONResponse({"detail": "Слишком много запросов"}, status_code=429)
    window.append(now)
    started = time.monotonic()
    response = await call_next(request)
    logger.info("%s %s -> %s %.2fs", request.method, request.url.path, response.status_code,
                time.monotonic() - started)
    return response


def require_key(x_api_key: str | None = Header(default=None)) -> None:
    if settings.api_key and x_api_key != settings.api_key:
        raise HTTPException(status_code=401, detail="Неверный X-API-Key")


_TAGS = re.compile(r"<[^>]+>")
_SCRIPTS = re.compile(r"<(script|style|noscript)\b[^>]*>.*?</\1>", re.S | re.I)
_ENTITIES = (
    ("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
    ("&quot;", '"'), ("&#39;", "'"), ("&laquo;", "«"), ("&raquo;", "»"),
)


def _plain(html: str) -> str:
    text = _SCRIPTS.sub(" ", html)
    text = _TAGS.sub(" ", text)
    for entity, char in _ENTITIES:
        text = text.replace(entity, char)
    return re.sub(r"\s+", " ", text).strip()


def _meta(html: str, name: str) -> str:
    """description / og:description: порядок атрибутов в <meta> произвольный."""
    for tag in re.findall(r"<meta[^>]*>", html, re.I):
        key = re.search(r"(?:name|property)=[\"']([^\"']+)[\"']", tag, re.I)
        if not key or key.group(1).lower() != name.lower():
            continue
        value = re.search(r"content=[\"']([^\"']*)[\"']", tag, re.I)
        if value and value.group(1).strip():
            return _plain(value.group(1))
    return ""


def extract_content(html: str, base_url: str) -> dict[str, Any]:
    """Заголовок, описание, текст и ссылки — тот же набор, что разбирает клиент."""
    title_match = re.search(r"<title[^>]*>(.*?)</title>", html, re.S | re.I)
    title = _plain(title_match.group(1))[:300] if title_match else ""
    links: list[str] = []
    for href in re.findall(r"href=[\"']([^\"']+)[\"']", html, re.I):
        absolute = urljoin(base_url, href.strip())
        if absolute.startswith(("http://", "https://")) and absolute not in links:
            links.append(absolute)
        if len(links) >= 60:
            break
    return {
        "title": title,
        "description": (_meta(html, "description") or _meta(html, "og:description"))[:400],
        "text": _plain(html)[:6000],
        "links": links,
    }


class ScrapeRequest(BaseModel):
    url: HttpUrl
    capture_api: bool = False


class AnalyzeRequest(BaseModel):
    facts: list[dict[str, Any]] | dict[str, Any]
    question: str | None = None


class DiscoveryRequest(BaseModel):
    url: HttpUrl


class RaeRequest(BaseModel):
    url: HttpUrl
    prompt: str = "Собери структурированные данные страницы"
    mode: Literal["agent", "manual"] = "agent"
    har: dict[str, Any] | None = None


@app.get("/healthz")
async def healthz() -> dict[str, Any]:
    return {"ok": True, "uptime_sec": round(time.time() - STARTED_AT, 1)}


@app.post("/scrape", dependencies=[Depends(require_key)])
async def scrape(req: ScrapeRequest) -> dict[str, Any]:
    """HTML/JSON без исполнения JS. Сложные защиты — задача scrapling-worker."""
    try:
        async with httpx.AsyncClient(follow_redirects=True, timeout=30, trust_env=False) as client:
            response = await client.get(
                str(req.url), headers={"User-Agent": "JB-OSINT/1.0 (server)"}
            )
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Источник недоступен: {exc}") from exc

    text = response.text[:500_000]
    title = re.search(r"<title[^>]*>(.*?)</title>", text, re.S | re.I)
    payload: dict[str, Any] = {
        "status": response.status_code,
        "url": str(response.url),
        "title": title.group(1).strip()[:300] if title else "",
        "bytes": len(response.content),
        "blocked": response.status_code in (401, 403, 429, 503),
        "json": None,
        "content": extract_content(text, str(response.url)),
    }
    content_type = response.headers.get("content-type", "")
    if "json" in content_type:
        try:
            payload["json"] = response.json()
        except ValueError:
            payload["json"] = None
    if req.capture_api:
        payload["api_hints"] = sorted(
            set(re.findall(r"https?://[^\"'\s]+/api/[^\"'\s]+", text))[:20]
        )
    return payload


def _contradictions(facts: list[dict[str, Any]]) -> list[dict[str, Any]]:
    """Ищет разные значения одного поля у одного объекта — без LLM."""
    grouped: dict[tuple[str, str], set[str]] = defaultdict(set)
    for fact in facts:
        obj = str(fact.get("object") or fact.get("subject") or "").strip()
        field = str(fact.get("field") or fact.get("type") or "").strip()
        value = str(fact.get("value") or "").strip()
        if obj and field and value:
            grouped[(obj, field)].add(value)
    return [
        {"object": obj, "field": field, "values": sorted(values)}
        for (obj, field), values in grouped.items()
        if len(values) > 1
    ]


@app.post("/ai/analyze", dependencies=[Depends(require_key)])
async def analyze(req: AnalyzeRequest) -> dict[str, Any]:
    """Cross-check фактов. Всё, что уходит наружу, предварительно маскируется."""
    facts = req.facts if isinstance(req.facts, list) else [req.facts]
    safe = mask_json(facts)
    findings = _contradictions(safe if isinstance(safe, list) else [safe])
    return {
        "contradictions": findings,
        "notes": [
            "Противоречий не найдено" if not findings else f"Найдено противоречий: {len(findings)}"
        ],
        "provider": "local-rules",
        "question": mask_json(req.question) if req.question else None,
    }


@app.post("/api/discovery", dependencies=[Depends(require_key)])
async def discovery(req: DiscoveryRequest) -> dict[str, Any]:
    """Ищет страницу выдачи ключа и способ авторизации по публичной странице."""
    try:
        async with httpx.AsyncClient(follow_redirects=True, timeout=30, trust_env=False) as client:
            response = await client.get(
                str(req.url), headers={"User-Agent": "JB-OSINT/1.0 (server)"}
            )
    except httpx.HTTPError as exc:
        raise HTTPException(status_code=502, detail=f"Источник недоступен: {exc}") from exc

    html = response.text[:500_000]
    links = re.findall(r"href=[\"']([^\"'#]+)[\"']", html, re.I)
    keywords = ("api", "key", "token", "developer", "docs", "access")
    candidates: list[str] = []
    for link in links:
        absolute = urljoin(str(response.url), link)
        if absolute.startswith(("http://", "https://")) and any(
            word in absolute.lower() for word in keywords
        ):
            if absolute not in candidates:
                candidates.append(absolute)

    text = html.lower()
    auth = "unknown"
    if re.search(r"bearer|authorization:\s*bearer", text):
        auth = "Bearer"
    elif re.search(r"api[_-]?key\s*[=:]|\?api[_-]?key", text):
        auth = "Query Param"
    elif "x-api-key" in text:
        auth = "Header"

    return {
        "found": bool(candidates),
        "key_url": candidates[0] if candidates else None,
        "auth": auth,
        "candidates": candidates[:10],
    }


def _run_rae_cli(req: RaeRequest, run_dir: Path) -> dict[str, Any]:
    command = [
        settings.rae_bin,
        "agent",
        "--prompt",
        req.prompt,
        "--url",
        str(req.url),
        "--json",
    ]
    logger.info("RAE CLI: mode=%s url_host=%s", req.mode, req.url.host)
    completed = subprocess.run(
        command, capture_output=True, text=True, timeout=settings.rae_timeout
    )
    if completed.returncode != 0:
        raise HTTPException(
            status_code=502,
            detail=f"RAE завершился с кодом {completed.returncode}: {completed.stderr[-500:]}",
        )
    data: dict[str, Any] = {}
    for line in reversed(completed.stdout.strip().splitlines()):
        try:
            data = json.loads(line)
            break
        except json.JSONDecodeError:
            continue
    script_path = data.get("script_path") or ""
    script = ""
    if script_path and Path(script_path).exists():
        script = Path(script_path).read_text(encoding="utf-8", errors="replace")[:200_000]
        target = run_dir / "api_client.py"
        target.write_text(script, encoding="utf-8")
        script_path = str(target)
    return {
        "script_path": script_path,
        "usage": data.get("usage", {}),
        "script": script,
        "masked": {"emails": 0, "phones": 0, "ips": 0, "tokens": 0},
        "mode": req.mode,
    }


@app.post("/api/v1/rae/generate", dependencies=[Depends(require_key)])
async def rae_generate(req: RaeRequest) -> dict[str, Any]:
    """
    RAE: либо принимает HAR (маскирует -> генерация), либо запускает CLI.
    Внешняя LLM допускается только с явного разрешения ALLOW_EXTERNAL_LLM=1.
    """
    run_id = uuid.uuid4().hex[:12]
    run_dir = Path(settings.work_dir) / "runs" / run_id
    run_dir.mkdir(parents=True, exist_ok=True)

    if req.har:
        masked, counts = mask_har(req.har)
        logger.info(
            "RAE HAR masked: emails=%s phones=%s ips=%s tokens=%s",
            counts["emails"], counts["phones"], counts["ips"], counts["tokens"],
        )
        script, usage = await generate_client(masked, req.prompt)
        target = run_dir / "api_client.py"
        target.write_text(script, encoding="utf-8")
        (run_dir / "har.masked.json").write_text(
            json.dumps(masked, ensure_ascii=False), encoding="utf-8"
        )
        return {
            "script_path": str(target),
            "usage": usage,
            "script": script,
            "masked": counts,
            "mode": "har",
        }

    if settings.openai_api_key and not settings.allow_external_llm:
        raise HTTPException(
            status_code=400,
            detail="Внешняя LLM запрещена NDA: передайте HAR, он будет замаскирован",
        )
    if not shutil.which(settings.rae_bin):
        raise HTTPException(
            status_code=503,
            detail="RAE не установлен на сервере: передайте HAR в поле har",
        )
    return await asyncio.to_thread(_run_rae_cli, req, run_dir)
