"""Configuração do FAROL a partir de variáveis de ambiente.

Nenhum segredo é lido de arquivo versionado. GEMINI_API_KEY vem do ambiente
(GitHub Actions Secret em produção; .env local ignorado pelo Git em desenvolvimento).
"""
from __future__ import annotations

import os
from dataclasses import dataclass, field, replace
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
CONFIG_DIR = Path(__file__).resolve().parent
FIXTURES_DIR = REPO_ROOT / "backend" / "tests" / "fixtures"

DEFAULT_MODEL = "gemini-flash-latest"
# Somente modelos Flash do plano gratuito. Usados apenas se o principal estiver sobrecarregado
# (HTTP 503) ou descontinuado (HTTP 404) — nunca quando a cota acaba.
DEFAULT_FALLBACKS = ("gemini-3.5-flash", "gemini-3.5-flash-lite", "gemini-flash-lite-latest")


def _int(name: str, default: int) -> int:
    raw = os.environ.get(name, "").strip()
    try:
        return int(raw) if raw else default
    except ValueError:
        return default


def _float(name: str, default: float) -> float:
    raw = os.environ.get(name, "").strip()
    try:
        return float(raw) if raw else default
    except ValueError:
        return default


def _bool(name: str, default: bool) -> bool:
    raw = os.environ.get(name, "").strip().lower()
    if not raw:
        return default
    return raw in {"1", "true", "yes", "sim", "on"}


@dataclass(frozen=True)
class Settings:
    data_dir: Path
    sources_path: Path
    categories_path: Path
    # repr=False: a chave nunca aparece em logs ou mensagens de erro.
    gemini_api_key: str | None = field(default=None, repr=False)
    gemini_model: str = DEFAULT_MODEL
    gemini_fallback_models: tuple[str, ...] = DEFAULT_FALLBACKS
    ai_enabled: bool = True
    ai_mock: bool = False
    dev_mode: bool = False
    # Controle de cota da IA
    max_ai_calls_per_run: int = 5
    max_ai_calls_per_day: int = 20
    # Lotes e contexto pequenos: o plano gratuito recusa (HTTP 503) prompts grandes sob carga.
    ai_batch_size: int = 6
    ai_max_context_events: int = 15
    ai_min_interval_seconds: float = 13.0
    ai_max_attempts_per_article: int = 3
    max_input_chars: int = 700
    # Coleta
    max_age_days: int = 4
    http_timeout_seconds: int = 20
    # Edição e publicação
    highlights_per_edition: int = 7
    latest_max_articles: int = 60
    latest_window_hours: int = 48
    # Retenção
    retention_article_days: int = 120
    retention_edition_days: int = 21
    retention_trend_days: int = 120
    working_window_days: int = 8
    # Proteção contra execuções duplicadas
    min_minutes_between_runs: int = 30

    @property
    def has_api_key(self) -> bool:
        return bool(self.gemini_api_key)


def load_settings(*, dev: bool = False, mock_ai: bool = False, no_ai: bool = False) -> Settings:
    dev = dev or _bool("FAROL_DEV", False)
    data_dir = Path(os.environ.get("FAROL_DATA_DIR") or (REPO_ROOT / ("data-dev" if dev else "data")))
    sources_path = FIXTURES_DIR / "dev_sources.json" if dev else CONFIG_DIR / "sources.json"
    model = (os.environ.get("GEMINI_MODEL") or DEFAULT_MODEL).strip().removeprefix("models/")
    settings = Settings(
        data_dir=data_dir,
        sources_path=Path(os.environ.get("FAROL_SOURCES_PATH") or sources_path),
        categories_path=CONFIG_DIR / "categories.json",
        gemini_api_key=(os.environ.get("GEMINI_API_KEY") or "").strip() or None,
        gemini_model=model,
        gemini_fallback_models=tuple(
            m.strip() for m in (os.environ.get("GEMINI_FALLBACK_MODELS") or ",".join(DEFAULT_FALLBACKS)).split(",")
            if m.strip()
        ),
        ai_enabled=_bool("FAROL_AI_ENABLED", True) and not no_ai,
        ai_mock=mock_ai or dev or _bool("FAROL_AI_MOCK", False),
        dev_mode=dev,
        max_ai_calls_per_run=_int("FAROL_MAX_AI_CALLS_PER_RUN", 5),
        max_ai_calls_per_day=_int("FAROL_MAX_AI_CALLS_PER_DAY", 20),
        ai_batch_size=max(1, _int("FAROL_AI_BATCH_SIZE", 6)),
        ai_max_context_events=_int("FAROL_AI_MAX_CONTEXT_EVENTS", 15),
        ai_min_interval_seconds=_float("FAROL_AI_MIN_INTERVAL_SECONDS", 13.0),
        max_input_chars=_int("FAROL_MAX_INPUT_CHARS", 700),
        max_age_days=_int("FAROL_MAX_AGE_DAYS", 4),
        highlights_per_edition=_int("FAROL_HIGHLIGHTS", 7),
        latest_max_articles=_int("FAROL_LATEST_MAX_ARTICLES", 60),
        min_minutes_between_runs=_int("FAROL_MIN_MINUTES_BETWEEN_RUNS", 30),
    )
    if dev:
        # Dados fictícios nunca precisam de cota nem de proteção de intervalo.
        settings = replace(settings, gemini_api_key=None, min_minutes_between_runs=0)
    return settings
