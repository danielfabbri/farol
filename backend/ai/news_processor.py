"""Classificação, resumo e relação entre notícias em UMA chamada por lote.

Responde às responsabilidades de classificação, resumo, relevância e relação com eventos
anteriores sem multiplicar chamadas — essencial para caber na cota gratuita da Gemini.
"""
from __future__ import annotations

import logging
from dataclasses import dataclass, field
from datetime import datetime
from zoneinfo import ZoneInfo

from pydantic import ValidationError

from backend.ai.gemini_client import (
    FatalGeminiError, GeminiError, JsonGenerator, QuotaExceeded, SchemaRejected, ServiceUnavailable,
)
from backend.ai.prompts import build_batch_prompt, response_schema, system_prompt
from backend.collectors.normalization import is_bad_translation, looks_english, truncate
from backend.models.analysis import ArticleAnalysis
from backend.models.article import Article

log = logging.getLogger(__name__)
QUOTA_TZ = ZoneInfo("America/Los_Angeles")  # a cota diária da Gemini reinicia à meia-noite do Pacífico


class AiUsage:
    """Orçamento de chamadas por execução e por dia, persistido em data/state/ai_usage.json."""

    def __init__(self, state: dict | None, now: datetime, *, daily_limit: int, run_limit: int):
        day = now.astimezone(QUOTA_TZ).date().isoformat()
        state = dict(state or {})
        if state.get("day") != day:
            state = {"day": day, "calls": 0, "quota_exhausted": False}
        self.state = state
        self.daily_limit = daily_limit
        self.run_limit = run_limit
        self.run_calls = 0

    @property
    def quota_exhausted(self) -> bool:
        return bool(self.state.get("quota_exhausted"))

    def can_call(self) -> bool:
        return (
            not self.quota_exhausted
            and self.state["calls"] < self.daily_limit
            and self.run_calls < self.run_limit
        )

    def record(self, n: int = 1) -> None:
        self.state["calls"] += n
        self.run_calls += n

    def mark_exhausted(self) -> None:
        self.state["quota_exhausted"] = True

    # Orçamentos nomeados adicionais (ex.: "brief_calls" para os resumos de seção).
    def can_spend(self, key: str, daily_limit: int, run_limit: int) -> bool:
        runs = getattr(self, "_spent_run", {})
        return (not self.quota_exhausted and self.state.get(key, 0) < daily_limit and runs.get(key, 0) < run_limit)

    def spend(self, key: str, n: int = 1) -> None:
        self.state[key] = self.state.get(key, 0) + n
        runs = getattr(self, "_spent_run", {})
        runs[key] = runs.get(key, 0) + n
        self._spent_run = runs

    # Orçamento separado para a tradução leve (usa modelos "lite", com cota própria).
    def can_translate(self, daily_limit: int, run_limit: int) -> bool:
        return self.state.get("translation_calls", 0) < daily_limit and getattr(self, "_tr_run", 0) < run_limit

    def record_translation(self, n: int = 1) -> None:
        self.state["translation_calls"] = self.state.get("translation_calls", 0) + n
        self._tr_run = getattr(self, "_tr_run", 0) + n


def translate_articles(
    client: JsonGenerator,
    articles: list[Article],
    usage: AiUsage,
    *,
    batch_size: int,
    daily_limit: int,
    run_limit: int,
    excerpt_chars: int = 400,
) -> tuple[int, str | None]:
    """Garante título e trecho em português quando a análise completa não foi possível.

    Retorna (traduzidas, motivo_de_parada).
    """
    from backend.ai.prompts import TRANSLATE_SCHEMA, TRANSLATE_SYSTEM_PROMPT, build_translate_prompt
    from backend.models.analysis import Translation

    done = 0
    for start in range(0, len(articles), batch_size):
        if not usage.can_translate(daily_limit, run_limit):
            return done, "budget_reached"
        batch = articles[start:start + batch_size]
        refs = {f"t{i + 1}": a for i, a in enumerate(batch)}
        items = [{"ref": r, "title": a.original_title, "excerpt": truncate(a.excerpt, excerpt_chars)} for r, a in refs.items()]
        before = getattr(client, "calls_made", None)
        try:
            data = client.generate_json(TRANSLATE_SYSTEM_PROMPT, build_translate_prompt(items), TRANSLATE_SCHEMA)
        except QuotaExceeded:
            usage.record_translation(1)
            return done, "quota_exhausted"
        except GeminiError as exc:
            after = getattr(client, "calls_made", None)
            usage.record_translation(max(0, after - before) if before is not None and after is not None else 1)
            log.warning("Tradução falhou: %s", exc)
            return done, "unavailable" if isinstance(exc, ServiceUnavailable) else "error"
        after = getattr(client, "calls_made", None)
        usage.record_translation(max(1, after - before) if before is not None and after is not None else 1)
        for raw in (data.get("items") if isinstance(data, dict) else None) or []:
            if not isinstance(raw, dict) or raw.get("ref") not in refs:
                continue
            try:
                tr = Translation.model_validate(raw)
            except ValidationError:
                continue
            art = refs[tr.ref]
            if is_bad_translation(art.original_title, tr.title_pt):
                continue  # devolveu o título em inglês: fica para a próxima tentativa
            if not art.ai_processed:  # nunca sobrescreve a análise completa
                art.title_pt = tr.title_pt.strip()
                art.excerpt_pt = tr.excerpt_pt.strip() or None
                done += 1
    return done, None


