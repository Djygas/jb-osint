"""JB OSINT GitHub lab — local API server (Termux, 127.0.0.1:8081).

Клонирует публичные репозитории GitHub в отдельный каталог, ищет исполняемые
файлы и запускает команды по явному запросу из приложения.

Безопасность: только loopback, обязательный X-Lab-Token, каталоги строго внутри
work_dir, белый список исполняемых файлов, запрет sudo/su/doas и `bash -c`,
таймаут на выполнение. Код репозитория не запускается сам по себе.
"""

from __future__ import annotations

import hashlib
import os
import re
import secrets
import shlex
import shutil
import subprocess
import time
from pathlib import Path
from urllib.parse import urlparse

from fastapi import FastAPI, Header, HTTPException
from pydantic import BaseModel

HOME = Path(os.environ.get("HOME", "/data/data/com.termux/files/home"))
ROOT = HOME / ".jb_osint"
WORK = Path(os.environ.get("JB_LAB_WORK", str(ROOT / "lab")))
TOKEN_FILE = Path(os.environ.get("JB_LAB_TOKEN_FILE", str(ROOT / "lab_server" / "token")))
DEFAULT_TIMEOUT = int(os.environ.get("JB_LAB_TIMEOUT", "600"))

ALLOWED_HOSTS = {"github.com", "www.github.com"}
ALLOWED_BIN = {
    "git", "python", "python3", "pip", "pip3", "node", "npm", "yarn",
    "bash", "sh", "ls", "cat", "head", "tail", "grep", "find", "make",
    "gradle", "java", "javac", "go", "cargo", "chmod", "./gradlew",
}
BLOCKED_BIN = {"sudo", "su", "doas", "rm", "shutdown", "reboot", "pkg", "apt", "apt-get"}
SCRIPT_EXT = {".py": "python", ".sh": "bash", ".js": "node"}
MAX_OUTPUT = 200_000

app = FastAPI(title="JB OSINT Lab", version="1.0.0")


class CloneRequest(BaseModel):
    url: str
    ref: str = ""


class DirRequest(BaseModel):
    dir: str


class RunRequest(BaseModel):
    dir: str
    command: str
    timeout: int = DEFAULT_TIMEOUT


def _token() -> str:
    try:
        return TOKEN_FILE.read_text(encoding="utf-8").strip()
    except OSError:
        return ""


def _auth(header: str | None) -> None:
    expected = _token()
    if not expected or not header or not secrets.compare_digest(header, expected):
        raise HTTPException(status_code=401, detail="Неверный X-Lab-Token")


def _repo(raw: str) -> tuple[str, str, str, str]:
    text = raw.strip()
    if not text:
        raise HTTPException(status_code=400, detail="Пустой URL")
    if text.startswith("http://"):
        text = "https://" + text[7:]
    elif not text.startswith("https://"):
        text = "https://" + text
    parsed = urlparse(text)
    if parsed.scheme != "https" or (parsed.hostname or "").lower() not in ALLOWED_HOSTS:
        raise HTTPException(status_code=400, detail="Только https://github.com/owner/repo")
    parts = [part for part in parsed.path.split("/") if part]
    if len(parts) < 2:
        raise HTTPException(status_code=400, detail="Нужен owner/repo")
    owner, name = parts[0], parts[1].removesuffix(".git")
    if not re.fullmatch(r"[A-Za-z0-9](?:[A-Za-z0-9._-]{0,38})", owner):
        raise HTTPException(status_code=400, detail="Некорректный owner")
    if not re.fullmatch(r"[A-Za-z0-9._-]{1,100}", name):
        raise HTTPException(status_code=400, detail="Некорректное имя репозитория")
    ref = ""
    if len(parts) >= 4 and parts[2] == "tree":
        ref = "/".join(parts[3:])
    elif len(parts) >= 5 and parts[2] == "releases" and parts[3] == "tag":
        ref = "/".join(parts[4:])
    return owner, name, f"https://github.com/{owner}/{name}", ref


def _clean_ref(ref: str) -> str:
    value = (ref or "").strip()
    if not value:
        return ""
    if value.startswith("-") or ".." in value or not re.fullmatch(r"[A-Za-z0-9._/-]{1,100}", value):
        raise HTTPException(status_code=400, detail="Некорректная ветка/тег")
    return value


