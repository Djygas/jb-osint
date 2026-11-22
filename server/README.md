# JB OSINT — серверный слой (FastAPI)

Опциональный ускоритель для Android-приложения. Приложение работает и без него.

## Запуск

```bash
cp .env.example .env
# отредактируйте .env: JB_API_KEY, при необходимости OPENAI_*
docker compose up -d --build
curl -s http://127.0.0.1/healthz
```

Локально без Docker:

```bash
python -m venv .venv
. .venv/bin/activate        # Windows: .venv\Scripts\activate
pip install -r requirements.txt
pytest -q
uvicorn app.main:app --host 0.0.0.0 --port 8080
```

## Эндпоинты

Все, кроме `/healthz`, требуют заголовок `X-API-Key`, если задан `JB_API_KEY`.

### JS/Cloudflare-страницы (Crawl4AI, опционально)

Если обычный запрос вернул 403/429 или почти пустой текст, `/scrape` с `render=true`
открывает страницу в headless-браузере Crawl4AI. Пакет не входит в `requirements.txt`:
он тянет Playwright и Chromium (сотни мегабайт). Установка:

```bash
pip install crawl4ai && crawl4ai-setup
```

Без пакета сервер отвечает `render.used=false` и человеческой причиной — контент не
подменяется. Тот же сервер можно поднять в Termux: тогда MarkItDown и Crawl4AI из
скрипта настройки приложения используются локально, на телефоне.


| Метод | Путь | Назначение |
|---|---|---|
| GET | `/healthz` | Живость сервера |
| POST | `/scrape` | HTML/JSON без JS; `capture_api=true` — подсказки эндпоинтов; `render=true` — поднять Crawl4AI, если страница заблокирована или пустая |
| POST | `/ai/analyze` | Cross-check фактов: противоречия по одному полю |
| POST | `/api/discovery` | Ссылка на страницу выдачи ключа + тип авторизации |
| POST | `/api/v1/rae/generate` | RAE: HAR → маскирование → клиент; либо CLI-запуск |

Пример RAE с HAR:

```bash
curl -s -X POST http://127.0.0.1:8080/api/v1/rae/generate \
  -H 'Content-Type: application/json' -H 'X-API-Key: change-me' \
  -d '{"url":"https://site.example","prompt":"собери данные","mode":"manual","har":{"log":{"entries":[]}}}'
```

Ответ: `script_path`, `usage`, `script`, `masked` (сколько email/phone/IP/токенов вырезано).

## NDA и маскирование

- HAR маскируется **до** любой генерации: email, телефоны, IPv4, токены и
  заголовки `Authorization/Cookie` заменяются на `***`.
- В лог пишутся только счётчики замен, не тела запросов.
- Внешняя LLM включается явно: `ALLOW_EXTERNAL_LLM=1`. По умолчанию работает
  детерминированный генератор клиента из HAR (без сети).
- Запуск `reverse-api-engineer` на сервере возможен только при установленном
  `rae` и self-hosted модели; иначе сервер просит передать HAR.

## Ограничения MVP

- `/scrape` не исполняет JavaScript и не обходит Cloudflare — для этого отдельный
  `scrapling-worker` (Playwright) по ТЗ, он не входит в этот образ.
- Rate limit — в памяти процесса (10 rps на IP). Для нескольких реплик нужен Redis.
- PDF/OCR и MarkItDown остаются на Termux/отдельном сервисе.

## Проверки

```bash
pytest -q
```

Тесты покрывают маскирование (email/phone/IP/токены/заголовки), RAE-эндпоинт
(`script_path`, `usage`, отсутствие PII в сгенерированном коде) и cross-check.