def summarize_articles(
    client: JsonGenerator,
    articles: list[Article],
    usage: AiUsage,
    *,
    batch_size: int,
    daily_limit: int,
    run_limit: int,
    excerpt_chars: int = 700,
) -> tuple[int, str | None]:
    """Um parágrafo curto explicando cada notícia (e o título em português). Retorna (resumidas, motivo_de_parada)."""
    from backend.ai.prompts import DIGEST_SCHEMA, DIGEST_SYSTEM_PROMPT, build_digest_prompt
    from backend.models.analysis import Digest

    done, run_calls = 0, 0
    for start in range(0, len(articles), batch_size):
        if usage.state.get("digest_calls", 0) >= daily_limit or run_calls >= run_limit:
            return done, "budget_reached"
        batch = articles[start:start + batch_size]
        refs = {f"d{i + 1}": a for i, a in enumerate(batch)}
        items = [{"ref": r, "lang": a.language, "title": a.original_title, "excerpt": truncate(a.excerpt, excerpt_chars)}
                 for r, a in refs.items()]
        before = getattr(client, "calls_made", None)
        try:
            data = client.generate_json(DIGEST_SYSTEM_PROMPT, build_digest_prompt(items), DIGEST_SCHEMA)
        except GeminiError as exc:
            after = getattr(client, "calls_made", None)
            n = max(0, after - before) if before is not None and after is not None else 1
            usage.state["digest_calls"] = usage.state.get("digest_calls", 0) + n
            log.warning("Resumo curto falhou: %s", exc)
            if isinstance(exc, QuotaExceeded):
                return done, "quota_exhausted"
            return done, "unavailable" if isinstance(exc, ServiceUnavailable) else "error"
        after = getattr(client, "calls_made", None)
        n = max(1, after - before) if before is not None and after is not None else 1
        usage.state["digest_calls"] = usage.state.get("digest_calls", 0) + n
        run_calls += n
        for a in batch:
            a.digest_attempts += 1
        for raw in (data.get("items") if isinstance(data, dict) else None) or []:
            if not isinstance(raw, dict) or raw.get("ref") not in refs:
                continue
            try:
                dg = Digest.model_validate(raw)
            except ValidationError:
                continue
            art = refs[dg.ref]
            if art.ai_processed:
                continue  # nunca sobrescreve a análise completa
            if looks_english(dg.summary):
                continue  # resumo tem de vir em português
            art.summary = dg.summary.strip()
            if art.language != "pt" and not art.title_pt and not is_bad_translation(art.original_title, dg.title_pt):
                art.title_pt = dg.title_pt.strip()
            done += 1
    return done, None


@dataclass
class ProcessReport:
    processed: int = 0
    failed: int = 0
    batches: int = 0
    calls: int = 0
    stopped_reason: str | None = None
    errors: list[str] = field(default_factory=list)
    relations: dict[str, str] = field(default_factory=dict)


