"""Проверки серверного чтения страниц: разбор HTML и ответ /scrape."""
import httpx
from fastapi.testclient import TestClient

from app.main import app, extract_content

client = TestClient(app)

HTML = """<!doctype html><html><head>
<title>ООО «Ромашка» — контакты</title>
<meta name="description" content="Контакты и реквизиты компании">
<style>.x{color:red}</style>
</head><body>
<script>window.__data = {secret: 1}</script>
<h1>Ромашка</h1><p>Почта: info@romashka.ru</p>
<a href="/about">О нас</a><a href="https://vk.com/romashka">VK</a>
</body></html>"""


def test_extract_content_reads_title_text_and_links():
    content = extract_content(HTML, "https://romashka.ru/contacts")
    assert "Ромашка" in content["title"]
    assert content["description"] == "Контакты и реквизиты компании"
    assert "info@romashka.ru" in content["text"]
    assert "window.__data" not in content["text"]
    assert "color:red" not in content["text"]
    assert "https://romashka.ru/about" in content["links"]
    assert "https://vk.com/romashka" in content["links"]


class _FakeResponse:
    def __init__(self, status_code: int = 200, text: str = HTML) -> None:
        self.status_code = status_code
        self.text = text
        self.content = text.encode("utf-8")
        self.url = httpx.URL("https://romashka.ru/")
        self.headers = {"content-type": "text/html; charset=utf-8"}

    def json(self):
        raise ValueError("not json")


def test_scrape_returns_extracted_content(monkeypatch):
    async def fake_get(self, url, headers=None, **kwargs):
        return _FakeResponse()

    monkeypatch.setattr(httpx.AsyncClient, "get", fake_get)
    response = client.post("/scrape", json={"url": "https://romashka.ru/"})
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["status"] == 200
    assert data["blocked"] is False
    assert data["content"]["title"].startswith("ООО")
    assert "info@romashka.ru" in data["content"]["text"]
    assert data["content"]["links"]


def test_scrape_marks_blocked_source(monkeypatch):
    async def fake_get(self, url, headers=None, **kwargs):
        return _FakeResponse(status_code=403, text="<title>Just a moment...</title>")

    monkeypatch.setattr(httpx.AsyncClient, "get", fake_get)
    response = client.post("/scrape", json={"url": "https://romashka.ru/"})
    assert response.status_code == 200
    data = response.json()
    assert data["blocked"] is True
    assert data["content"]["title"].startswith("Just a moment")

def test_render_fallback_replaces_blocked_page(monkeypatch):
    async def fake_get(self, url, headers=None, **kwargs):
        return _FakeResponse(status_code=403, text="<title>Just a moment...</title>")

    async def fake_render(url):
        return {"ok": True, "html": HTML, "markdown": "", "title": "Ромашка", "error": ""}

    monkeypatch.setattr(httpx.AsyncClient, "get", fake_get)
    monkeypatch.setattr("app.main.render_page", fake_render)
    response = client.post("/scrape", json={"url": "https://romashka.ru/", "render": True})
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["render"]["used"] is True
    assert data["render"]["provider"] == "crawl4ai"
    assert data["blocked"] is False
    assert "info@romashka.ru" in data["content"]["text"]


def test_render_unavailable_keeps_blocked_content(monkeypatch):
    from app.render import RenderUnavailable

    async def fake_get(self, url, headers=None, **kwargs):
        return _FakeResponse(status_code=403, text="<title>Just a moment...</title>")

    async def fake_render(url):
        raise RenderUnavailable("Crawl4AI не установлен: pip install crawl4ai")

    monkeypatch.setattr(httpx.AsyncClient, "get", fake_get)
    monkeypatch.setattr("app.main.render_page", fake_render)
    data = client.post(
        "/scrape", json={"url": "https://romashka.ru/", "render": True}
    ).json()
    assert data["render"]["used"] is False
    assert "Crawl4AI" in data["render"]["note"]
    assert data["blocked"] is True


def test_render_not_requested_on_good_page(monkeypatch):
    async def fake_get(self, url, headers=None, **kwargs):
        return _FakeResponse()

    monkeypatch.setattr(httpx.AsyncClient, "get", fake_get)
    data = client.post("/scrape", json={"url": "https://romashka.ru/"}).json()
    assert "render" not in data
    assert data["blocked"] is False