"""Estrutura da resposta da IA, validada com Pydantic.

Os escores e potenciais são avaliações editoriais da IA, não fatos objetivos.
"""
from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field, field_validator

from backend.models.article import Potential


def _clean_list(values: list, limit: int) -> list[str]:
    out: list[str] = []
    for v in values or []:
        s = str(v).strip()
        if s and s not in out:
            out.append(s[:300])
    return out[:limit]


class ArticleAnalysis(BaseModel):
    model_config = ConfigDict(extra="ignore")

    ref: str
    is_on_topic: bool = True
    category: str = "technology_ai"
    subcategory: str = "other"
    title_pt: str = Field(min_length=3, max_length=240)
    summary: str = Field(min_length=10, max_length=1500)
    key_points: list[str] = Field(default_factory=list)
    why_it_matters: str = Field(default="", max_length=1200)
    potential_impacts: list[str] = Field(default_factory=list)
    uncertainties: list[str] = Field(default_factory=list)
    entities: list[str] = Field(default_factory=list)
    technologies: list[str] = Field(default_factory=list)
    relevance_score: int = 50
    novelty_score: int = 50
    business_potential: Potential = "low"
    career_potential: Potential = "low"
    study_potential: Potential = "low"
    related_event_id: str | None = None

    @field_validator("relevance_score", "novelty_score", mode="before")
    @classmethod
    def _clamp(cls, v):
        try:
            return max(0, min(100, int(round(float(v)))))
        except (TypeError, ValueError):
            return 50

    @field_validator("business_potential", "career_potential", "study_potential", mode="before")
    @classmethod
    def _potential(cls, v):
        s = str(v or "").strip().lower()
        mapping = {"baixo": "low", "médio": "medium", "medio": "medium", "alto": "high"}
        s = mapping.get(s, s)
        return s if s in {"low", "medium", "high"} else "low"

    @field_validator("key_points", "potential_impacts", "uncertainties", mode="before")
    @classmethod
    def _lists5(cls, v):
        return _clean_list(v if isinstance(v, list) else [], 5)

    @field_validator("entities", "technologies", mode="before")
    @classmethod
    def _lists10(cls, v):
        return _clean_list(v if isinstance(v, list) else [], 10)

    @field_validator("related_event_id", mode="before")
    @classmethod
    def _rel(cls, v):
        s = str(v or "").strip()
        return s if s.startswith("evt-") else None


class BatchAnalysis(BaseModel):
    items: list[dict] = Field(default_factory=list)
