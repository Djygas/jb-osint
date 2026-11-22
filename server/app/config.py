"""Настройки сервера из окружения (.env в Docker Compose)."""
from __future__ import annotations

import os
import tempfile
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    api_key: str = os.getenv("JB_API_KEY", "")
    openai_base_url: str = os.getenv("OPENAI_BASE_URL", "https://api.openai.com/v1")
    openai_api_key: str = os.getenv("OPENAI_API_KEY", "")
    openai_model: str = os.getenv("OPENAI_MODEL", "gpt-4o-mini")
    rae_bin: str = os.getenv("RAE_BIN", "reverse-api-engineer")
    rae_timeout: int = int(os.getenv("RAE_TIMEOUT", "900"))
    allow_external_llm: bool = os.getenv("ALLOW_EXTERNAL_LLM", "0") == "1"
    rate_limit_per_sec: int = int(os.getenv("RATE_LIMIT_PER_SEC", "10"))
    work_dir: str = os.getenv("WORK_DIR", os.path.join(tempfile.gettempdir(), "jb-osint"))


settings = Settings()
