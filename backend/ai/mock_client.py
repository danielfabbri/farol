"""Cliente simulado: respostas determinísticas, claramente marcadas como fictícias.

Usado nos testes automatizados e no modo de desenvolvimento (--dev / --mock-ai).
Nunca consome cota real.
"""
from __future__ import annotations

import json
import re

SAMPLE_MARK = "[FICTÍCIO]"


class MockGeminiClient:
    model = "mock"

    def __init__(self, *, fail_with: Exception | None = None, raw_response: dict | None = None):
        self.fail_with = fail_with
        self.raw_response = raw_response
        self.calls = 0
        self.prompts: list[str] = []

    def generate_json(self, system: str, prompt: str, schema: dict | None = None) -> dict:
        self.calls += 1
        self.prompts.append(prompt)
        if self.fail_with is not None:
            raise self.fail_with
        if self.raw_response is not None:
            return self.raw_response
        if "<<BRIEF>>" in prompt:
            items = json.loads(prompt[prompt.index("<<BRIEF>>") + 9:prompt.index("<</BRIEF>>")])
            return {
                "headline": f"{SAMPLE_MARK} Síntese simulada da seção",
                "overview": f"{SAMPLE_MARK} Parágrafo de abertura simulado com {len(items)} acontecimentos.",
                "points": [{"text": f"{SAMPLE_MARK} {it['title']}", "refs": [it["ref"]]} for it in items[:4]],
                "watch": None,
            }
        if "<<DIGEST>>" in prompt:
            payload = json.loads(prompt[prompt.index("<<DIGEST>>") + 10:prompt.index("<</DIGEST>>")])
            return {"items": [
                {"ref": it["ref"], "title_pt": f"{SAMPLE_MARK} Título traduzido de exemplo {it['ref']}",
                 "summary": f"{SAMPLE_MARK} Parágrafo simulado que explica a notícia de exemplo {it['ref']}, sem dados reais.",
                 "keywords": ["Tema Simulado", "notícia", f"exemplo {it['ref']}"]}
                for it in payload
            ]}
        if "<<TRANSLATE>>" in prompt:
            payload = json.loads(prompt[prompt.index("<<TRANSLATE>>") + 13:prompt.index("<</TRANSLATE>>")])
            return {"items": [
                {"ref": it["ref"], "title_pt": f"{SAMPLE_MARK} Título traduzido de exemplo {it['ref']}",
                 "excerpt_pt": f"{SAMPLE_MARK} Trecho traduzido de exemplo {it['ref']}" if it.get("excerpt") else ""}
                for it in payload
            ]}
        payload = json.loads(prompt[prompt.index("<<ARTICLES>>") + len("<<ARTICLES>>"):prompt.index("<</ARTICLES>>")])
        items = []
        for art in payload:
            title = art["title"]
            low = title.lower()
            sub = "ai_agents" if "agent" in low else "llms" if re.search(r"model|gpt|llm", low) else "products"
            ents = [e for e in ("Microsoft", "OpenAI", "Google", "Anthropic", "NVIDIA") if e.lower() in low]
            items.append({
                "ref": art["ref"],
                "is_on_topic": True,
                "category": "technology_ai",
                "subcategory": sub,
                "title_pt": f"{SAMPLE_MARK} Título analisado de exemplo {art['ref']}",
                "summary": f"{SAMPLE_MARK} Resumo simulado gerado sem IA real para: {title}.",
                "key_points": [f"{SAMPLE_MARK} Ponto simulado 1", f"{SAMPLE_MARK} Ponto simulado 2"],
                "why_it_matters": f"{SAMPLE_MARK} Texto simulado para testes da interface.",
                "potential_impacts": [f"{SAMPLE_MARK} Impacto hipotético simulado"],
                "uncertainties": ["Dados fictícios: nada aqui foi verificado."],
                "entities": ents,
                "technologies": ["AI Agents"] if sub == "ai_agents" else [],
                "relevance_score": 50 + (len(title) % 45),
                "novelty_score": 40 + (len(title) % 50),
                "business_potential": "medium",
                "career_potential": "high" if sub == "ai_agents" else "medium",
                "study_potential": "medium",
                "related_event_id": None,
            })
        return {"items": items}
