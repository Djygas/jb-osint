# Техническое задание — GitHub-лаборатория v1.0.0

## 1. Назначение

Вкладка «GitHub» в JB OSINT для работы с публичными репозиториями: клонирование,
поиск исполняемых файлов, запуск команд в изолированном каталоге, просмотр логов,
сборка артефактов и экспорт. Архитектура — локальный HTTP-сервер в Termux и
мобильный клиент, общающийся с ним только по loopback.

## 2. Компоненты

```
github_lab_server/
  main.py               FastAPI-сервер (127.0.0.1:8081)
  setup_lab.sh          установка venv, зависимостей, токена, автозапуска
app/src/main/java/ru/jbosint/lab/
  model/LabRepository.kt        модели данных и команды
  network/TermuxApiClient.kt    HTTP-клиент (OkHttp, loopback, X-Lab-Token)
  repository/GitHubLabRepository.kt  настройки + фасад API
  ui/screen/GitHubLabScreen.kt  экран лаборатории (Compose)
```

Экран подключён к существующей навигации JB OSINT (`Screen.LAB`, нижняя панель
и пункт в «Настройках»). Отдельного NavGraph в проекте нет — используется
собственная модель экранов приложения.

## 3. API

| Метод | Путь | Назначение |
|-------|------|-----------|
| GET | `/health` | проверка сервера и токена |
| POST | `/clone` | `{url, ref}` → `{url, ref, dir, type, scripts[]}` |
| POST | `/scan` | `{dir}` → `{dir, type, scripts[]}` |
| POST | `/run` | `{dir, command, timeout}` → `{exit_code, stdout, stderr, took_ms}` |
| POST | `/clear` | `{dir}` → `{ok}` |
| POST | `/export` | `{dir}` → `{path, files}` |

Все запросы требуют заголовок `X-Lab-Token`. Токен генерируется в `setup_lab.sh`
и хранится в `~/.jb_osint/lab_server/token` (права 600).

## 4. Безопасность

- сервер слушает только `127.0.0.1`;
- каталоги проектов строго внутри `~/.jb_osint/lab` с правами `700`;
- `git clone --depth 1 --single-branch`, только `https://github.com/owner/repo`;
- белый список исполняемых файлов; `sudo/su/doas/rm` и `bash -c` запрещены;
- таймаут по умолчанию 600 с (максимум 1800 с);
- код репозитория не запускается при открытии/клонировании — только по кнопке;
- токен в приложении хранится в `SecureStore` (AES/GCM + Android Keystore);
- cleartext в приложении разрешён только для loopback (network security config).

## 5. Ограничения

- только публичные репозитории (без интерактивной авторизации git);
- белый список — защита от случайной эскалации, а не полноценная песочница:
  запускаемые пользователем скрипты выполняются от имени Termux;
- для экспорта нужна настроенная общая папка (`termux-setup-storage`).
