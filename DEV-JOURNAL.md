# DEV-JOURNAL — JB OSINT

## 2026-11-22

### Состояние на входе

- GitHub HEAD: `b4ce9526` — «Merge PR #3 ci/telegram-parsers-apk» (22.11.2026 10:16 МСК).
- Локальная копия для сборки: `C:\jbosint\jb-osint` (ASCII-путь).
- Исходный ZIP: `C:\Users\Евге\Documents\AgentHere\jb-osint\src\jb-osint-main`.
- Прошлая сессия остановилась на сборке: `local.properties` был записан в ANSI
  (`C:/Users/????/Documents/...`), Gradle не находил SDK.

### Сделано

- `local.properties` переписан с `\uXXXX`-экранированием пути к SDK.
- Сборка базовой линии: `:app:compileDebugKotlin` → **BUILD SUCCESSFUL**.
- `SPEC.md` с объёмом итерации и критериями приёмки.

### Срез 1 — Termux

- `data/Termux.kt`: RUN_COMMAND-мост, диагностика, авто-скрипт настройки, белый список.
- `data/TermuxResultReceiver.kt`: приём stdout/stderr/exitCode через PendingIntent.
- `SettingsScreen`: блок «Локальный бэкенд (Termux)» — тумблер, диагностика, разрешение,
  путь, скрипт настройки, доверенные скрипты, запуск.
- `Storage`: termux_enabled / termux_home / termux_trusted.
- `AndroidManifest`: permission RUN_COMMAND, queries com.termux, receiver.
- `TermuxTest` (8 тестов): имена/пути/белый список/скрипт/маркеры диагностики.

### Срез 2–3 — Reverse API Engineer

- `data/RaeClient.kt`: разбор Python-клиента RAE — base_url, статические заголовки,
  def-функции, method/path/params; секреты в заголовках маскируются (`Bearer ***`).
- `RaeParserTest` (6 тестов): разбор, маскирование, приватные функции, шаблон URL.
- `ui/RaeDialog.kt`: вкладка «Из трафика» в «Своих модулях»:
  запуск `rae_manual.sh` через Termux, импорт `api_client.py` через SAF,
  предупреждение о сгенерированном коде, черновик модуля.
- Серверный режим в диалоге: URL сайта + цель, ответ `script_path`/`usage`/`masked`.

### Срез 4 — сервер

- `server/app/main.py`: `/healthz`, `/scrape`, `/ai/analyze`, `/api/discovery`,
  `/api/v1/rae/generate`; `X-API-Key`, rate limit 10 rps/IP.
- `server/app/masking.py`: email, телефоны, IPv4, токены, чувствительные ключи, HAR.
- `server/app/llm.py`: детерминированный генератор клиента из HAR; OpenAI — опционально.
- `server/`: Dockerfile, docker-compose, nginx.conf, `.env.example`, README, pytest.ini.
- Тесты: `pytest -q` → **7 passed** (маскирование, RAE-эндпоинт, cross-check).

### Срез 5 — каскад

- `data/Remote.kt`: `health`, `raeGenerate`, нормализация https-адреса.
- `Net.postLong`: отдельный таймаут до 15 минут для серверных задач.
- `SettingsScreen`: блок «Серверный ускоритель» — URL, X-API-Key в SecureStore,
  проверка связи, статус «Режим ограниченной функциональности. Выполнено локально».
- RAE: при ошибке сервера диалог предлагает локальный путь через Termux.

### Проверки (свидетельства)

- `:app:testDebugUnitTest` → **BUILD SUCCESSFUL**, 245 тестов (включая 14 новых).
- `:app:assembleDebug :app:lintDebug` → **BUILD SUCCESSFUL** (lint без ошибок).
- APK: `release/JB-OSINT-1.21.1-termux-rae-debug.apk`, 38 424 245 байт,
  SHA-256 `1C6856A40F108B73044C9C75DA61900A717E83434EFE2633C942D541AD2608DE`.
- Сервер живой: `GET /healthz` → `{"ok":true}`;
  `POST /api/v1/rae/generate` с HAR → `script_path`, `usage.provider=fallback`,
  `masked.emails=1`, `masked.tokens=1`; в сгенерированном коде `email=***`.
- `pytest` сервера: 7 passed.

### Грабли

- AGP не собирает проект в пути с кириллицей; рабочая копия — `C:\jbosint`.
- Тестовый JVM Gradle падает с `ClassNotFoundException: GradleWorkerMain`, если
  `GRADLE_USER_HOME` в кириллическом пути: argfile `@C:\Users\????\...` не читается.
  Рабочая связка: `GRADLE_USER_HOME=C:\jbosint\gradle-home`, Gradle в `C:\jbosint\tools`.
- PowerShell 5.1 читает UTF-8 как ANSI: массовые замены в `.md` делать только через
  `[System.IO.File]::ReadAllText($f, [Text.Encoding]::UTF8)`.
- `python`/`node`/`git` в системе нет; для сервера использован портативный Python 3.12.8
  в `C:\jbosint\tools\python312` (embeddable + get-pip).

### Следующие шаги

1. Подключить серверный клиент к поиску (сейчас сервер используется в RAE и health-check).
2. Scrapling/Crawl4AI worker в Docker (JS/Cloudflare) — отдельный образ.
3. Импорт PDF/DOCX в «Доказательную базу» через MarkItDown и полнотекстовый поиск.
4. Пуш в GitHub: нужен токен (в памяти не хранится).

### Открытые вопросы

- Токен GitHub в памяти не хранится (правило безопасности). Для пуша нужен токен
  от пользователя либо доступ к репозиторию через его окружение.
- Локальный RAE в Termux требует Playwright/Chromium и GUI-сессии; на практике
  надёжнее серверный режим. В UI это честно указано.

### Срез 6 — серверное чтение страниц (фолбэк)

- Сервер `POST /scrape` теперь отдаёт блок `content`: `title`, `description`,
  `text` (до 6 000 символов), `links` (до 60) — `extract_content()` в `server/app/main.py`.
  Заодно `trust_env=False` у httpx: переменные прокси из окружения (`NO_PROXY` с `[::1]`)
  роняли клиент на разборе URL.
- Клиент: `Remote.scrape()` + `Remote.parseScrape()` (разбор вынесен из сети под тест),
  `PageContent.via` = `local` / `server`; в фактах страницы появляется
  атрибут «Получено: серверный режим».
- `PageReader.read()`: локальный запрос не удался (403/429/таймаут) → пробует сервер,
  если он включён в настройках. Сервер выключен — поведение прежнее, всё локально.
- `AppViewModel.init`: `PageReader.serverFallback` читает `serverEnabled`/`serverUrl`
  и ключ из SecureStore при каждом вызове, поэтому настройки применяются сразу.
- Тесты: `ServerFallbackTest` (6) — разбор ответа, blocked, битый JSON, маркер источника;
  `server/tests/test_scrape.py` (3) — извлечение из HTML и blocked-страница.
- Проверки: `:app:testDebugUnitTest` → **257 тестов, 0 падений**; `pytest -q` →
  **10 passed**; живой `/scrape` на uvicorn: `status=200`, `title=Example Domain`,
  текст извлечён, `blocked=false`. APK `1.21.2` (versionCode 30) собран.

