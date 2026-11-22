"""JS/Cloudflare-рендер через Crawl4AI — необязательный слой сервера.

Ставится в Termux (`pip install crawl4ai && crawl4ai-setup`) или на VPS.
Если пакета нет, сервер честно говорит «рендер недоступен», а не подменяет контент.
"""
from __future__ import annotations

import logging
from typing import Any

logger = logging.getLogger("jb-osint.render")


class RenderUnavailable(Exception):
    """Crawl4AI не установлен."""


def available() -> bool:
    try:
        import crawl4ai  # noqa: F401
    except Exception:  # noqa: BLE001 — пакет падает на импорте без браузера
        return False
    return True


def _markdown(result: Any) -> str:
    value = getattr(result, "markdown", "") or ""
    if isinstance(value, str):
        return value
    # В crawl4ai 0.6+ markdown — объект с raw_markdown/fit_markdown.
    for attr in ("raw_markdown", "fit_markdown"):
        text = getattr(value, attr, "") or ""
        if text:
            return str(text)
    return str(value)


async def render_page(url: str, timeout: int = 45) -> dict[str, Any]:
    """Открывает страницу в headless-браузере и отдаёт HTML после исполнения JS."""
    if not available():
        raise RenderUnavailable(
            "Crawl4AI не установлен: pip install crawl4ai && crawl4ai-setup"
        )
    from crawl4ai import AsyncWebCrawler, BrowserConfig, CrawlerRunConfig

    browser = BrowserConfig(headless=True, verbose=False)
    run = CrawlerRunConfig(page_timeout=timeout * 1000)
    try:
        async with AsyncWebCrawler(config=browser) as crawler:
            result = await crawler.arun(url=url, config=run)
    except Exception as exc:  # noqa: BLE001 — браузер падает по-разному
        return {"ok": False, "html": "", "markdown": "", "title": "", "error": f"рендер не удался: {exc}"}

    if not getattr(result, "success", False):
        return {
            "ok": False,
            "html": "",
            "markdown": "",
            "title": "",
            "error": str(getattr(result, "error_message", "") or "рендер не удался"),
        }
    metadata = getattr(result, "metadata", None) or {}
    return {
        "ok": True,
        "html": (getattr(result, "html", "") or "")[:400_000],
        "markdown": _markdown(result)[:200_000],
        "title": str(metadata.get("title", "") or "")[:300],
        "error": "",
    }
