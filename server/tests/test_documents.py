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


def blank_pdf() -> bytes:
    """PDF без текстового слоя — имитация скана."""
    from pypdf import PdfWriter

    writer = PdfWriter()
    writer.add_blank_page(width=595, height=842)
    buffer = io.BytesIO()
    writer.write(buffer)
    return buffer.getvalue()


def test_scanned_pdf_falls_back_to_ocr(monkeypatch):
    monkeypatch.setattr("app.documents.ocr_available", lambda: True)
    monkeypatch.setattr(
        "app.documents.ocr_pdf",
        lambda data, lang="rus+eng", max_pages=12: "Распознанный текст скана",
    )
    result = extract_document(blank_pdf(), "scan.pdf")
    assert result["provider"] == "ocr"
    assert "Распознанный" in result["text"]


def test_scan_without_tesseract_is_rejected(monkeypatch):
    monkeypatch.setattr("app.documents.ocr_available", lambda: False)
    try:
        extract_document(blank_pdf(), "scan.pdf")
    except UnsupportedDocument as exc:
        assert "tesseract" in str(exc)
    else:  # pragma: no cover - защита от тихого пропуска
        raise AssertionError("ожидался отказ без tesseract")


def test_image_scan_goes_to_ocr(monkeypatch):
    monkeypatch.setattr("app.documents.ocr_available", lambda: True)
    monkeypatch.setattr(
        "app.documents.ocr_image", lambda data, lang="rus+eng": "Скан квитанции"
    )
    result = extract_document(b"\x89PNG\r\n\x1a\n fake", "receipt.png")
    assert result["provider"] == "ocr"
    assert "квитанции" in result["text"]


def test_text_pdf_does_not_run_ocr(monkeypatch):
    def boom(*args, **kwargs):
        raise AssertionError("OCR не должен запускаться для текстового PDF")

    monkeypatch.setattr("app.documents.from_pdf", lambda data: "Договор аренды помещения " * 5)
    monkeypatch.setattr("app.documents.ocr_pdf", boom)
    result = extract_document(b"%PDF-1.4 fake", "contract.pdf")
    assert result["provider"] == "pypdf"
    assert "Договор" in result["text"]
