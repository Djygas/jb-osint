#!/data/data/com.termux/files/usr/bin/bash
# JB OSINT GitHub-лаборатория: локальный API-сервер в Termux.
# Запуск: bash setup_lab.sh
set -euo pipefail

PREFIX="${PREFIX:-/data/data/com.termux/files/usr}"
HOME_DIR="${HOME:-/data/data/com.termux/files/home}"
PORT="${JB_LAB_PORT:-8081}"
ROOT="$HOME_DIR/.jb_osint"
SERVER_DIR="$ROOT/lab_server"
WORK_DIR="$ROOT/lab"
TOKEN_FILE="$SERVER_DIR/token"
TOKEN=""
REPO_RAW="https://raw.githubusercontent.com/Djygas/jb-osint/main/github_lab_server/main.py"

echo "[JB LAB] Каталоги"
mkdir -p "$SERVER_DIR" "$WORK_DIR"
chmod 700 "$ROOT" "$SERVER_DIR" "$WORK_DIR"

echo "[JB LAB] Пакеты"
pkg install -y python git >/dev/null 2>&1 || true
pkg install -y termux-services >/dev/null 2>&1 || true

echo "[JB LAB] Виртуальное окружение"
if [ ! -x "$SERVER_DIR/.venv/bin/python" ]; then
  python3 -m venv "$SERVER_DIR/.venv"
fi
"$SERVER_DIR/.venv/bin/pip" install --quiet --upgrade pip
"$SERVER_DIR/.venv/bin/pip" install --quiet "fastapi==0.115.6" "uvicorn==0.34.0"

echo "[JB LAB] Серверный код"
SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
if [ -f "$SCRIPT_DIR/main.py" ]; then
  cp "$SCRIPT_DIR/main.py" "$SERVER_DIR/main.py"
elif [ -f "$SERVER_DIR/main.py" ]; then
  :
else
  curl -fsSL "$REPO_RAW" -o "$SERVER_DIR/main.py"
fi
chmod 600 "$SERVER_DIR/main.py"

echo "[JB LAB] Токен"
if [ -s "$TOKEN_FILE" ]; then
  TOKEN="$(cat "$TOKEN_FILE")"
else
  TOKEN="$(python3 -c 'import secrets; print(secrets.token_hex(24))')"
  printf '%s' "$TOKEN" > "$TOKEN_FILE"
  chmod 600 "$TOKEN_FILE"
fi

echo "[JB LAB] Автозапуск (termux-services)"
SVDIR="$PREFIX/var/service/jb-lab"
if [ -d "$PREFIX/var/service" ]; then
  mkdir -p "$SVDIR"
  cat > "$SVDIR/run" <<EOF
#!/data/data/com.termux/files/usr/bin/sh
exec 2>&1
cd "$SERVER_DIR"
exec "$SERVER_DIR/.venv/bin/uvicorn" main:app --host 127.0.0.1 --port $PORT
EOF
  chmod +x "$SVDIR/run"
  command -v sv-enable >/dev/null 2>&1 && sv-enable jb-lab >/dev/null 2>&1 || true
fi

echo "[JB LAB] Запуск сервера"
if ! curl -fsS -m 3 -H "X-Lab-Token: $TOKEN" "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
  ( cd "$SERVER_DIR" && nohup "$SERVER_DIR/.venv/bin/uvicorn" main:app \
      --host 127.0.0.1 --port "$PORT" > "$SERVER_DIR/server.log" 2>&1 & ) || true
  sleep 3
fi

if curl -fsS -m 3 -H "X-Lab-Token: $TOKEN" "http://127.0.0.1:$PORT/health" >/dev/null 2>&1; then
  STATUS="сервер отвечает"
else
  STATUS="сервер ещё не ответил — проверьте $SERVER_DIR/server.log"
fi

cat <<EOF

============================================================
 GitHub-лаборатория настроена
============================================================
 API:    http://127.0.0.1:$PORT
 Токен:  $TOKEN
 Токен-файл: $TOKEN_FILE
 Статус: $STATUS

 В приложении: GitHub → поле «Токен сервера» → вставьте токен.
 Для автозапуска: перезапустите Termux (termux-services подхватит сервис).
 Рабочая папка проектов: $WORK_DIR
============================================================
EOF
