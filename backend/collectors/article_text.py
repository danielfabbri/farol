"""Texto da matéria para a IA resumir (não é publicado nem guardado).

O RSS traz só a chamada (muitas vezes uma frase). Para o resumo explicar a notícia de verdade,
lemos a página da própria matéria e extraímos o corpo do texto (trafilatura, gratuito e local).
O texto serve apenas de insumo para o resumo nesta execução: não vai para o app nem para o repositório.
Páginas com paywall costumam entregar só o início — tudo bem, o resumo usa o que houver.

Aproveitamos a mesma página para preencher a imagem de destaque, se o feed não trouxe uma.
"""
from __future__ import annotations

import json
import logging
import re
from concurrent.futures import ThreadPoolExecutor

import requests

from backend.collectors.http_client import USER_AGENT
from backend.collectors.image_enricher import find_page_image
from backend.models.article import Article

log = logging.getLogger(__name__)

try:  # dependência opcional: sem ela, usa o articleBody do JSON-LD ou os parágrafos da página
    import trafilatura
except ImportError:  # pragma: no cover
    trafilatura = None

_LD_RE = re.compile(r'<script[^>]+application/ld\+json[^>]*>(.*?)</script>', re.IGNORECASE | re.DOTALL)
_P_RE = re.compile(r"<p\b[^>]*>(.*?)</p>", re.IGNORECASE | re.DOTALL)
_TAG_RE = re.compile(r"<[^>]+>")
_WS_RE = re.compile(r"[ \t\r\f\v]+")
# Linhas que não são texto da notícia (assinatura, convite para newsletter, "leia também"...).
_JUNK = re.compile(
    r"^(leia (também|mais)|veja (também|mais)|assine|cadastre-se|receba (as|nossas)|siga .{0,40} no|"
    r"clique aqui|publicidade|newsletter|compartilhe|ouça|assista)",
    re.IGNORECASE,
)


def _ld_body(html: str) -> str | None:
    for raw in _LD_RE.findall(html):
        try:
            data = json.loads(raw.strip())
        except (ValueError, TypeError):
            continue
        stack = data if isinstance(data, list) else [data]
        while stack:
            node = stack.pop()
            if isinstance(node, dict):
                body = node.get("articleBody")
                if isinstance(body, str) and len(body) > 200:
                    return body
                stack.extend(v for v in node.values() if isinstance(v, (list, dict)))
            elif isinstance(node, list):
                stack.extend(node)
    return None


def _paragraphs(html: str) -> str:
    parts = []
    for p in _P_RE.findall(html):
        text = _WS_RE.sub(" ", _TAG_RE.sub("", p)).strip()
        if len(text) >= 60:
            parts.append(text)
    return "\n".join(parts)


def extract_text(html: str, url: str | None = None) -> str:
    text = None
    if trafilatura is not None:
        try:
            text = trafilatura.extract(html, url=url, include_comments=False, include_tables=False,
                                       favor_precision=True, deduplicate=True)
        except Exception:  # noqa: BLE001
            text = None
    if not text or len(text) < 300:
        text = _ld_body(html) or text or _paragraphs(html)
    lines = [ln.strip() for ln in (text or "").splitlines()]
    lines = [ln for ln in lines if len(ln) > 1 and not _JUNK.match(ln)]
    import html as _html
    return _html.unescape("\n".join(lines)).strip()


def _fetch(session: requests.Session, url: str, timeout: int, max_bytes: int) -> str:
    headers = {"User-Agent": USER_AGENT, "Accept": "text/html", "Accept-Language": "pt-BR,pt;q=0.9,en;q=0.8"}
    with session.get(url, headers=headers, timeout=timeout, stream=True) as resp:
        if resp.status_code != 200 or "html" not in resp.headers.get("Content-Type", "html"):
            return ""
        chunks, size = [], 0
        for chunk in resp.iter_content(32_768):
            chunks.append(chunk)
            size += len(chunk)
            if size >= max_bytes:
                break
        raw = b"".join(chunks)
        enc = resp.encoding if resp.encoding and resp.encoding.lower() != "iso-8859-1" else None
        return raw.decode(enc or "utf-8", errors="replace")


def fetch_article_texts(articles: list[Article], session: requests.Session, *, max_items: int = 120,
                        max_chars: int = 3500, timeout: int = 12, max_bytes: int = 2_000_000,
                        workers: int = 8) -> dict[str, str]:
    """Corpo das matérias (id -> texto, até max_chars). Falhas simplesmente ficam de fora."""
    todo = articles[:max_items]

    def work(a: Article) -> tuple[str, str]:
        try:
            html = _fetch(session, a.source_url, timeout, max_bytes)
        except Exception:  # noqa: BLE001 — falha de rede não interrompe nada
            return a.id, ""
        if not html:
            return a.id, ""
        if not a.image_url:
            a.image_url = find_page_image(html, a.source_url)
            a.image_checked = True
        text = extract_text(html, a.source_url)
        if len(text) > max_chars:
            cut = text[:max_chars]
            text = cut[: cut.rfind(" ")] + "…" if " " in cut else cut
        return a.id, text

    if not todo:
        return {}
    with ThreadPoolExecutor(max_workers=workers) as pool:
        results = dict(pool.map(work, todo))
    texts = {k: v for k, v in results.items() if len(v) >= 200}
    log.info("Texto das matérias: %d de %d páginas lidas", len(texts), len(todo))
    return texts
