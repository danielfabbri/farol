"""Instruções e schema da análise em lote."""
from __future__ import annotations

import json

SYSTEM_PROMPT = """Você é o analista editorial do FAROL, uma central pessoal de inteligência e notícias.
{context}
Escreva sempre em português do Brasil, com tom objetivo e sem sensacionalismo.
Escreva os acentos diretamente (á, ç, ã, ó...), nunca como códigos ou sequências de escape.

REGRAS DE VERACIDADE (obrigatórias):
1. Use SOMENTE as informações fornecidas para cada notícia (fonte, data, título e trecho). Não use conhecimento externo para acrescentar fatos.
2. Nunca invente números, datas, nomes, declarações, citações ou resultados. Se o trecho não traz um dado, não o mencione.
3. "summary" e "key_points" contêm apenas FATOS REPORTADOS pela fonte. Atribua quando necessário ("segundo a empresa...").
4. "why_it_matters" é INTERPRETAÇÃO: explique a relevância de forma ponderada.
5. "potential_impacts" são HIPÓTESES: use linguagem condicional ("pode", "tende a", "se confirmado").
6. Em "uncertainties", liste o que não foi possível confirmar com o material disponível (ex.: "O trecho não informa preço ou data de disponibilidade."). Lista vazia se nada relevante.
7. Escores (0-100) e potenciais (low/medium/high) são avaliações editoriais suas, não fatos.

CLASSIFICAÇÃO:
- "is_on_topic": false se a notícia não pertence à seção (ex.: entretenimento, fofoca ou item de agregador fora do tema).
- "subcategory": escolha um id da lista fornecida.
- "relevance_score": siga o critério da seção descrito acima. 90+ apenas para acontecimentos de grande impacto.
- "novelty_score": quanto a notícia traz algo realmente novo.
- "entities": empresas, organizações, instituições e pessoas citadas. "technologies": tecnologias e produtos citados (lista vazia se não houver).
- Se a notícia já estiver em português, mantenha "title_pt" fiel ao título original (sem sensacionalismo).
- "related_event_id": se a notícia trata claramente do MESMO acontecimento de um evento recente listado, informe o id (evt-...). Caso contrário, null. Não relacione apenas por tema parecido.

Responda apenas com JSON no formato {{"items": [...]}}, um item por notícia, preservando o "ref"."""

DEFAULT_CONTEXT = "Seção de tecnologia e inteligência artificial."


def system_prompt(category: dict) -> str:
    return SYSTEM_PROMPT.format(context=category.get("ai_context") or DEFAULT_CONTEXT)


TRANSLATE_SYSTEM_PROMPT = """Você traduz títulos e trechos de notícias de tecnologia para português do Brasil.
Regras: tradução fiel e natural; não resuma, não acrescente nem omita informação; mantenha nomes de empresas,
produtos, modelos e siglas sem traduzi-los (ex.: OpenAI, GPT-5, NVIDIA, LLM, Perplexity, Apple, Notion). Se o trecho estiver vazio, devolva excerpt_pt vazio.
Escreva os acentos diretamente (á, ç, ã, ó...), nunca como códigos ou sequências de escape.
Responda apenas com JSON no formato {"items": [{"ref": ..., "title_pt": ..., "excerpt_pt": ...}]}, preservando o "ref"."""

TRANSLATE_SCHEMA = {
    "type": "object",
    "properties": {
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "ref": {"type": "string"},
                    "title_pt": {"type": "string"},
                    "excerpt_pt": {"type": "string"},
                },
                "required": ["ref", "title_pt", "excerpt_pt"],
            },
        }
    },
    "required": ["items"],
}


