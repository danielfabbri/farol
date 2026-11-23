"""Imagem para notícias cujo feed não traz foto (ex.: Folha, DW).

Lê apenas o início da página da própria notícia e usa a imagem de destaque declarada pelo veículo
(og:image / twitter:image — a mesma que aparece ao compartilhar o link). Guarda só o endereço; a imagem
continua hospedada pelo veículo. Logotipos e imagens genéricas são descartados.
"""
from __future__ import annotations

import logging
import re
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import urljoin

import requests

from backend.collectors.http_client import USER_AGENT
from backend.models.article import Article

log = logging.getLogger(__name__)

_META_RE = re.compile(r"<meta\b[^>]*>", re.IGNORECASE)
_KEYS = ("og:image:secure_url", "og:image", "twitter:image", "twitter:image:src")
_GENERIC = re.compile(r"logo|default|placeholder|avatar|favicon|sprite|share-?image|brand", re.IGNORECASE)


def _attr(tag: str, name: str) -> str | None:
    m = re.search(rf'\b{name}\s*=\s*["\']([^"\']*)["\']', tag, re.IGNORECASE)
    return m.group(1).strip() if m else None


def find_page_image(html: str, page_url: str) -> str | None:
    found: dict[str, str] = {}
    for tag in _META_RE.findall(html):
        key = (_attr(tag, "property") or _attr(tag, "name") or "").lower()
        content = _attr(tag, "content")
        if key in _KEYS and content and key not in found:
            found[key] = content
    for key in _KEYS:
        url = found.get(key)
        if not url:
            continue
        url = urljoin(page_url, url.replace("&amp;", "&"))
        if url.startswith("http://"):
            url = "https://" + url[len("http://"):]
        filename = url.split("?")[0].rstrip("/").rsplit("/", 1)[-1]
        # Só o nome do arquivo indica logotipo ("logo-folha-share.jpg"); o caminho pode conter "default" legítimo.
        if url.startswith("https://") and not _GENERIC.search(filename) and not filename.lower().endswith((".svg", ".gif")):
            return url[:1000]
    return None


def _fetch_head(session: requests.Session, url: str, timeout: int, max_bytes: int) -> str:
    with session.get(url, headers={"User-Agent": USER_AGENT, "Accept": "text/html"}, timeout=timeout, stream=True) as resp:
        if resp.status_code != 200 or "html" not in resp.headers.get("Content-Type", "html"):
            return ""
        chunks, size = [], 0
        for chunk in resp.iter_content(16_384):
            chunks.append(chunk)
            size += len(chunk)
            if size >= max_bytes:  # og:image fica no <head>: não precisamos da página inteira
                break
        return b"".join(chunks).decode(resp.encoding or "utf-8", errors="replace")


def enrich_images(articles: list[Article], session: requests.Session, *, max_items: int = 80,
                  timeout: int = 10, max_bytes: int = 300_000, workers: int = 6) -> int:
    """Preenche image_url das notícias sem imagem. Cada notícia é tentada uma única vez."""
    todo = [a for a in articles if not a.image_url and not a.image_checked][:max_items]

    def work(a: Article) -> bool:
        try:
            html = _fetch_head(session, a.source_url, timeout, max_bytes)
            a.image_url = find_page_image(html, a.source_url) if html else None
        except Exception:  # noqa: BLE001 — falha de rede não interrompe nada
            a.image_url = None
        a.image_checked = True
        return a.image_url is not None

    if not todo:
        return 0
    with ThreadPoolExecutor(max_workers=workers) as pool:
        found = sum(pool.map(work, todo))
    log.info("Imagens buscadas nas páginas: %d de %d", found, len(todo))
    return found