class NewsProcessor:
    def __init__(self, client: JsonGenerator, category: dict, *, batch_size: int, max_input_chars: int, max_attempts: int):
        self.client = client
        self.category = category
        self.batch_size = batch_size
        self.max_input_chars = max_input_chars
        self.max_attempts = max_attempts
        self.sub_ids = [s["id"] for s in category.get("subcategories", [])] or ["other"]
        self._use_schema = True

    def _input(self, ref: str, a: Article) -> dict:
        return {
            "ref": ref,
            "source": a.source_name,
            "source_type": a.source_type,
            "published_at": a.published_at.isoformat() if a.published_at else None,
            "language": a.language,
            "title": truncate(a.original_title, 300),
            "excerpt": truncate(a.excerpt, self.max_input_chars),
        }

    def _call(self, prompt: str) -> tuple[dict, int]:
        before = getattr(self.client, "calls_made", None)
        try:
            schema = response_schema(self.sub_ids) if self._use_schema else None
            try:
                data = self.client.generate_json(system_prompt(self.category), prompt, schema)
            except SchemaRejected:
                log.warning("Schema recusado pela API; repetindo com validação apenas local")
                self._use_schema = False
                data = self.client.generate_json(system_prompt(self.category), prompt, None)
            return data, self._calls_since(before)
        except GeminiError as exc:
            exc.calls_used = self._calls_since(before)  # type: ignore[attr-defined]
            raise

    def _calls_since(self, before: int | None) -> int:
        after = getattr(self.client, "calls_made", None)
        return max(0, after - before) if before is not None and after is not None else 1

    def apply(self, article: Article, analysis: ArticleAnalysis, now: datetime) -> None:
        article.title_pt = analysis.title_pt.strip()
        article.summary = analysis.summary.strip()
        article.key_points = analysis.key_points
        article.why_it_matters = analysis.why_it_matters.strip() or None
        article.potential_impacts = analysis.potential_impacts
        article.uncertainties = analysis.uncertainties
        article.entities = analysis.entities
        article.technologies = analysis.technologies
        article.subcategory = analysis.subcategory if analysis.subcategory in self.sub_ids else "other"
        article.relevance_score = analysis.relevance_score
        article.novelty_score = analysis.novelty_score
        article.business_potential = analysis.business_potential
        article.career_potential = analysis.career_potential
        article.study_potential = analysis.study_potential
        article.off_topic = not analysis.is_on_topic
        article.ai_processed = True
        article.ai_model = self.client.model
        article.ai_processed_at = now

    def process(self, pending: list[Article], recent_events: list[dict], usage: AiUsage, now: datetime) -> ProcessReport:
        report = ProcessReport()
        queue = [a for a in pending if not a.ai_processed and a.ai_attempts < self.max_attempts]
        for start in range(0, len(queue), self.batch_size):
            if not usage.can_call():
                report.stopped_reason = "quota_exhausted" if usage.quota_exhausted else "budget_reached"
                break
            batch = queue[start:start + self.batch_size]
            refs = {f"a{i + 1}": a for i, a in enumerate(batch)}
            prompt = build_batch_prompt([self._input(r, a) for r, a in refs.items()], recent_events, self.category)
            report.batches += 1
            try:
                data, used = self._call(prompt)
                usage.record(used)
                report.calls += used
            except QuotaExceeded as exc:
                used = getattr(exc, "calls_used", 1)
                usage.record(used)
                report.calls += used
                if exc.daily:
                    usage.mark_exhausted()  # bloqueia novas chamadas até a cota diária reiniciar
                report.stopped_reason = "quota_exhausted"
                report.errors.append(str(exc))
                log.warning("Cota da Gemini atingida (%s): chamadas interrompidas; coleta segue sem IA.",
                            "diária" if exc.daily else "por minuto")
                break
            except ServiceUnavailable as exc:
                # Sobrecarga transitória: não penaliza os artigos; ficam para a próxima edição.
                used = getattr(exc, "calls_used", 0)
                usage.record(used)
                report.calls += used
                report.stopped_reason = "unavailable"
                report.errors.append(str(exc))
                log.warning("Gemini sobrecarregada; artigos ficam pendentes para a próxima edição.")
                break
            except FatalGeminiError as exc:
                usage.record(getattr(exc, "calls_used", 1))
                report.stopped_reason = "fatal_error"
                report.errors.append(str(exc))
                log.error("IA desativada nesta execução: %s", exc)
                break
            except GeminiError as exc:
                used = getattr(exc, "calls_used", 1)
                usage.record(used)
                report.calls += used
                report.errors.append(str(exc))
                for a in batch:
                    a.ai_attempts += 1
                report.failed += len(batch)
                log.warning("Lote falhou (%s); artigos ficam pendentes", exc)
                continue

            got: set[str] = set()
            for raw in (data.get("items") if isinstance(data, dict) else None) or []:
                ref = str(raw.get("ref", "")) if isinstance(raw, dict) else ""
                article = refs.get(ref)
                if article is None or ref in got:
                    continue
                try:
                    analysis = ArticleAnalysis.model_validate(raw)
                except ValidationError as exc:
                    log.warning("Resposta inválida para %s: %d erro(s) de validação", article.id, exc.error_count())
                    continue
                self.apply(article, analysis, now)
                got.add(ref)
                if analysis.related_event_id:
                    report.relations[article.id] = analysis.related_event_id
            for ref, a in refs.items():
                if ref in got:
                    report.processed += 1
                else:
                    a.ai_attempts += 1
                    report.failed += 1
        return report
