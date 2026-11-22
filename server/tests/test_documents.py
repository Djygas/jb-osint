"""Извлечение текста из документов: DOCX/TXT/PDF и отказы для остального."""
import io
import zipfile

from fastapi.testclient import TestClient

from app.documents import UnsupportedDocument, extract_document
from app.main import app

client = TestClient(app)


def docx_bytes(text: str) -> bytes:
    buffer = io.BytesIO()
    with zipfile.ZipFile(buffer, "w") as archive:
        archive.writestr(
            "[Content_Types].xml",
            '<?xml version="1.0"?><Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"/>',
        )
        archive.writestr(
            "word/document.xml",
            '<?xml version="1.0"?><w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main">'
            f"<w:body><w:p><w:r><w:t>{text}</w:t></w:r></w:p><w:p><w:r><w:t>Второй абзац</w:t></w:r></w:p></w:body></w:document>",
        )
    return buffer.getvalue()


def test_txt_utf8_is_extracted():
    data = "Договор аренды. Почта: info@example.ru".encode("utf-8")
    result = extract_document(data, "contract.txt")
    assert result["provider"] == "stdlib"
    assert "Договор аренды" in result["text"]
    assert result["chars"] > 10


def test_txt_cp1251_is_decoded():
    data = "Протокол собрания".encode("cp1251")
    result = extract_document(data, "protokol.txt")
    assert "Протокол собрания" in result["text"]


def test_docx_paragraphs_are_extracted():
    result = extract_document(docx_bytes("Реквизиты компании"), "rekvizity.docx")
    assert "Реквизиты компании" in result["text"]
    assert "Второй абзац" in result["text"]


def test_unknown_format_is_rejected():
    try:
        extract_document(b"\x00\x01\x02", "photo.psd")
    except UnsupportedDocument as exc:
        assert "не поддержан" in str(exc)
    else:  # pragma: no cover - защита от тихого пропуска
        raise AssertionError("ожидался отказ для неизвестного формата")


def test_endpoint_returns_text():
    response = client.post(
        "/documents/extract?name=note.txt",
        content="Служебная записка".encode("utf-8"),
        headers={"content-type": "application/octet-stream"},
    )
    assert response.status_code == 200, response.text
    data = response.json()
    assert data["chars"] > 0
    assert "Служебная записка" in data["text"]


def test_endpoint_rejects_unknown_format():
    response = client.post(
        "/documents/extract?name=scan.psd",
        content=b"\x00\x01",
        headers={"content-type": "application/octet-stream"},
    )
    assert response.status_code == 415


def test_endpoint_rejects_empty_body():
    response = client.post(
        "/documents/extract?name=empty.txt",
        content=b"",
        headers={"content-type": "application/octet-stream"},
    )
    assert response.status_code == 400
