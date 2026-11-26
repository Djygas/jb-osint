#!/data/data/com.termux/files/usr/bin/bash
set -e
CONFIG_DIR="$HOME/.config/jb_osint"
mkdir -p "$CONFIG_DIR" "$HOME/.jb_osint/lab"
chmod 700 "$HOME/.jb_osint/lab"
cat > "$CONFIG_DIR/github_lab.json" <<EOF
{
  "work_dir": "$HOME/.jb_osint/lab",
  "timeout_sec": 600,
  "allowed_domains": ["github.com","raw.githubusercontent.com"],
  "default_shell": "/bin/bash",
  "venv_python": "$(which python3)"
}
EOF
chmod 600 "$CONFIG_DIR/github_lab.json"
echo "GitHub-лаборатория настроена. Конфиг: $CONFIG_DIR/github_lab.json"
