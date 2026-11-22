"""Извлечение текста из документов: MarkItDown → pypdf/stdlib → OCR (tesseract)."""
from __future__ import annotations

import io
import re
import zipfile
from typing import Any

MAX_BYTES = 25_000_000
MAX_OCR_PAGES = 12
_TAGS = re.compile(r"<[^>]+>")
_ENTITIES = (
    ("&nbsp;", " "), ("&amp;", "&"), ("&lt;", "<"), ("&gt;", ">"),
    ("&quot;", '"'), ("&#39;", "'"), ("&apos;", "'"),
)
TEXT_EXT = {
    ".txt", ".md", ".markdown", ".csv", ".json", ".log", ".yml", ".yaml",
    ".xml", ".html", ".htm", ".srt", ".vtt",
}
IMAGE_EXT = {".png", ".jpg", ".jpeg", ".tiff", ".tif", ".bmp", ".webp"}


class UnsupportedDocument(Exception):
    """Формат не поддержан этим сервером."""


def _decode(data: bytes) -> str:
    for encoding in ("utf-8-sig", "utf-8", "cp1251"):
        try:
            return data.decode(encoding)
        except UnicodeDecodeError:
            continue
    return data.decode("utf-8", errors="replace")


def _plain(raw: str) -> str:
    text = _TAGS.sub(" ", raw)
    for entity, char in _ENTITIES:
        text = text.replace(entity, char)
    return re.sub(r"\s+", " ", text).strip()


def from_docx(data: bytes) -> str:
    try:
        with zipfile.ZipFile(io.BytesIO(data)) as archive:
            xml = archive.read("word/document.xml").decode("utf-8", errors="replace")
    except (KeyError, zipfile.BadZipFile) as exc:
        raise UnsupportedDocument("DOCX повреждён или это не DOCX") from exc
    xml = xml.replace("</w:p>", "\n").replace("<w:tab/>", " ").replace("<w:br/>", "\n")
    return re.sub(r"\n{3,}", "\n\n", _plain_lines(xml)).strip()


def _plain_lines(xml: str) -> str:
    """Как _plain, но переводы строк из </w:p> сохраняются."""
    text = _TAGS.sub(" ", xml)
    for entity, char in _ENTITIES:
        text = text.replace(entity, char)
    lines = [re.sub(r"[ \t]+", " ", line).strip() for line in text.split("\n")]
    return "\n".join(line for line in lines if line)


def from_pdf(data: bytes) -> str:
    try:
        from pypdf import PdfReader
    except ImportError as exc:
        raise UnsupportedDocument("PDF: на сервере нет pypdf/MarkItDown") from exc
    try:
        reader = PdfReader(io.BytesIO(data))
        pages = [(page.extract_text() or "") for page in reader.pages[:400]]
    except Exception as exc:  # noqa: BLE001 - библиотека бросает разные типы
        raise UnsupportedDocument(f"PDF не прочитан: {exc}") from exc
    return re.sub(r"\n{3,}", "\n\n", "\n".join(pages)).strip()


def from_markitdown(data: bytes, ext: str) -> str | None:
    """None — MarkItDown не установлен. Ошибки конвертации тоже дают None."""
    try:
        from markitdown import MarkItDown
    except ImportError:
        return None
    try:
        result = MarkItDown().convert_stream(io.BytesIO(data), file_extension=ext or None)
        return getattr(result, "text_content", "") or ""
    except Exception:  # noqa: BLE001 - у MarkItDown широкий набор исключений
        return None


def ocr_available() -> bool:
    """Нужны и пакет pytesseract, и системный бинарь tesseract."""
    try:
        import pytesseract
    except ImportError:
        return False
    try:
        pytesseract.get_tesseract_version()
        return True
    except Exception:  # noqa: BLE001 - tesseract может быть не установлен
        return False


def ocr_image(data: bytes, lang: str = "rus+eng") -> str:
    import pytesseract
    from PIL import Image

    image = Image.open(io.BytesIO(data))
    if image.mode not in ("RGB", "L"):
        image = image.convert("RGB")
    return pytesseract.image_to_string(image, lang=lang).strip()


def ocr_pdf(data: bytes, lang: str = "rus+eng", max_pages: int = MAX_OCR_PAGES) -> str:
    """Скан без текстового слоя: страницы рендерятся PyMuPDF и распознаются tesseract."""
    try:
        import fitz  # PyMuPDF
    except ImportError as exc:
        raise UnsupportedDocument("OCR PDF: нужен PyMuPDF (pip install pymupdf)") from exc
    import pytesseract
    from PIL import Image

    try:
        document = fitz.open(stream=data, filetype="pdf")
    except Exception as exc:  # noqa: BLE001
        raise UnsupportedDocument(f"PDF не открыт для OCR: {exc}") from exc
    parts: list[str] = []
    for page in document[:max_pages]:
        pixmap = page.get_pixmap(dpi=200)
        image = Image.frombytes("RGB", (pixmap.width, pixmap.height), pixmap.samples)
        parts.append(pytesseract.image_to_string(image, lang=lang))
    document.close()
    return re.sub(r"\n{3,}", "\n\n", "\n".join(parts)).strip()


def ocr_document(data: bytes, ext: str) -> str:
    """OCR для скана. Без tesseract — честный отказ с инструкцией."""
    if not ocr_available():
        raise UnsupportedDocument(
            "Скан без текстового слоя: нужен OCR. Установите tesseract "
            "(apt: tesseract-ocr tesseract-ocr-rus, Termux: pkg install tesseract) "
            "и pytesseract"
        )
    return ocr_pdf(data) if ext == ".pdf" else ocr_image(data)


def extract_document(data: bytes, name: str) -> dict[str, Any]:
    if not data:
        raise UnsupportedDocument("Пустой файл")
    if len(data) > MAX_BYTES:
        raise UnsupportedDocument("Файл больше 25 МБ")
    ext = ("." + name.rsplit(".", 1)[-1].lower()) if "." in name else ""

    text = from_markitdown(data, ext)
    provider = "markitdown"
    if text is not None and not text.strip() and (ext == ".pdf" or ext in IMAGE_EXT):
        text = None  # MarkItDown вернул пусто — дальше OCR

    if text is None:
        if ext in TEXT_EXT or ext == "":
            text, provider = _decode(data), "stdlib"
        elif ext == ".docx":
            text, provider = from_docx(data), "stdlib"
        elif ext == ".pdf":
            provider = "pypdf"
            try:
                text = from_pdf(data)
            except UnsupportedDocument:
                text = ""
            # Скан: текстового слоя нет или он символический — распознаём картинки.
            if len(text.strip()) < 80:
                text, provider = ocr_document(data, ext), "ocr"
        elif ext in IMAGE_EXT:
            text, provider = ocr_document(data, ext), "ocr"
        else:
            raise UnsupportedDocument(
                f"Формат {ext or 'без расширения'} не поддержан: нужен MarkItDown"
            )

    text = text.strip()
    if not text:
        raise UnsupportedDocument("Текст не найден (возможно, скан без распознавания)")
    # 400k символов — потолок, чтобы ответ не раздувал мобильный канал.
    return {"filename": name, "ext": ext, "chars": len(text), "provider": provider, "text": text[:400_000]}
