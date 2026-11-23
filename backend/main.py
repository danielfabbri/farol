"""Ponto de entrada: python -m backend.main [--dev] [--mock-ai] [--no-ai] [--force]"""
from __future__ import annotations

import argparse
import logging
import os
import sys
from pathlib import Path

from backend.config.settings import REPO_ROOT, load_settings
from backend.pipeline import run


class RedactingFormatter(logging.Formatter):
    """Última barreira: substitui qualquer ocorrência da chave nos logs, inclusive tracebacks."""

    def __init__(self, fmt: str, datefmt: str, secrets: list[str]):
        super().__init__(fmt, datefmt)
        self.secrets = [s for s in secrets if s and len(s) >= 8]

    def format(self, record: logging.LogRecord) -> str:
        text = super().format(record)
        for s in self.secrets:
            text = text.replace(s, "***")
        return text


def _load_local_env() -> None:
    """Desenvolvimento local: lê .env (ignorado pelo Git) sem sobrescrever o ambiente."""
    path = Path(REPO_ROOT) / ".env"
    if not path.exists():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line and not line.startswith("#") and "=" in line:
            k, v = line.split("=", 1)
            os.environ.setdefault(k.strip(), v.strip().strip('"').strip("'"))


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(description="FAROL — coleta e análise de notícias")
    p.add_argument("--dev", action="store_true", help="dados fictícios (fixtures + IA simulada) em data-dev/")
    p.add_argument("--mock-ai", action="store_true", help="feeds reais com IA simulada (não consome cota)")
    p.add_argument("--no-ai", action="store_true", help="somente coleta, sem IA")
    p.add_argument("--force", action="store_true", help="ignora a proteção contra execuções próximas")
    p.add_argument("--log-level", default="INFO")
    args = p.parse_args(argv)
    if hasattr(sys.stdout, "reconfigure"):
        sys.stdout.reconfigure(encoding="utf-8")

    if not os.environ.get("GITHUB_ACTIONS"):
        _load_local_env()
    settings = load_settings(dev=args.dev, mock_ai=args.mock_ai, no_ai=args.no_ai)

    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(RedactingFormatter(
        "%(asctime)s %(levelname)-7s %(name)s: %(message)s", "%H:%M:%S", [settings.gemini_api_key or ""]))
    logging.basicConfig(level=args.log_level.upper(), handlers=[handler], force=True)
    log = logging.getLogger("farol")
    log.info("FAROL iniciando | dados=%s | modelo=%s | IA=%s | chave=%s",
             settings.data_dir.name, settings.gemini_model,
             "simulada" if settings.ai_mock else ("ativa" if settings.ai_enabled else "desativada"),
             "configurada" if settings.has_api_key else "ausente")

    force = args.force or os.environ.get("GITHUB_EVENT_NAME") == "workflow_dispatch"
    try:
        result = run(settings, force=force)
    except Exception as exc:  # noqa: BLE001
        log.exception("Falha inesperada: %s", type(exc).__name__)
        return 2
    return 1 if result.status == "failed" else 0


if __name__ == "__main__":
    sys.exit(main())
