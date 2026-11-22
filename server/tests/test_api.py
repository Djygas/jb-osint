import json
from pathlib import Path

from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)

HAR = {
    "log": {
        "entries": [
            {
                "request": {
                    "method": "GET",
                    "url": "https://api.example.com/v1/users?email=ivan@example.com",
                    "headers": [
                        {"name": "Accept", "value": "application/json"},
                        {"name": "Authorization", "value": "Bearer secretsecretsecretsecret123"},
                    ],
                }
            },
            {
                "request": {
                    "method": "POST",
                    "url": "https://api.example.com/v1/search",
                    "headers": [{"name": "Content-Type", "value": "application/json"}],
                }
            },
        ]
    }
}


def test_healthz():
    response = client.get("/healthz")
    assert response.status_code == 200
    assert response.json()["ok"] is True


def test_rae_generates_client_from_masked_har():
    response = client.post(
        "/api/v1/rae/generate",
        json={
            "url": "https://api.example.com",
            "prompt": "собери пользователей",
            "mode": "manual",
            "har": HAR,
        },
    )
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["script_path"].endswith("api_client.py")
    assert data["usage"]["provider"] == "fallback"
    assert data["masked"]["emails"] >= 1
    assert data["masked"]["tokens"] >= 1

    script = Path(data["script_path"]).read_text(encoding="utf-8")
    assert "BASE_URL" in script
    assert "https://api.example.com" in script
    assert "ivan@example.com" not in script
    assert "secretsecretsecretsecret123" not in script


def test_analyze_reports_contradictions_and_masks():
    response = client.post(
        "/ai/analyze",
        json={
            "facts": [
                {"object": "Иванов", "field": "должность", "value": "Директор"},
                {"object": "Иванов", "field": "должность", "value": "Стажёр"},
                {"email": "ivan@example.com"},
            ]
        },
    )
    assert response.status_code == 200
    data = response.json()
    assert len(data["contradictions"]) == 1
    assert data["provider"] == "local-rules"


def test_rae_without_har_and_without_cli_is_503():
    response = client.post(
        "/api/v1/rae/generate",
        json={"url": "https://api.example.com", "prompt": "test", "mode": "agent"},
    )
    assert response.status_code in (400, 503)
