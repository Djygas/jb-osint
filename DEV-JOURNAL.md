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


### Срез 7 — документы досье: импорт и полнотекстовый поиск

- data/Documents.kt: DocumentText (UTF-8 с BOM → строгий UTF-8 → windows-1251,
  DOCX через zip word/document.xml, HTML → текст), DocSearch (все слова запроса,
  счёт совпадений, сниппет с контекстом), DocumentStore (index.json + <id>.txt
  в приватной папке приложения, SHA-256 исходного файла).
- Сервер POST /documents/extract?name=...: тело — сырые байты файла, имя — в query.
  Порядок конвертеров: MarkItDown (если установлен) → stdlib (TXT/DOCX) → pypdf (PDF).
  Лимиты: 25 МБ на файл, 400 000 символов текста в ответе. pypdf==5.1.0 в requirements.
- Приложение: Remote.extractDocument() + parseExtract(); каскад «локально → сервер»
  (сервер только если включён в настройках). UI: карточка «Документы» на главном экране
  и полноэкранный диалог — импорт через SAF, поиск, удаление, пометка «локально/сервер».
- Termux-слой не дублировали: скрипт настройки уже ставит markitdown[all], а тот же
  FastAPI-сервер можно поднять в Termux — тогда /documents/extract работает на телефоне.
- Тесты: DocumentsTest (9) — DOCX, cp1251, HTML, PDF→сервер, поиск/сниппет, разбор ответа;
  server/tests/test_documents.py (7) — TXT/DOCX/CP1251 и отказы 400/415.
- Проверки: :app:testDebugUnitTest → **266 тестов, 0 падений**; pytest -q → **17 passed**;
  живой /documents/extract на uvicorn (DOCX 337 Б) → 200, provider=stdlib, два абзаца
  с переносом; :app:lintDebug → 0 errors / 2 warnings; APK 1.21.3 (versionCode 31),
  37 630 361 Б, SHA-256 DF702F7A…18A9CC.

### Срез 8 — JS/Cloudflare-рендер через Crawl4AI

- `server/app/render.py`: `render_page()` — headless-браузер Crawl4AI; понимает и 0.4
  (`markdown` строкой), и 0.6+ (`markdown` объектом с raw/fit_markdown). Пакета нет →
  `RenderUnavailable`, сервер честно отвечает «Crawl4AI не установлен».
- `/scrape` получил поле `render` (по умолчанию false). Рендер включается только когда
  обычный ответ бесполезен: `blocked` (401/403/429/503) или текста меньше 200 символов.
  В ответе блок `render: {used, provider, note}`; HTML наружу не отдаётся — сервер сам
  извлекает title/text/links.
- Клиент: `Remote.scrape` всегда просит render (он вызывается только как фолбэк после
  локальной ошибки); `PageContent.via = "render"`, факт помечается
  «Получено: сервер + JS-рендер».
- Тесты: `test_scrape.py` +3 (рендер подменяет блокировку; отсутствие Crawl4AI сохраняет
  blocked; без флага блока `render` нет), `ServerFallbackTest` +1 (via=render).
- Проверки: `pytest` 20 passed; `:app:testDebugUnitTest` 267 тестов, 0 падений;
  живой `/scrape` с `render=true` → `render.used=false`, note «Crawl4AI не установлен»
  (реальный рендер проверяется там, где пакет установлен — Termux/VPS).


### Срез 9 — сверка фактов в UI (`/ai/analyze`)

- Найдена и исправлена ошибка серверной сверки: маскирование применялось ДО сравнения,
  поэтому две разные почты превращались в `***`, и противоречие исчезало. Теперь проверка
  идёт по исходным фактам (правила локальные, наружу ничего не уходит), а маскирование
  остаётся обязательным перед внешней LLM (`ALLOW_EXTERNAL_LLM=1`).
- Клиент: `Remote.analyze()` + `parseAnalyze()`, `analyzeFacts()`: subject → object,
  kind → field, value и source; до 400 фактов, дубликаты отбрасываются.
- UI: карточка «Сверка фактов» на экране результата и полноэкранный диалог: сколько фактов
  уйдёт, кнопка «Проверить», список противоречий (объект / поле / значения), провайдер и
  заметки сервера. Сервер выключен — понятная ошибка, запрос не уходит.
- Тесты: `CrossCheckTest` (5) — маппинг, дубликаты, лимит, разбор ответа; сервер
  `test_api.py` +2 — пустой список и PII-поля (регрессия маскирования).
- Проверки: `pytest` 22 passed; `:app:testDebugUnitTest` 272 теста, 0 падений;
  живой `/ai/analyze` → 2 противоречия, включая поле `email`; APK 1.21.5 (versionCode 33).


### Slice 10 - release 1.21.6 (2026-11-23)

- Tri arhiva s rabochego stola (JB-OSINT-1.21.2, JB-OSINT-1.21.5, jb-osint-termux-rae-server
  ot 22.11) sverРµРЅС‹ s etim repozitoriem: v arhivah net nichego, chego net v repo - v repo vsyo
  eto uzhe est i pozzhe (Documents.kt, CrossCheckCard, render.py, ocr.py, novye testy).
  Itog: edinyi kod - eto repo, otdelnye arhivy bolshe ne nuzhny.
- Sborka: JDK 17.0.21.1, Gradle 8.11.1, GRADLE_OPTS="-Xmx2g -XX:MaxMetaspaceSize=640m",
  :app:assembleDebug --rerun-tasks -> BUILD SUCCESSFUL (40 tasks executed).
