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
from backend.ai.fallback_translator import translate_titles
from backend.ai.news_processor import AiUsage, NewsProcessor, summarize_articles, translate_articles
from backend.ai.section_briefer import generate_brief, signature, top_events
from backend.ai.gemini_client import FatalGeminiError, GeminiError, QuotaExceeded, ServiceUnavailable
from backend.models.edition import Section, SectionBrief
from backend.collectors.image_enricher import enrich_images
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
    cat_ex_re = {cid: keyword_pattern(c["exclude_keywords"]) for cid, c in categories.items() if c.get("exclude_keywords")}
    # Seções temáticas (ex.: Robótica, Espaço) puxam de outras seções as notícias cujo título trata do tema.
    claims = [
        (cid, keyword_pattern(c["claim_keywords"]), set(c.get("claim_from", [])))
        for cid, c in sorted(categories.items(), key=lambda kv: kv[1].get("order", 99))
        if c.get("claim_keywords")
    ]

    def claim(a: Article) -> None:
        for cid, rx, origins in claims:
            if a.category in origins and rx.search(a.original_title):
                a.category = cid
                return
    sources = [s for s in load_sources(settings.sources_path)
               if s.category in categories or any(c in categories for c in s.section_rules.values())]

    session = session or requests.Session()
    http_cache: dict = repo.load_state("http_cache", {})
    dedup = Deduplicator(repo.load_state("seen", {}))
    working: dict[str, Article] = repo.load_recent_articles(now.date(), settings.working_window_days)
    for a in working.values():
        claim(a)  # vale também para notícias coletadas antes de a seção existir

    # 1) Coleta
    new_articles: list[Article] = []
    source_report: dict[str, dict] = {}
    for src in sources:
        res = collect_source(
            src, session, cache=http_cache.get(src.id, {}), collected_at=now,
            max_age_days=settings.max_age_days, keyword_re=kw.get(src.category),
            exclude_re=keyword_pattern(src.exclude_keywords) if src.exclude_keywords else None,
            timeout=settings.http_timeout_seconds, is_sample=settings.dev_mode,
        )
        for a in res.articles:
            claim(a)
        fresh = [
            a for a in res.articles
            if a.category in categories and not (cat_ex_re.get(a.category) and cat_ex_re[a.category].search(a.original_title))
            and a.id not in working and not dedup.is_duplicate(a)
        ]
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

    # Imagens: notícias sem foto no feed recebem a imagem de destaque da própria página.
    if settings.image_enrichment_enabled and not settings.dev_mode:
        recent_no_image = sorted(
            (a for a in list(working.values()) + new_articles if not a.image_url and not a.image_checked),
            key=lambda a: a.reference_time, reverse=True,
        )
        enrich_images(recent_no_image, session, max_items=settings.max_image_lookups_per_run)

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
    if client is not None and pending and settings.analysis_enabled:
        recent_events = sorted(
            (e for e in build_events(working.values()) if e.last_seen >= pending_cut),
            key=lambda e: e.last_seen, reverse=True,
        )[: settings.ai_max_context_events]
        # Orçamento dividido entre as seções com pendências (nenhuma seção monopoliza a cota).
        cats_pending = [cid for cid in categories if any(a.category == cid for a in pending)]
        per_cat = max(1, settings.max_ai_calls_per_run // max(1, len(cats_pending)))
        for cid, category in categories.items():
            cat_pending = [a for a in pending if a.category == cid]
            if not cat_pending:
                continue
            usage.run_limit = min(settings.max_ai_calls_per_run, usage.run_calls + per_cat)
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
    elif client is not None:
        ai_state = "idle"
    elif usage.quota_exhausted:
        ai_state = "quota_exhausted"
    # 2b) Tradução leve: tudo em português, mesmo sem a análise completa.
    translated, tr_stop = 0, None
    untranslated = sorted(
        (a for a in working.values()
         if not a.ai_processed and not a.title_pt and not a.off_topic
         and a.language != "pt" and a.reference_time >= pending_cut),
        key=lambda a: (a.source_priority, a.reference_time), reverse=True,
    )
    # Resumo curto (um parágrafo) das notícias da janela publicada, mais recentes primeiro.
    digest_cut = now - timedelta(hours=settings.latest_window_hours)
    digest_todo = sorted(
        (a for a in working.values()
         if settings.digest_enabled and not a.summary and not a.off_topic and a.digest_attempts < 2
         and len(a.excerpt or "") >= settings.digest_min_excerpt_chars and a.reference_time >= digest_cut),
        key=lambda a: a.reference_time, reverse=True,
    )
    # Só as que vão aparecer no app: as mais recentes de cada seção (mesmo limite da edição publicada).
    per_section: dict[str, int] = {}
    shown_first = []
    for a in digest_todo:
        per_section[a.category] = per_section.get(a.category, 0) + 1
        if per_section[a.category] <= settings.latest_max_articles_per_section:
            shown_first.append(a)
    digest_todo = shown_first
    translator = None
    if (untranslated or digest_todo) and client is not None:
        if ai_client is not None or settings.ai_mock:
            translator = client
        elif settings.has_api_key:
            translator = GeminiClient(
                settings.gemini_api_key or "", settings.translation_models[0],
                fallback_models=list(settings.translation_models[1:]),
                exhausted_models=set(usage.state.get("exhausted_models", [])) | getattr(client, "exhausted_models", set()),
                min_interval=settings.ai_min_interval_seconds,
            )
    digested, dg_stop = 0, None
    if translator is not None and digest_todo:
        digested, dg_stop = summarize_articles(
            translator, digest_todo, usage, batch_size=settings.digest_batch_size,
            daily_limit=settings.max_digest_calls_per_day, run_limit=settings.max_digest_calls_per_run,
        )
        log.info("Resumos curtos: %d de %d%s", digested, len(digest_todo), f" (parou: {dg_stop})" if dg_stop else "")
        untranslated = [a for a in untranslated if not a.title_pt]
    ai_report["digested"] = digested
    if translator is not None and untranslated and dg_stop != "quota_exhausted":
        translated, tr_stop = translate_articles(
            translator, untranslated, usage, batch_size=settings.translation_batch_size,
            daily_limit=settings.max_translation_calls_per_day, run_limit=settings.max_translation_calls_per_run,
        )
    # 2c) Último recurso: só os títulos, via serviço gratuito, quando a Gemini não deu conta.
    still_english = [a for a in untranslated if not a.title_pt]
    if still_english and settings.title_fallback_enabled and not settings.ai_mock and not settings.dev_mode:
        still_english.sort(key=lambda a: (a.source_type != "research", a.source_priority, a.reference_time), reverse=True)
        fb = translate_titles(still_english, session, usage.state)
        translated += fb
        if fb:
            log.info("Tradução de reserva (somente títulos): %d", fb)
    exhausted_now = set(usage.state.get("exhausted_models", []))
    for c in (client, translator):
        exhausted_now |= getattr(c, "exhausted_models", set()) if c is not None else set()
    usage.state["exhausted_models"] = sorted(exhausted_now)
    ai_report["translated"] = translated
    log.info("IA: estado=%s processadas=%d falhas=%d chamadas=%d (hoje: %d/%d) | traduzidas=%d%s", ai_state,
             ai_report["processed"], ai_report["failed"], ai_report["calls"],
             usage.state["calls"], settings.max_ai_calls_per_day, translated,
             f" (tradução parou: {tr_stop})" if tr_stop else "")

    # Categorias recalculadas para todos (vale também para filtros recém-criados).
    for a in working.values():
        a.tags = compute_tags(a, filters)

    # 3) Persistência dos artigos
    repo.save_articles(working.values())

    # 4) Eventos, ranking e edição
    usage.run_limit = settings.max_ai_calls_per_run
    events = build_events(working.values())
    for e in events:
        e.score = score_event(e, working, now)
    by_score = {e.id: e.score for e in events}
    ordered_cats = sorted(categories.values(), key=lambda c: c.get("order", 99))

    # Destaques por seção e capa (o melhor de cada seção primeiro, depois por importância).
    section_highlights = {
        c["id"]: select_highlights([e for e in events if e.category == c["id"]], now, settings.section_highlights)
        for c in ordered_cats
    }
    front: list = [hl[0] for c in ordered_cats if (hl := section_highlights[c["id"]])]
    rest = sorted((e for hl in section_highlights.values() for e in hl[1:]), key=lambda e: e.score, reverse=True)
    highlights = (front + rest)[: settings.highlights_per_edition]
    highlight_ids = {e.id for e in highlights} | {e.id for hl in section_highlights.values() for e in hl}

    # Janela da edição: limite por seção, para nenhuma seção engolir as outras.
    window_cut = now - timedelta(hours=settings.latest_window_hours)
    visible = [a for a in working.values() if not a.off_topic]
    latest_articles = []
    for c in ordered_cats:
        in_window = [a for a in visible if a.category == c["id"] and (a.reference_time >= window_cut or a.event_id in highlight_ids)]
        in_window.sort(key=lambda a: (a.event_id in highlight_ids, by_score.get(a.event_id, 0), a.relevance_score or 0), reverse=True)
        latest_articles += in_window[: settings.latest_max_articles_per_section]
    latest_event_ids = {a.event_id for a in latest_articles}
    latest_events = sorted((e for e in events if e.id in latest_event_ids), key=lambda e: e.score, reverse=True)
    articles_by_id = {a.id: a for a in latest_articles}

    # Resumo de cada seção (reaproveitado se os principais acontecimentos não mudaram).
    edition_id, label = edition_identity(now)
    briefs_path = repo.root / "briefs" / "latest.json"
    briefs_store: dict = repo.read_json(briefs_path, {})
    brief_client = client if (client is not None and settings.briefs_enabled) else None
    briefs_made = 0
    for c in ordered_cats:
        sec_events = [e for e in latest_events if e.category == c["id"]]
        if len(sec_events) < 2:
            continue
        sig = signature(top_events(sec_events))
        prev = briefs_store.get(c["id"])
        if prev and prev.get("signature") == sig:
            continue
        made = False
        if brief_client is not None and usage.can_spend("brief_calls", settings.max_brief_calls_per_day, settings.max_brief_calls_per_run):
            try:
                brief = generate_brief(brief_client, c["label"], label, sec_events, articles_by_id, now)
                usage.spend("brief_calls")
                briefs_store[c["id"]] = brief.model_dump(mode="json")
                briefs_made += 1
                made = True
            except GeminiError as exc:
                usage.spend("brief_calls")
                log.warning("Resumo da seção %s indisponível: %s", c["label"], exc)
                if isinstance(exc, (ServiceUnavailable, QuotaExceeded, FatalGeminiError)):
                    brief_client = None  # serviço fora: não insiste nas outras seções nesta execução
        if not made and prev:
            prev["stale"] = True  # mantém o anterior, sinalizando que a seção mudou desde então
    for sid in list(briefs_store):
        gen = briefs_store[sid].get("generated_at", "")
        if gen and datetime.fromisoformat(gen.replace("Z", "+00:00")) < now - timedelta(hours=36):
            del briefs_store[sid]  # resumo velho demais não é exibido
    repo.write_json(briefs_path, briefs_store)
    ai_report["briefs"] = briefs_made

    section_filters = {cid: [{"id": f["id"], "label": f["label"]} for f in filters if f.get("category") == cid] for cid in categories}
    sections = [
        Section(
            id=c["id"], label=c["label"], order=c.get("order", 99),
            highlights=[e.id for e in section_highlights[c["id"]]],
            filters=section_filters.get(c["id"], []),
            article_count=sum(1 for a in latest_articles if a.category == c["id"]),
            brief=(SectionBrief.model_validate(briefs_store[c["id"]])
                   if settings.briefs_enabled and c["id"] in briefs_store else None),
        )
        for c in ordered_cats
    ]

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
            "translated_this_run": translated,
            "message": AI_MESSAGES.get(ai_state, "") + (
                " Enquanto isso, títulos e trechos foram traduzidos automaticamente."
                if translated and ai_state not in ("ok", "idle") else ""),
        },
        notice=SAMPLE_NOTICE if settings.dev_mode else None,
        sections=sections,
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
