"""Coleta de feeds RSS/Atom e conversão para Article normalizado."""
from __future__ import annotations

import logging
import re
from dataclasses import dataclass, field
from datetime import datetime, timedelta

import feedparser
import requests

from backend.collectors.http_client import FetchError, FetchResult, fetch
from backend.collectors.normalization import (
    article_id, clean_text, normalize_url, struct_to_datetime, truncate,
)
from backend.collectors.source_manager import Source
from backend.config.settings import FIXTURES_DIR
from backend.models.article import Article

log = logging.getLogger(__name__)
EXCERPT_LIMIT = 600


@dataclass
class SourceResult:
    source_id: str
    ok: bool
    status: str
    articles: list[Article] = field(default_factory=list)
    entries_seen: int = 0
    error: str | None = None
    etag: str | None = None
    last_modified: str | None = None


def parse_entries(content: bytes, source: Source, collected_at: datetime, *, is_sample: bool = False) -> list[Article]:
    parsed = feedparser.parse(content)
    if parsed.bozo and not parsed.entries:
        raise FetchError(f"feed inválido: {type(parsed.bozo_exception).__name__}")
    feed_lang = (parsed.feed.get("language") or source.language or "en").split("-")[0].lower()
    articles: list[Article] = []
    for entry in parsed.entries:
        link = (entry.get("link") or "").strip()
        title = clean_text(entry.get("title"))
        if not link or not title:
            continue
        norm = normalize_url(link)
        summary_html = entry.get("summary") or ""
        if not summary_html and entry.get("content"):
            summary_html = entry["content"][0].get("value", "")
        excerpt = truncate(clean_text(summary_html), EXCERPT_LIMIT)
        # Hacker News e arXiv incluem textos padrão ("Comments", "Announce Type") sem valor.
        if excerpt.lower() in {"comments", ""}:
            excerpt = ""
        excerpt = re.sub(r"^arXiv:\S+\s+Announce Type:\s*\w+\s+Abstract:\s*", "", excerpt)
        discussion = entry.get("comments") if source.source_type == "aggregator" else None
        published = struct_to_datetime(entry.get("published_parsed") or entry.get("updated_parsed"))
        articles.append(
            Article(
                id=article_id(norm),
                original_title=title,
                source_id=source.id,
                source_name=source.name,
                source_type=source.source_type,
                source_url=link,
                discussion_url=discussion,
                published_at=published,
                collected_at=collected_at,
                language=feed_lang,
                excerpt=excerpt,
                category=source.category,
                source_priority=source.priority,
                is_sample=is_sample,
            )
        )
    return articles


_REL_DATE_RE = re.compile(r"__NOW_MINUS_(\d+)H__")


def _load_fixture(name: str, now: datetime) -> bytes:
    """Fixtures fictícias com datas relativas (__NOW_MINUS_5H__) para dados de dev realistas."""
    text = (FIXTURES_DIR / name).read_text(encoding="utf-8")
    text = _REL_DATE_RE.sub(lambda m: (now - timedelta(hours=int(m.group(1)))).strftime("%Y-%m-%dT%H:%M:%SZ"), text)
    return text.encode("utf-8")


def _fetch_source(source: Source, session: requests.Session, cache: dict, timeout: int, now: datetime) -> FetchResult:
    if source.url.startswith("fixture://"):
        return FetchResult(status=200, content=_load_fixture(source.url.removeprefix("fixture://"), now))
    return fetch(
        source.url, session, etag=cache.get("etag"), last_modified=cache.get("last_modified"), timeout=timeout
    )


def collect_source(
    source: Source,
    session: requests.Session,
    *,
    cache: dict,
    collected_at: datetime,
    max_age_days: int,
    keyword_re: re.Pattern | None,
    timeout: int = 20,
    is_sample: bool = False,
) -> SourceResult:
    try:
        result = _fetch_source(source, session, cache, timeout, collected_at)
    except FetchError as exc:
        return SourceResult(source.id, ok=False, status="error", error=str(exc))
    except Exception as exc:  # noqa: BLE001 — uma fonte nunca derruba a coleta
        return SourceResult(source.id, ok=False, status="error", error=type(exc).__name__)

    if result.not_modified:
        return SourceResult(source.id, ok=True, status="not_modified", etag=result.etag, last_modified=result.last_modified)

    try:
        articles = parse_entries(result.content, source, collected_at, is_sample=is_sample)
    except FetchError as exc:
        return SourceResult(source.id, ok=False, status="error", error=str(exc))

    seen = len(articles)
    cutoff = collected_at - timedelta(days=max_age_days)
    fresh = [a for a in articles if a.published_at is None or a.published_at >= cutoff]
    if source.require_keywords and keyword_re is not None:
        fresh = [a for a in fresh if keyword_re.search(f"{a.original_title} {a.excerpt}")]
    fresh.sort(key=lambda a: a.reference_time, reverse=True)
    return SourceResult(
        source.id, ok=True, status="ok", articles=fresh, entries_seen=seen,
        etag=result.etag, last_modified=result.last_modified,
    )
