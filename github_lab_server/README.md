# GitHub-лаборатория v1.0.0

Модуль JB OSINT для безопасной работы с публичными репозиториями GitHub через
локальный API-сервер в Termux.

- клонирование `--depth 1 --single-branch` в `~/.jb_osint/lab/<hash>`;
- определение типа проекта (Android/Python/Node);
- поиск исполняемых файлов `.py/.sh/.js` и запуск по кнопке;
- таймаут, белый список команд, запрет `sudo/su/doas` и `bash -c`;
- экспорт лога и артефактов (`*.apk`, `*.jar`) в `Download/JB-Osint/reports`;
- доступ только с `127.0.0.1` по токену `X-Lab-Token`.

## Быстрый старт

1. В Termux:
   ```bash
   bash setup_lab.sh
   ```
   Скрипт создаст venv, поставит FastAPI/uvicorn, сгенерирует токен и запустит
   сервер на `127.0.0.1:8081`.
2. В приложении: **GitHub** → вставьте токен из вывода скрипта → «Проверить сервер».
3. Вставьте ссылку `https://github.com/owner/repo` → «Клонировать и просканировать».
4. Запускайте найденные скрипты кнопкой «Запустить» или вводите команды в терминале.

Подробности: [INSTALLATION.md](INSTALLATION.md) и
[TECHNICAL_SPECIFICATION.md](TECHNICAL_SPECIFICATION.md).
