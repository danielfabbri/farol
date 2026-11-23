"""Orquestra uma execução: coleta → deduplicação → eventos → IA → edição → publicação."""
from __future__ import annotations

import json
import logging
import os
from dataclasses import dataclass, field
from datetime import datetime, timedelta

import requests

from backend.ai.event_clusterer import apply_ai_relations, assign_events_by_title, build_events
from backend.ai.gemini_client import GeminiClient, JsonGenerator
from backend.ai.intelligence_analyzer import (
    build_trends, compute_tags, edition_identity, score_event, select_highlights,
)
from backend.ai.mock_client import MockGeminiClient
from backend.ai.news_processor import AiUsage, NewsProcessor
from backend.collectors.normalization import keyword_pattern, utcnow
from backend.collectors.rss_collector import collect_source
from backend.collectors.source_manager import load_sources
from backend.config.settings import Settings
from backend.models.article import Article
from backend.models.edition import EditionInfo, LatestFeed
from backend.storage.deduplication import Deduplicator
from backend.storage.json_repository import JsonRepository

log = logging.getLogger("farol")
SAMPLE_NOTICE = "DADOS FICTÍCIOS — gerados no modo de desenvolvimento, sem valor informativo."
AI_MESSAGES = {
    "ok": "Análises geradas pela IA. Escores e potenciais são avaliações editoriais, não fatos.",
    "quota_exhausted": "A cota gratuita da IA foi atingida. Notícias novas aparecem sem resumo até a próxima janela.",
    "budget_reached": "Limite de chamadas desta execução atingido; as demais notícias serão analisadas na próxima edição.",
    "disabled": "Processamento por IA desativado.",
    "no_key": "Chave da IA não configurada; exibindo apenas dados coletados.",
    "fatal_error": "A IA ficou indisponível nesta execução; exibindo dados coletados.",
    "unavailable": "O serviço de IA estava sobrecarregado; as notícias novas serão analisadas na próxima edição.",
    "idle": "Nenhuma notícia nova precisava de análise.",
}


@dataclass
class RunResult:
    status: str
    summary: dict = field(default_factory=dict)


def _make_client(settings: Settings, exhausted: set[str]) -> tuple[JsonGenerator | None, str]:
    if not settings.ai_enabled:
        return None, "disabled"
    if settings.ai_mock:
        return MockGeminiClient(), "ok"
    if not settings.has_api_key:
        return None, "no_key"
    return GeminiClient(
        settings.gemini_api_key or "", settings.gemini_model,
        fallback_models=list(settings.gemini_fallback_models), exhausted_models=exhausted,
        min_interval=settings.ai_min_interval_seconds,
    ), "ok"