def _dir_for(url: str, ref: str) -> Path:
    digest = hashlib.sha256(f"{url}@{ref}".encode("utf-8")).hexdigest()[:16]
    return WORK / digest


def _safe_dir(raw: str) -> Path:
    try:
        path = Path(raw).resolve()
        root = WORK.resolve()
        path.relative_to(root)
    except (ValueError, OSError):
        raise HTTPException(status_code=403, detail="Каталог вне лаборатории") from None
    if not path.is_dir():
        raise HTTPException(status_code=404, detail="Каталог не найден")
    return path


def _project_type(directory: Path) -> str:
    names = {child.name for child in directory.iterdir()} if directory.is_dir() else set()
    if names & {"build.gradle", "build.gradle.kts", "settings.gradle", "settings.gradle.kts"}:
        return "android"
    if names & {"requirements.txt", "pyproject.toml"}:
        return "python"
    if "package.json" in names:
        return "node"
    return "other"


def _scripts(directory: Path) -> list[dict[str, str]]:
    out: list[dict[str, str]] = []
    skip = {".git", ".venv", "venv", "node_modules", "build", "dist", "__pycache__", ".gradle"}
    if not directory.is_dir():
        return out
    for path in directory.rglob("*"):
        rel = path.relative_to(directory)
        if len(rel.parts) > 3 or any(part in skip for part in rel.parts):
            continue
        if not path.is_file():
            continue
        kind = SCRIPT_EXT.get(path.suffix.lower())
        if kind is None:
            continue
        out.append({"name": path.name, "path": str(rel), "type": kind})
        if len(out) >= 80:
            break
    return sorted(out, key=lambda item: item["path"].lower())


def _append_log(directory: Path, text: str) -> None:
    try:
        with (directory / "lab.log").open("a", encoding="utf-8") as handle:
            handle.write(text.rstrip() + "\n")
    except OSError:
        pass


def _clone(url: str, ref: str) -> dict:
    owner, name, clean_url, url_ref = _repo(url)
    branch = _clean_ref(ref) or _clean_ref(url_ref)
    directory = _dir_for(clean_url, branch)
    WORK.mkdir(parents=True, exist_ok=True)
    os.chmod(WORK, 0o700)
    if directory.exists():
        shutil.rmtree(directory, ignore_errors=True)
    directory.mkdir(parents=True, exist_ok=True)
    os.chmod(directory, 0o700)

    command = ["git", "clone", "--depth", "1", "--single-branch"]
    if branch:
        command += ["--branch", branch]
    command += [clean_url, str(directory)]
    started = time.time()
    try:
        result = subprocess.run(
            command, capture_output=True, text=True, timeout=min(DEFAULT_TIMEOUT, 900)
        )
    except FileNotFoundError:
        raise HTTPException(status_code=503, detail="git не установлен: pkg install -y git") from None
    except subprocess.TimeoutExpired:
        raise HTTPException(status_code=504, detail="Клонирование не уложилось в таймаут") from None
    _append_log(directory, "$ git clone --depth 1 --single-branch " + clean_url)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "git clone failed")[-500:]
        raise HTTPException(status_code=502, detail=detail)
    _append_log(directory, f"# clone ok, {int((time.time() - started) * 1000)} ms")
    project_type = _project_type(directory)
    return {
        "url": clean_url,
        "ref": branch,
        "dir": str(directory),
        "type": project_type,
        "scripts": _scripts(directory),
    }


def _scan(raw_dir: str) -> dict:
    directory = _safe_dir(raw_dir)
    return {"dir": str(directory), "type": _project_type(directory), "scripts": _scripts(directory)}


