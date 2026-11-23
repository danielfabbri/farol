"""Deduplicação por URL normalizada, fonte e similaridade de títulos."""
from __future__ import annotations

import re
import unicodedata
from datetime import date, timedelta
from difflib import SequenceMatcher

from backend.models.article import Article

_STOPWORDS = {
    "a", "an", "the", "and", "or", "of", "to", "in", "on", "for", "with", "by", "at", "from", "is",
    "are", "be", "its", "it", "as", "how", "why", "what", "new", "now", "this", "that", "into", "your",
    "you", "we", "our", "will", "can", "after", "about", "says", "say", "o", "os", "as", "um", "uma",
    "de", "do", "da", "dos", "das", "e", "em", "no", "na", "para", "por", "com", "que", "se",
}
_TOKEN_RE = re.compile(r"[a-z0-9][a-z0-9.\-+]*")


def title_tokens(title: str) -> set[str]:
    t = unicodedata.normalize("NFKD", title.lower())
    t = "".join(c for c in t if not unicodedata.combining(c))
    return {tok.strip(".-") for tok in _TOKEN_RE.findall(t) if tok.strip(".-") not in _STOPWORDS and len(tok) > 1}


def title_key(title: str) -> str:
    return " ".join(sorted(title_tokens(title)))


def title_similarity(a: str, b: str) -> tuple[float, int]:
    """Retorna (similaridade 0..1, nº de termos em comum)."""
    ta, tb = title_tokens(a), title_tokens(b)
    if not ta or not tb:
        return 0.0, 0
    shared = len(ta & tb)
    jaccard = shared / len(ta | tb)
    seq = SequenceMatcher(None, " ".join(sorted(ta)), " ".join(sorted(tb))).ratio()
    # Jaccard domina; a sequência só refina variações de grafia ("launch"/"launches").
    return 0.7 * jaccard + 0.3 * seq, shared


class Deduplicator:
    """Mantém o índice de itens já vistos (persistido em data/state/seen.json)."""

    def __init__(self, seen: dict | None = None):
        self.seen: dict[str, dict] = dict(seen or {})
        self._by_source_title = {(v.get("s"), v.get("t")) for v in self.seen.values()}

    def is_duplicate(self, article: Article) -> bool:
        if article.id in self.seen:
            return True
        # Mesma fonte republicando o mesmo título com outra URL.
        return (article.source_id, title_key(article.original_title)) in self._by_source_title

    def register(self, article: Article) -> None:
        tk = title_key(article.original_title)
        self.seen[article.id] = {"s": article.source_id, "t": tk, "d": article.collected_at.date().isoformat()}
        self._by_source_title.add((article.source_id, tk))

    def prune(self, today: date, keep_days: int) -> None:
        cut = (today - timedelta(days=keep_days)).isoformat()
        self.seen = {k: v for k, v in self.seen.items() if v.get("d", "") >= cut}
        self._by_source_title = {(v.get("s"), v.get("t")) for v in self.seen.values()}