def run(
    settings: Settings,
    *,
    force: bool = False,
    session: requests.Session | None = None,
    ai_client: JsonGenerator | None = None,
    now: datetime | None = None,
) -> RunResult:
    now = now or utcnow()
    repo = JsonRepository(settings.data_dir)
    run_state = repo.load_state("run_state", {})

    last_success = run_state.get("last_success")
    if not force and last_success and settings.min_minutes_between_runs > 0:
        elapsed = now - datetime.fromisoformat(last_success)
        if timedelta(0) <= elapsed < timedelta(minutes=settings.min_minutes_between_runs):
            log.info("Execução ignorada: a última terminou há %d min (proteção contra duplicadas).", elapsed.seconds // 60)
            return RunResult("skipped", {"reason": "recent_run"})

    cat_doc = json.loads(settings.categories_path.read_text(encoding="utf-8"))
    categories = {c["id"]: c for c in cat_doc["categories"] if c.get("enabled", True)}
    filters = cat_doc.get("filters", [])
    kw = {cid: keyword_pattern(c.get("keywords", [])) for cid, c in categories.items()}
    sources = [s for s in load_sources(settings.sources_path) if s.category in categories]

    session = session or requests.Session()
    http_cache: dict = repo.load_state("http_cache", {})
    dedup = Deduplicator(repo.load_state("seen", {}))
    working: dict[str, Article] = repo.load_recent_articles(now.date(), settings.working_window_days)

    # 1) Coleta
    new_articles: list[Article] = []
    source_report: dict[str, dict] = {}
    for src in sources:
        res = collect_source(
            src, session, cache=http_cache.get(src.id, {}), collected_at=now,
            max_age_days=settings.max_age_days, keyword_re=kw.get(src.category),
            timeout=settings.http_timeout_seconds, is_sample=settings.dev_mode,
        )
        fresh = [a for a in res.articles if a.id not in working and not dedup.is_duplicate(a)]
        taken = fresh[: src.max_items_per_run]
        for a in taken:
            dedup.register(a)
        new_articles.extend(taken)
        if res.ok and res.status == "ok" and len(fresh) <= src.max_items_per_run:
            # Só grava o ETag quando nada ficou para trás; caso contrário o 304 esconderia os restantes.
            http_cache[src.id] = {"etag": res.etag, "last_modified": res.last_modified}
        source_report[src.id] = {"status": res.status, "entries": res.entries_seen, "new": len(taken), "error": res.error}
        level = logging.WARNING if not res.ok else logging.INFO
        log.log(level, "Fonte %-22s %-12s itens=%-4d novos=%d%s", src.id, res.status, res.entries_seen, len(taken),
                f" erro={res.error}" if res.error else "")

    sources_ok = sum(1 for r in source_report.values() if r["status"] != "error")
    sources_failed = len(source_report) - sources_ok

    for a in new_articles:
        a.tags = compute_tags(a, filters)
    assign_events_by_title(new_articles, working.values())
    for a in new_articles:
        working[a.id] = a

    # 2) Inteligência artificial (respeitando a cota)
    usage = AiUsage(repo.load_state("ai_usage", {}), now,
                    daily_limit=settings.max_ai_calls_per_day, run_limit=settings.max_ai_calls_per_run)
    exhausted = set(usage.state.get("exhausted_models", []))
    client, ai_state = (ai_client, "ok") if ai_client is not None else _make_client(settings, exhausted)
    pending_cut = now - timedelta(hours=72)
    pending = sorted(
        (a for a in working.values()
         if not a.ai_processed and a.ai_attempts < settings.ai_max_attempts_per_article and a.reference_time >= pending_cut),
        key=lambda a: (a.source_priority, a.reference_time), reverse=True,
    )
    ai_report = {"processed": 0, "failed": 0, "calls": 0, "errors": []}
    if client is not None and pending:
        recent_events = sorted(
            (e for e in build_events(working.values()) if e.last_seen >= pending_cut),
            key=lambda e: e.last_seen, reverse=True,
        )[: settings.ai_max_context_events]
        for cid, category in categories.items():
            cat_pending = [a for a in pending if a.category == cid]
            if not cat_pending:
                continue
            processor = NewsProcessor(
                client, category, batch_size=settings.ai_batch_size,
                max_input_chars=settings.max_input_chars, max_attempts=settings.ai_max_attempts_per_article,
            )
            rep = processor.process(
                cat_pending, [{"id": e.id, "title": e.title} for e in recent_events if e.category == cid], usage, now
            )
            moved = apply_ai_relations(working, rep.relations)
            ai_report["processed"] += rep.processed
            ai_report["failed"] += rep.failed
            ai_report["calls"] += rep.calls
            ai_report["errors"] += rep.errors
            ai_report["events_merged"] = ai_report.get("events_merged", 0) + moved
            if rep.stopped_reason:
                ai_state = rep.stopped_reason
                if rep.stopped_reason in ("quota_exhausted", "fatal_error", "unavailable"):
                    break
        for a in working.values():
            if a.ai_processed and a.ai_processed_at == now:
                a.tags = compute_tags(a, filters)
    elif client is not None:
        ai_state = "idle"
    elif usage.quota_exhausted:
        ai_state = "quota_exhausted"
    if client is not None and hasattr(client, "exhausted_models"):
        usage.state["exhausted_models"] = sorted(client.exhausted_models)
    log.info("IA: estado=%s processadas=%d falhas=%d chamadas=%d (hoje: %d/%d)", ai_state,
             ai_report["processed"], ai_report["failed"], ai_report["calls"],
             usage.state["calls"], settings.max_ai_calls_per_day)

    # 3) Persistência dos artigos
    repo.save_articles(working.values())

    # 4) Eventos, ranking e edição
    events = build_events(working.values())
    for e in events:
        e.score = score_event(e, working, now)
    highlights = select_highlights(events, now, settings.highlights_per_edition)
    highlight_ids = {e.id for e in highlights}
    by_score = {e.id: e.score for e in events}

    window_cut = now - timedelta(hours=settings.latest_window_hours)
    visible = [a for a in working.values() if not a.off_topic]
    in_window = [a for a in visible if a.reference_time >= window_cut or a.event_id in highlight_ids]
    in_window.sort(key=lambda a: (a.event_id in highlight_ids, by_score.get(a.event_id, 0), a.relevance_score or 0), reverse=True)
    latest_articles = in_window[: settings.latest_max_articles]
    latest_event_ids = {a.event_id for a in latest_articles}
    latest_events = sorted((e for e in events if e.id in latest_event_ids), key=lambda e: e.score, reverse=True)

    edition_id, label = edition_identity(now)
    pending_after = sum(1 for a in visible if not a.ai_processed and a.reference_time >= pending_cut)
    edition = EditionInfo(
        id=edition_id, label=label, generated_at=now, highlight_count=len(highlights),
        article_count=len(latest_articles), ai_processed_count=sum(a.ai_processed for a in latest_articles),
        sources_ok=sources_ok, sources_failed=sources_failed, new_articles=len(new_articles),
    )
    primary_cat = next(iter(categories.values()))
    latest = LatestFeed(
        generated_at=now,
        is_sample=settings.dev_mode,
        edition=edition,
        category={"id": primary_cat["id"], "label": primary_cat["label"]},
        subcategories={s["id"]: s["label"] for c in categories.values() for s in c.get("subcategories", [])},
        filters=[{"id": f["id"], "label": f["label"]} for f in filters],
        highlights=[e.id for e in highlights],
        events=latest_events,
        articles=latest_articles,
        archive_days=repo.article_days()[-30:],
        ai_status={
            "state": ai_state, "model": getattr(client, "model", None) if client else None,
            "processed_this_run": ai_report["processed"], "pending": pending_after,
            "message": AI_MESSAGES.get(ai_state, ""),
        },
        notice=SAMPLE_NOTICE if settings.dev_mode else None,
    )
    latest_doc = latest.model_dump(mode="json")
    LatestFeed.model_validate(latest_doc)  # garante que o contrato do app continua válido
    repo.save_latest(latest_doc)
    repo.save_edition(edition_id, {
        "edition": edition.model_dump(mode="json"),
        "is_sample": settings.dev_mode,
        "highlights": [e.model_dump(mode="json") for e in highlights],
        "articles": [a.model_dump(mode="json") for a in working.values() if a.event_id in highlight_ids],
    })

    # 5) Tendências (sinais de recorrência)
    trends = build_trends(list(working.values()), now)
    repo.save_trends({
        "generated_at": now.isoformat().replace("+00:00", "Z"), "is_sample": settings.dev_mode, "window_days": 7,
        "disclaimer": "Sinais baseados em contagem de menções entre fontes. Não são previsões.",
        "trends": [t.model_dump(mode="json") for t in trends],
    }, now.date().isoformat())

    # 6) Retenção e estado
    removed = repo.apply_retention(
        now.date(), article_days=settings.retention_article_days,
        edition_days=settings.retention_edition_days, trend_days=settings.retention_trend_days,
    )
    dedup.prune(now.date(), keep_days=30)
    repo.save_state("http_cache", http_cache)
    repo.save_state("seen", dedup.seen)
    repo.save_state("ai_usage", usage.state)

    status = "failed" if sources_ok == 0 else "partial" if (sources_failed or ai_report["errors"]) else "ok"
    summary = {
        "started_at": now.isoformat(), "finished_at": utcnow().isoformat(), "status": status,
        "trigger": os.environ.get("GITHUB_EVENT_NAME", "local"), "edition_id": edition_id,
        "sources_ok": sources_ok, "sources_failed": sources_failed,
        "failed_sources": {k: v["error"] for k, v in source_report.items() if v["status"] == "error"},
        "new_articles": len(new_articles), "highlights": len(highlights), "events": len(latest_events),
        "ai": {"state": ai_state, **{k: v for k, v in ai_report.items() if k != "errors"},
               "errors": ai_report["errors"][:5], "calls_today": usage.state["calls"]},
        "retention_removed": len(removed),
    }
    runs = repo.load_state("runs", [])
    repo.save_state("runs", (runs + [summary])[-60:])
    if status != "failed":
        run_state["last_success"] = now.isoformat()
    run_state["last_run"] = now.isoformat()
    repo.save_state("run_state", run_state)
    log.info("Concluído: status=%s novos=%d destaques=%d eventos=%d", status, len(new_articles), len(highlights), len(latest_events))
    return RunResult(status, summary)