DIGEST_SYSTEM_PROMPT = """Você resume notícias para um leitor brasileiro.
Para cada item, escreva em português do Brasil:
- title_pt: o título SEMPRE em português do Brasil. Se "lang" não for "pt", TRADUZA o título; nunca devolva o
  título em inglês (só nomes próprios, marcas e siglas ficam como no original). Se já estiver em português, repita-o;
- summary: SEMPRE em português, DOIS parágrafos (separados por uma linha em branco; 600 a 950 caracteres no total)
  que dispensem abrir a matéria. A primeira frase responde direto: o que aconteceu, quem fez, quando e onde.
  Primeiro parágrafo: o fato principal com os dados concretos (números, valores, datas, nomes, cargos, votações, locais).
  Segundo parágrafo: por que aconteceu, contexto e antecedentes, reações citadas e o que acontece a seguir (prazos, próximos
  passos). Priorize informação concreta; evite frases vagas ("o tema gera debate", "a medida pode ter impactos").
  O campo "text" costuma ser o texto da matéria (pode trazer restos de legenda, crédito ou menu: ignore-os). Se ele for só
  uma chamada curta, escreva um parágrafo apenas, com o que houver — nunca encha com generalidades.
Use SOMENTE informações presentes no título e no texto; não invente números, nomes, datas nem consequências.
Sem opinião, sem pontos fortes ou fracos, sem listas, sem frases como "a matéria fala sobre".
Mantenha nomes de pessoas, empresas, produtos e siglas como no original.
Escreva os acentos diretamente (á, ç, ã, ó...), nunca como códigos ou sequências de escape.
Responda apenas com JSON no formato {"items": [{"ref": ..., "title_pt": ..., "summary": ...}]}, preservando o "ref"."""

DIGEST_SCHEMA = {
    "type": "object",
    "properties": {
        "items": {
            "type": "array",
            "items": {
                "type": "object",
                "properties": {
                    "ref": {"type": "string"},
                    "title_pt": {"type": "string"},
                    "summary": {"type": "string"},
                },
                "required": ["ref", "title_pt", "summary"],
            },
        }
    },
    "required": ["items"],
}


def build_digest_prompt(items: list[dict]) -> str:
    return f"Resuma (JSON):\n<<DIGEST>>{json.dumps(items, ensure_ascii=False)}<</DIGEST>>"


def build_translate_prompt(items: list[dict]) -> str:
    return f"Traduza (JSON):\n<<TRANSLATE>>{json.dumps(items, ensure_ascii=False)}<</TRANSLATE>>"


def response_schema(subcategory_ids: list[str]) -> dict:
    potential = {"type": "string", "enum": ["low", "medium", "high"]}
    str_list = {"type": "array", "items": {"type": "string"}}
    item = {
        "type": "object",
        "properties": {
            "ref": {"type": "string"},
            "is_on_topic": {"type": "boolean"},
            "category": {"type": "string"},
            "subcategory": {"type": "string", "enum": subcategory_ids},
            "title_pt": {"type": "string"},
            "summary": {"type": "string"},
            "key_points": str_list,
            "why_it_matters": {"type": "string"},
            "potential_impacts": str_list,
            "uncertainties": str_list,
            "entities": str_list,
            "technologies": str_list,
            "relevance_score": {"type": "integer", "minimum": 0, "maximum": 100},
            "novelty_score": {"type": "integer", "minimum": 0, "maximum": 100},
            "business_potential": potential,
            "career_potential": potential,
            "study_potential": potential,
            "related_event_id": {"type": ["string", "null"]},
        },
        "required": [
            "ref", "is_on_topic", "category", "subcategory", "title_pt", "summary", "key_points",
            "why_it_matters", "potential_impacts", "uncertainties", "entities", "technologies",
            "relevance_score", "novelty_score", "business_potential", "career_potential",
            "study_potential", "related_event_id",
        ],
    }
    return {
        "type": "object",
        "properties": {"items": {"type": "array", "items": item}},
        "required": ["items"],
    }


def build_batch_prompt(articles: list[dict], recent_events: list[dict], category: dict) -> str:
    subs = "\n".join(f"- {s['id']}: {s['label']}" for s in category.get("subcategories", []))
    events = "\n".join(f"- {e['id']}: {e['title'][:90]}" for e in recent_events) or "(nenhum)"
    return (
        f"Categoria: {category['id']} — {category['label']}\n\n"
        f"Subcategorias permitidas:\n{subs}\n\n"
        f"Eventos recentes já registrados (para related_event_id):\n{events}\n\n"
        "Notícias a analisar (JSON). O campo 'excerpt' é o trecho publicado no feed; pode estar incompleto.\n"
        f"<<ARTICLES>>{json.dumps(articles, ensure_ascii=False)}<</ARTICLES>>"
    )
