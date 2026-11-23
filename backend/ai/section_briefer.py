"""Resumo da seção inteira ("o que você precisa saber hoje em Política", por exemplo).

A IA recebe os principais acontecimentos da seção (título, resumo ou trecho, fontes) e devolve:
uma manchete, um parágrafo de abertura, 3–6 tópicos (cada um ligado aos acontecimentos de origem)
e, opcionalmente, o que acompanhar. O material de entrada é o mesmo para a Gemini e para o
modo manual (ver backend/briefing_cli.py), então qualquer modelo segue as mesmas regras.
"""
from __future__ import annotations

import hashlib
import json
from datetime import datetime

from pydantic import ValidationError

from backend.ai.gemini_client import GeminiError, JsonGenerator
from backend.collectors.normalization import truncate
from backend.models.analysis import BriefOutput
from backend.models.article import Article
from backend.models.edition import BriefPoint, Event, SectionBrief

BRIEF_SYSTEM_PROMPT = """Você é o editor-chefe do FAROL e escreve o RESUMO DA SEÇÃO para quem tem poucos minutos.
Escreva em português do Brasil, tom jornalístico, objetivo, sem sensacionalismo e sem opinião.
Escreva os acentos diretamente (á, ç, ã...), nunca como códigos.

REGRAS:
1. Use SOMENTE o material fornecido (títulos, resumos, trechos). Não acrescente fatos, números, nomes ou datas.
2. "headline": uma frase-síntese do momento da seção (até 120 caracteres).
3. "overview": 2 a 3 frases conectando os fatos mais importantes.
4. "points": 3 a 6 tópicos, do mais ao menos importante. Cada tópico é um fato, com atribuição quando for declaração
   ("segundo o governo..."), e lista em "refs" os códigos dos acontecimentos usados (ex.: ["e1", "e3"]). Agrupe no mesmo tópico
   acontecimentos que tratam do mesmo assunto.
5. "watch": opcional, o que acompanhar nos próximos dias, apenas se decorrer claramente do material e marcado como
   expectativa ("deve", "está previsto"). Use null se não houver.
6. Acontecimentos cobertos por várias fontes ("sources" > 1) costumam ser mais relevantes.

Responda apenas com JSON: {"headline": ..., "overview": ..., "points": [{"text": ..., "refs": [...]}], "watch": ...}"""

BRIEF_SCHEMA = {
    "type": "object",
    "properties": {
        "headline": {"type": "string"},
        "overview": {"type": "string"},
        "points": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {"text": {"type": "string"}, "refs": {"type": "array", "items": {"type": "string"}}},
                "required": ["text", "refs"],
            },
        },
        "watch": {"type": ["string", "null"]},
    },
    "required": ["headline", "overview", "points", "watch"],
}


def top_events(events: list[Event], limit: int = 10) -> list[Event]:
    return sorted(events, key=lambda e: e.score, reverse=True)[:limit]


def signature(events: list[Event]) -> str:
    """Muda quando o conjunto dos principais acontecimentos muda (evita gastar cota à toa)."""
    return hashlib.sha1("|".join(sorted(e.id for e in events)).encode()).hexdigest()[:12]


def brief_items(events: list[Event], articles: dict[str, Article]) -> tuple[list[dict], dict[str, str]]:
    items, refmap = [], {}
    for i, e in enumerate(events, start=1):
        ref = f"e{i}"
        refmap[ref] = e.id
        arts = [articles[a] for a in e.article_ids if a in articles]
        lead = next((a for a in arts if a.ai_processed), None) or next((a for a in arts if a.title_pt), None) or (arts[0] if arts else None)
        text = (lead.summary or lead.excerpt_pt or lead.excerpt) if lead else e.summary
        items.append({
            "ref": ref,
            "title": lead.display_title if lead else e.title,
            "text": truncate(text or "", 450),
            "sources": e.source_count,
            "source_names": sorted({s.source_name for s in e.sources})[:4],
            "published_at": e.last_seen.isoformat(),
        })
    return items, refmap


def brief_prompt(section_label: str, edition_label: str, items: list[dict]) -> str:
    return (
        f"Seção: {section_label}\nEdição: {edition_label}\n"
        "Principais acontecimentos da seção (do mais ao menos relevante pelo ranking do FAROL):\n"
        f"<<BRIEF>>{json.dumps(items, ensure_ascii=False)}<</BRIEF>>"
    )


def to_brief(out: BriefOutput, refmap: dict[str, str], *, now: datetime, model: str | None,
             author: str, sig: str) -> SectionBrief:
    points = []
    for p in out.points:
        ids = list(dict.fromkeys(refmap[r] for r in p.refs if r in refmap))
        if ids:  # tópico sem origem verificável é descartado
            points.append(BriefPoint(text=p.text.strip(), event_ids=ids))
    if not points:
        raise ValueError("nenhum tópico do resumo aponta para acontecimentos da seção")
    return SectionBrief(
        headline=out.headline.strip(), overview=out.overview.strip(), points=points,
        watch=(out.watch or "").strip() or None, generated_at=now, model=model, author=author,
        signature=sig, event_ids=list(refmap.values()),
    )


def generate_brief(client: JsonGenerator, section_label: str, edition_label: str, events: list[Event],
                   articles: dict[str, Article], now: datetime) -> SectionBrief:
    top = top_events(events)
    items, refmap = brief_items(top, articles)
    data = client.generate_json(BRIEF_SYSTEM_PROMPT, brief_prompt(section_label, edition_label, items), BRIEF_SCHEMA)
    try:
        out = BriefOutput.model_validate(data)
        return to_brief(out, refmap, now=now, model=getattr(client, "model", None), author="IA", sig=signature(top))
    except (ValidationError, ValueError) as exc:
        raise GeminiError(f"resumo inválido: {type(exc).__name__}") from None