def _run(req: RunRequest) -> dict:
    directory = _safe_dir(req.dir)
    command = (req.command or "").strip()
    if not command:
        raise HTTPException(status_code=400, detail="Пустая команда")
    if len(command) > 4000:
        raise HTTPException(status_code=400, detail="Команда слишком длинная")
    try:
        argv = shlex.split(command)
    except ValueError as exc:
        raise HTTPException(status_code=400, detail=f"Разбор команды: {exc}") from None
    if not argv:
        raise HTTPException(status_code=400, detail="Пустая команда")
    exe = argv[0]
    name = os.path.basename(exe)
    if name in BLOCKED_BIN or exe in BLOCKED_BIN:
        raise HTTPException(status_code=403, detail="sudo/su/doas/rm запрещены")
    if name not in ALLOWED_BIN and not exe.startswith("./"):
        raise HTTPException(status_code=403, detail=f"Команда {name} не в белом списке")
    if name in {"bash", "sh"} and "-c" in argv[1:]:
        raise HTTPException(status_code=403, detail="bash -c запрещён: запускайте файл скрипта")
    if exe.startswith("./"):
        target = (directory / exe[2:]).resolve()
        try:
            target.relative_to(directory.resolve())
        except ValueError:
            raise HTTPException(status_code=403, detail="Скрипт вне проекта") from None
    timeout = max(1, min(int(req.timeout or DEFAULT_TIMEOUT), 1800))
    env = dict(os.environ)
    env.update({"TERM": "dumb", "JB_LAB": "1", "GIT_TERMINAL_PROMPT": "0"})
    started = time.time()
    _append_log(directory, "$ " + command)
    try:
        proc = subprocess.run(
            argv, cwd=str(directory), capture_output=True, text=True,
            timeout=timeout, env=env,
        )
        code, stdout, stderr = proc.returncode, proc.stdout, proc.stderr
    except subprocess.TimeoutExpired as exc:
        code = 124
        stdout = (exc.stdout or "") if isinstance(exc.stdout, str) else ""
        stderr = f"Таймаут {timeout} с"
    except FileNotFoundError:
        code, stdout, stderr = 127, "", f"Не найдено: {name}"
    except OSError as exc:
        code, stdout, stderr = 126, "", str(exc)
    took_ms = int((time.time() - started) * 1000)
    _append_log(directory, f"# exit {code}, {took_ms} ms")
    return {
        "exit_code": code,
        "stdout": (stdout or "")[-MAX_OUTPUT:],
        "stderr": (stderr or "")[-MAX_OUTPUT:],
        "took_ms": took_ms,
    }


def _clear(raw_dir: str) -> dict:
    directory = _safe_dir(raw_dir)
    shutil.rmtree(directory, ignore_errors=True)
    return {"ok": True}


def _export(raw_dir: str) -> dict:
    directory = _safe_dir(raw_dir)
    storage = HOME / "storage" / "downloads"
    if not storage.is_dir():
        raise HTTPException(
            status_code=409,
            detail="Сначала выполните termux-setup-storage",
        )
    stamp = time.strftime("%Y%m%d-%H%M%S")
    target = storage / "JB-Osint" / "reports" / f"{directory.name}-{stamp}"
    target.mkdir(parents=True, exist_ok=True)
    log = directory / "lab.log"
    if log.is_file():
        shutil.copy2(log, target / "lab.log")
    copied = 0
    for path in directory.rglob("*"):
        if copied >= 50:
            break
        if path.is_file() and path.suffix.lower() in {".apk", ".jar"}:
            try:
                shutil.copy2(path, target / path.name)
                copied += 1
            except OSError:
                continue
    return {"path": str(target), "files": copied}


@app.get("/health")
def health(x_lab_token: str | None = Header(default=None)) -> dict:
    _auth(x_lab_token)
    return {"ok": True, "version": app.version}


@app.post("/clone")
def clone(req: CloneRequest, x_lab_token: str | None = Header(default=None)) -> dict:
    _auth(x_lab_token)
    return _clone(req.url, req.ref)


@app.post("/scan")
def scan(req: DirRequest, x_lab_token: str | None = Header(default=None)) -> dict:
    _auth(x_lab_token)
    return _scan(req.dir)


@app.post("/run")
def run(req: RunRequest, x_lab_token: str | None = Header(default=None)) -> dict:
    _auth(x_lab_token)
    return _run(req)


@app.post("/clear")
def clear(req: DirRequest, x_lab_token: str | None = Header(default=None)) -> dict:
    _auth(x_lab_token)
    return _clear(req.dir)


@app.post("/export")
def export(req: DirRequest, x_lab_token: str | None = Header(default=None)) -> dict:
    _auth(x_lab_token)
    return _export(req.dir)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="127.0.0.1", port=int(os.environ.get("JB_LAB_PORT", "8081")))
