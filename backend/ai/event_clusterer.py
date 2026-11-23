"""Agrupa notícias de veículos diferentes sobre o mesmo acontecimento (eventos).

Duas camadas: similaridade de títulos (determinística, sempre disponível) e
relação semântica indicada pela IA (related_event_id), quando houver cota.
Fontes independentes nunca são descartadas: todas ficam listadas no evento.
"""
from __future__ import annotations

from collections import Counter, defaultdict
from datetime import timedelta
from typing import Iterable

from backend.models.article import Article
from backend.models.edition import Event, EventSource
from backend.storage.deduplication import title_similarity, title_tokens

TYPE_RANK = {"primary": 0, "research": 1, "press": 2, "aggregator": 3}


def _distinctive(title: str) -> set[str]:
    """Termos com 5+ letras: nomes, lugares e substantivos que identificam o fato."""
    return {t for t in title_tokens(title) if len(t) >= 5 and not t.isdigit()}


def assign_events_by_title(
    new_articles: list[Article],
    existing: Iterable[Article],
    *,
    threshold: float = 0.6,
    min_shared: int = 3,
    window_hours: int = 72,
    distinctive_shared: int = 4,
    distinctive_window_hours: int = 36,
) -> None:
    """Agrupa coberturas do mesmo fato por fontes diferentes.

    Duas regras: títulos muito parecidos (redação quase igual) ou, para portais que
    redigem a manchete de outro jeito, 4+ termos distintivos em comum num intervalo de 36 h
    (ex.: "Alemanha... ameaças de bomba... escolas" no g1 e na Folha).
    """
    pool = [a for a in existing if a.event_id]
    window = timedelta(hours=window_hours)
    dwindow = timedelta(hours=distinctive_window_hours)
    for art in sorted(new_articles, key=lambda a: a.reference_time):
        best, best_score = None, 0.0
        art_terms = _distinctive(art.original_title)
        for other in pool:
            gap = abs(other.reference_time - art.reference_time)
            if other.source_id == art.source_id or other.category != art.category or gap > window:
                continue
            score, shared = title_similarity(art.original_title, other.original_title)
            if shared >= min_shared and score >= threshold and score > best_score:
                best, best_score = other, score
            elif gap <= dwindow and len(art_terms & _distinctive(other.original_title)) >= distinctive_shared:
                alt = 0.5 + 0.01 * len(art_terms & _distinctive(other.original_title))
                if alt > best_score:
                    best, best_score = other, alt
        art.event_id = best.event_id if best is not None else f"evt-{art.id}"
        pool.append(art)


def apply_ai_relations(articles: dict[str, Article], relations: dict[str, str]) -> int:
    """Move um artigo isolado para o evento que a IA identificou como o mesmo acontecimento."""
    sizes = Counter(a.event_id for a in articles.values())
    valid = set(sizes)
    moved = 0
    for aid, evt in relations.items():
        art = articles.get(aid)
        if art is None or evt not in valid or evt == art.event_id or sizes[art.event_id] != 1:
            continue
        sizes[art.event_id] -= 1
        art.event_id = evt
        sizes[evt] += 1
        moved += 1
    return moved


def _representative(items: list[Article]) -> Article:
    return sorted(
        items,
        key=lambda a: (not a.ai_processed, TYPE_RANK.get(a.source_type, 9), -a.source_priority, a.reference_time),
    )[0]


def build_events(articles: Iterable[Article]) -> list[Event]:
    groups: dict[str, list[Article]] = defaultdict(list)
    for a in articles:
        if a.event_id and not a.off_topic:
            groups[a.event_id].append(a)
    events: list[Event] = []
    for evt_id, items in groups.items():
        rep = _representative(items)
        items_sorted = sorted(items, key=lambda a: a.reference_time)
        scores = [a.relevance_score for a in items if a.relevance_score is not None]

        def union(attr: str) -> list[str]:
            seen: dict[str, str] = {}
            for a in items_sorted:
                for v in getattr(a, attr):
                    seen.setdefault(v.lower(), v)
            return list(seen.values())[:12]

        events.append(Event(
            id=evt_id,
            title=rep.display_title,
            summary=rep.summary,
            category=rep.category,
            subcategory=rep.subcategory,
            first_seen=items_sorted[0].reference_time,
            last_seen=items_sorted[-1].reference_time,
            article_ids=[a.id for a in items_sorted],
            sources=[
                EventSource(
                    article_id=a.id, source_id=a.source_id, source_name=a.source_name,
                    source_url=a.source_url, title=a.original_title, published_at=a.published_at,
                ) for a in items_sorted
            ],
            source_count=len({a.source_id for a in items}),
            entities=union("entities"),
            technologies=union("technologies"),
            tags=union("tags"),
            relevance_score=max(scores) if scores else None,
            ai_processed=any(a.ai_processed for a in items),
        ))
    return events
