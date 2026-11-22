import json

from app.masking import mask_har, mask_json, mask_text


def test_masks_email_phone_and_ip():
    text = "Почта ivan@example.com, тел +7 (999) 123-45-67, IP 8.8.8.8"
    out = mask_text(text)
    assert "ivan@example.com" not in out
    assert "+7" not in out
    assert "8.8.8.8" not in out
    assert "***" in out


def test_masks_sensitive_keys_recursively():
    data = {
        "email": "a@b.ru",
        "profile": {"phone": "+79991234567"},
        "note": "позвонить a@b.ru",
        "safe": "application/json",
    }
    out = mask_json(data)
    assert out["email"] == "***"
    assert out["profile"]["phone"] == "***"
    assert "a@b.ru" not in out["note"]
    assert out["safe"] == "application/json"


def test_mask_har_counts_and_removes_values():
    har = {
        "log": {
            "entries": [
                {
                    "request": {
                        "method": "GET",
                        "url": "https://api.test/v1/users?email=a@b.ru",
                        "headers": [
                            {"name": "Accept", "value": "application/json"},
                            {
                                "name": "Authorization",
                                "value": "Bearer abcdefghijklmnopqrstuvwxyz012345",
                            },
                        ],
                        "cookies": [{"name": "sid", "value": "a@b.ru"}],
                    }
                }
            ]
        }
    }
    masked, counts = mask_har(har)
    text = json.dumps(masked, ensure_ascii=False)
    assert counts["emails"] >= 1
    assert counts["tokens"] >= 1
    assert "a@b.ru" not in text
    assert "abcdefghijklmnopqrstuvwxyz012345" not in text
    assert "application/json" in text
