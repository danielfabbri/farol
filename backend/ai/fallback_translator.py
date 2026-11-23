"""Último recurso para manter os TÍTULOS em português quando todos os modelos Gemini falham.

Usa a API pública e gratuita do MyMemory (https://mymemory.translated.net/doc/spec.php):
sem chave, sem cadastro, sem cobrança; limite anônimo de ~5.000 caracteres/dia.
Envia somente manchetes públicas. Controla o próprio orçamento diário em data/state/ai_usage.json.
"""
from __future__ import annotations

import logging

import requests

from backend.collectors.http_client import USER_AGENT
from backend.collectors.normalization import clean_text, looks_corrupted
from backend.models.article import Article

log = logging.getLogger(__name__)
API = "https://api.mymemory.translated.net/get"


def translate_titles(
    articles: list[Article],
    session: requests.Session,
    usage_state: dict,
    *,
    daily_char_limit: int = 4500,
    run_char_limit: int = 1500,
) -> int:
    used_today = usage_state.get("fallback_chars", 0)
    used_run = 0
    done = 0
    for art in articles:
        title = art.original_title[:300]
        if used_today + len(title) > daily_char_limit or used_run + len(title) > run_char_limit:
            break
        try:
            resp = session.get(
                API, params={"q": title, "langpair": "en|pt-BR"},
                headers={"User-Agent": USER_AGENT}, timeout=15,
            )
            data = resp.json()
        except (requests.RequestException, ValueError) as exc:
            log.warning("Tradução de reserva indisponível: %s", type(exc).__name__)
            break
        used_today += len(title)
        used_run += len(title)
        text = clean_text((data.get("responseData") or {}).get("translatedText") or "")
        if data.get("quotaFinished") or "MYMEMORY WARNING" in text.upper() or data.get("responseStatus") == 429:
            log.warning("Cota diária da tradução de reserva atingida")
            used_today = daily_char_limit
            break
        if data.get("responseStatus") != 200 or not text or looks_corrupted(text) or text.lower() == title.lower():
            continue
        art.title_pt = text[:240]
        done += 1
    usage_state["fallback_chars"] = used_today
    return done