- Testy: :app:testDebugUnitTest -> BUILD SUCCESSFUL (272 testa).
- APK: 1.21.6-debug, versionCode 34, 37 646 741 B,
  SHA-256 4842a80a726497dca11dbd7ed8c3d7503eb348e777c07177ee2f5315c5460dd9.
- GitHub Release: v1.21.6 - https://github.com/Djygas/jb-osint/releases/tag/v1.21.6
  asset JB-OSINT-1.21.6-debug.apk + SHA-256.txt. ProverРµРЅРѕ: 302 -> 200, Content-Length 37 646 741.
- Zametka: .github/workflows/android-ci.yml lokalno starРµРµ remote (v remote fallback secreta
  JB_OSINT) - lokalnyi variant NE pushit, inache otkatit CI.

### Slice 11 - signed release + report check (2026-11-23)

- Podpisannaya sborka poluchena cherez GitHub Actions (workflow_dispatch-bound push,
  run 45170671412, job apk, artifact jb-osint-release-apk): keystore iz sekretov CI,
  app/build.gradle.kts bere JB_KEYSTORE_FILE/PASSWORD/ALIAS/KEY_PASSWORD.
- apksigner verify: Verified (v2 scheme), CN=Evgen, O=Personal Projects, RSA 2048.
  aapt2: package ru.jbosint.osint, versionCode 34, versionName 1.21.6,
  minSdk 26 / targetSdk 35. APK 21 171 726 B, SHA-256
  ee1abb7c5bffdbe324fe78d21d1119b86103a66df0b333d0d99c8b1d7d9de0ac.
- V release v1.21.6 dobavlen asset JB-OSINT-1.21.6-release.apk; SHA-256.txt peresobran
  (oba APK). ProverРµРЅРѕ: 302 -> 200, Content-Length 21 171 726.
- Proverka otchetov: vremennyi test SampleReportsTest sozdaval realnye faily v
  C:/jbosint/report-check (HTML/JSON/XLSX/PDF/CSV s russkimi dannymi), posle proverki udalen.
  Itogi: HTML - lang=ru, razdely "Ustanovlennye svedeniya"/"Dostovernost"/"Istochniki", bez
  mojibake; JSON - schema jb-osint/report/2.0, russkie znacheniya i urovni dostovernosti;
  XLSX - listy "Svodka"/"Reestr"/"Istochniki"; PDF - %PDF-1.7, 3 vstroennyh shrifta Inter
  (FontFile2), %%EOF/startxref na meste; CSV - kirillica s razdelitelem ";".
- Testy: :app:testDebugUnitTest 272 testa, :report-core:test vse zelenye (vklyuchaya
  "pdf report is built and contains cyrillic text"); lintDebug proshyol v CI.
- Nuance: release i debug - raznye pakety (ru.jbosint.osint vs ...debug), dannye ne perenositutsya.

### Slice 12 — 1.24.0 «Deep Leak Scan» (2026-11-25)

- `data/LeakScan.kt`: `LeakRules` (SHA-256 нормализованного адреса, маска `iv***@mail.ru`,
  разбор строк CSV/TSV/SQL, эвристика «в строке был пароль») и `LeakScan` — каскад
  «индекс → кэш 24 ч → цепочка API». Первый ответивший сервис закрывает вопрос,
  между попытками пауза 700 мс, при полном отказе сети возвращается локальный результат
  с пометкой об офлайне.
- `data/LeakStore.kt`: SQLite `leaks.db` — `files`, `hits`, `cache`. Потоковая индексация
  дампов (буфер 1 МБ, пачки по 50 000 вставок), переиндексация только по изменению
  size+mtime, чистка исчезнувших файлов, поиск по `idx_leak_hash`.
  Отдельно решена проблема «упал на середине файла»: недочитанный файл удаляется
  из базы целиком и переиндексируется в следующий раз.
- `ui/LeakScanDialog.kt`: карточка на главном экране и полноэкранный диалог —
  тумблеры «Онлайн-API / Локальный индекс / Полная переиндексация», добавление дампа
  через SAF, кнопка «Сканировать всё», группировка по источнику, экспорт JSON с масками.
- Решение по безопасности: **пароли не хранятся и не показываются**. В индексе только
  SHA-256 адреса, маска, имя файла и номер строки; признак «в строке был пароль» —
  без самого значения. Это продолжение линии модуля LeakCheck («записи и пароли
  модуль не забирает»), а не отмена её.
- Отказ по ТЗ: обхода 429 через прокси нет. На лимит отвечаем паузой и переходом
  к следующему сервису — это отказоустойчивость, а не обход блокировки.
- Тесты: `LeakScanTest` (10) — хэш и нормализация, маска, разбор строк, лимит адресов,
  телефон, эвристика пароля, расширения, круговорот фактов через кэш, порядок цепочки.
- Проверки: `:app:compileDebugKotlin` OK; `:app:testDebugUnitTest` → 42 набора,
  **311 тестов, 0 падений**; `:app:lintDebug` → BUILD SUCCESSFUL; `:app:assembleDebug` OK.
- APK: `release/JB-OSINT-1.24.0-leakscan-debug.apk`, versionCode 39, 37 794 201 Б,
  SHA-256 `87da2eb3c2c3168604dbaa16651a41bef949f1b141224fa1b31d007c27146b04`.
- Не сделано: публикация GitHub-релиза (в окружении нет токена), подпись release-ключом
  (ключ только в секретах CI), изоляция скриптов WEREWIKS — мост `plugin.json` из 1.23
  уже есть, sandbox с запретом чтения env/SharedPreferences остаётся следующим срезом.