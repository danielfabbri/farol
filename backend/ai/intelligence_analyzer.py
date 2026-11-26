"""Ranking, seleção de destaques, edições e sinais de tendência.

Tudo aqui é determinístico (sem chamadas de IA): funciona mesmo quando a cota acaba.
"""
from __future__ import annotations

import re
from collections import Counter, defaultdict
from datetime import datetime, timedelta
from zoneinfo import ZoneInfo

from backend.collectors.normalization import keyword_pattern
from backend.models.article import Article
from backend.models.edition import Event, Trend

BRT = ZoneInfo("America/Sao_Paulo")


def compute_tags(article: Article, filters: list[dict]) -> list[str]:
    text = f"{article.original_title} {article.title_pt or ''} {article.excerpt}"
    ents = {e.lower() for e in article.entities + article.technologies}
    tags = []
    for f in filters:
        if article.subcategory and article.subcategory in f.get("subcategories", []):
            tags.append(f["id"])
        elif ents & {e.lower() for e in f.get("entities", [])}:
            tags.append(f["id"])
        elif f.get("keywords") and keyword_pattern(f["keywords"]).search(text):
            tags.append(f["id"])
    return tags


def score_event(event: Event, articles: dict[str, Article], now: datetime) -> float:
    items = [articles[i] for i in event.article_ids if i in articles]
    if event.relevance_score is not None:
        base = float(event.relevance_score)
    else:  # sem IA: prioridade editorial da fonte
        base = 30.0 + 6.0 * max((a.source_priority for a in items), default=3)
    # Assunto que vários veículos estão cobrindo é o assunto do dia: é o sinal mais forte de relevância.
    coverage = min(32.0, 8.0 * (event.source_count - 1))
    # Imprensa à frente de blogs de empresas e artigos acadêmicos (estes só sobem se a imprensa repercutir).
    types = {a.source_type for a in items}
    kind = 0.0 if ("press" in types or not types) else (-8.0 if "primary" in types else -14.0)
    age_h = max(0.0, (now - event.last_seen).total_seconds() / 3600)
    decay = max(0.0, age_h - 12) * 0.6
    return round(base + coverage + kind - decay, 2)


def select_highlights(events: list[Event], now: datetime, limit: int, window_hours: int = 36) -> list[Event]:
    cutoff = now - timedelta(hours=window_hours)
    recent = [e for e in events if e.last_seen >= cutoff]
    if len(recent) < limit:  # dias fracos: amplia a janela em vez de deixar a edição vazia
        recent = [e for e in events if e.last_seen >= now - timedelta(hours=window_hours * 2)]
    # Pequena preferência por eventos já analisados (têm resumo), sem ignorar o ranking.
    recent.sort(key=lambda e: e.score + (5 if e.ai_processed else 0), reverse=True)
    return recent[:limit]


def edition_identity(now: datetime) -> tuple[str, str]:
    local = now.astimezone(BRT)
    edition_id = now.strftime("%Y-%m-%d-%H%M")
    h = local.hour + local.minute / 60
    if h < 9:
        label = "Edição da manhã"
    elif h < 15:
        label = "Edição do meio-dia"
    elif h < 19.5:
        label = "Edição da tarde"
    else:
        label = "Edição da noite"
    return edition_id, label


def _norm_key(value: str) -> str:
    return re.sub(r"\s+", " ", value.strip().lower())


def build_trends(articles: list[Article], now: datetime, *, days: int = 7, min_articles: int = 3, min_sources: int = 2) -> list[Trend]:
    """Sinais de recorrência: assuntos citados por várias fontes ao longo dos dias.

    São contagens, não previsões. A interpretação da evolução virá na Fase 4.
    """
    cutoff = now - timedelta(days=days)
    buckets: dict[tuple[str, str], list[Article]] = defaultdict(list)
    labels: dict[tuple[str, str], Counter] = defaultdict(Counter)
    for a in articles:
        if a.off_topic or not a.ai_processed or a.reference_time < cutoff:
            continue
        for kind, values in (("entity", a.entities), ("technology", a.technologies)):
            for v in {_norm_key(x): x for x in values}.values():
                key = (kind, _norm_key(v))
                buckets[key].append(a)
                labels[key][v] += 1
    trends: list[Trend] = []
    today = now.date()
    for (kind, key), items in buckets.items():
        sources = {a.source_id for a in items}
        if len(items) < min_articles or len(sources) < min_sources:
            continue
        daily = Counter(a.reference_time.date().isoformat() for a in items)
        first = min(a.reference_time for a in items)
        last = max(a.reference_time for a in items)
        recent = sum(c for d, c in daily.items() if d >= (today - timedelta(days=1)).isoformat())
        older = sum(daily.values()) - recent
        if first >= now - timedelta(hours=48):
            status = "emerging"
        elif recent > older / max(1, days - 2) * 2:
            status = "rising"
        else:
            status = "steady"
        related = Counter(e for a in items for e in a.entities if _norm_key(e) != key)
        trends.append(Trend(
            key=f"{kind}:{key}", label=labels[(kind, key)].most_common(1)[0][0], kind=kind,
            first_seen=first, last_seen=last, article_count=len(items), source_count=len(sources),
            event_count=len({a.event_id for a in items}), daily_counts=dict(sorted(daily.items())),
            related_entities=[e for e, _ in related.most_common(6)],
            event_ids=list(dict.fromkeys(a.event_id for a in sorted(items, key=lambda x: x.reference_time, reverse=True) if a.event_id))[:10],
            status=status,
        ))
    trends.sort(key=lambda t: (t.source_count, t.article_count), reverse=True)
    return trends[:30]
