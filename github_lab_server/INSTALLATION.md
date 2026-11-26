# Установка GitHub-лаборатории

## 1. Termux

Установите Termux (F-Droid), откройте его и выполните:

```bash
pkg update -y
pkg install -y python git termux-services
```

Скопируйте на устройство каталог `github_lab_server` (или только
`setup_lab.sh`) и запустите:

```bash
cd /path/to/github_lab_server
bash setup_lab.sh
```

Скрипт:

1. создаёт `~/.jb_osint/lab_server` и `~/.jb_osint/lab` (права 700);
2. создаёт venv и ставит `fastapi==0.115.6`, `uvicorn==0.34.0`;
3. кладёт `main.py` (или скачивает его из репозитория JB OSINT);
4. генерирует токен в `~/.jb_osint/lab_server/token` (права 600);
5. настраивает сервис `termux-services` и запускает сервер на `127.0.0.1:8081`;
6. печатает токен и адрес API.

Для экспорта в общую папку один раз выполните:

```bash
termux-setup-storage
```

Если `termux-services` не подхватил сервис — перезапустите Termux или запустите
вручную:

```bash
cd ~/.jb_osint/lab_server
.venv/bin/uvicorn main:app --host 127.0.0.1 --port 8081
```

Проверка:

```bash
curl -H "X-Lab-Token: <токен>" http://127.0.0.1:8081/health
```

## 2. Приложение

1. Откройте JB OSINT → нижняя вкладка **GitHub** (или Настройки → GitHub Лаборатория).
2. Адрес сервера: `http://127.0.0.1:8081`.
3. Вставьте токен из вывода `setup_lab.sh`.
4. «Проверить сервер» → должно появиться «Сервер отвечает».
5. Вставьте ссылку `https://github.com/owner/repo` (или `github.com/owner/repo`)
   и при необходимости ветку/тег → «Клонировать и просканировать».
6. Найденные `.py/.sh/.js` появятся как кнопки «Запустить»; произвольные команды
   можно вводить в поле терминала.
7. «Экспорт → Download/JB-Osint/reports» копирует `lab.log` и `*.apk`/`*.jar`.

## 3. Диагностика

| Симптом | Что проверить |
|---------|---------------|
| «Сервер Termux недоступен» | сервер запущен, порт 8081, токен совпадает |
| HTTP 401 | токен из `~/.jb_osint/lab_server/token` |
| HTTP 403 «Команда не в белом списке» | используйте поддерживаемые бинарники или `./script.sh` |
| HTTP 409 при экспорте | `termux-setup-storage` |
| «git не установлен» | `pkg install -y git` |
