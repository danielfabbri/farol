"""Estrutura da resposta da IA, validada com Pydantic.

Os escores e potenciais são avaliações editoriais da IA, não fatos objetivos.
"""
from __future__ import annotations

from pydantic import BaseModel, ConfigDict, Field, field_validator, model_validator

from backend.collectors.normalization import fix_unicode_escapes, looks_corrupted


def _reject_corrupted(*texts: str) -> None:
    for t in texts:
        if looks_corrupted(t):
            raise ValueError("texto gerado com caracteres corrompidos")
from backend.models.article import Potential


def _fix_all(data):
    """Aplica a correção de acentos em todos os textos da resposta da IA."""
    if isinstance(data, str):
        return fix_unicode_escapes(data)
    if isinstance(data, list):
        return [_fix_all(x) for x in data]
    if isinstance(data, dict):
        return {k: _fix_all(v) for k, v in data.items()}
    return data


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

    @model_validator(mode="before")
    @classmethod
    def _fix_text(cls, data):
        return _fix_all(data)

    @model_validator(mode="after")
    def _quality(self):
        _reject_corrupted(self.title_pt, self.summary, self.why_it_matters, *self.key_points, *self.potential_impacts)
        return self

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


class Translation(BaseModel):
    """Tradução leve (título + trecho) para notícias que ainda não receberam a análise completa."""

    model_config = ConfigDict(extra="ignore")

    ref: str
    title_pt: str = Field(min_length=3, max_length=240)
    excerpt_pt: str = Field(default="", max_length=1200)

    @model_validator(mode="before")
    @classmethod
    def _fix_text(cls, data):
        return _fix_all(data)

    @model_validator(mode="after")
    def _quality(self):
        _reject_corrupted(self.title_pt, self.excerpt_pt)
        return self


class Digest(BaseModel):
    """Resumo (um ou dois parágrafos) + título em português."""

    model_config = ConfigDict(extra="ignore")

    ref: str
    title_pt: str = Field(min_length=3, max_length=240)
    summary: str = Field(min_length=40, max_length=1100)

    @model_validator(mode="before")
    @classmethod
    def _fix_text(cls, data):
        return _fix_all(data)

    @model_validator(mode="after")
    def _quality(self):
        _reject_corrupted(self.title_pt, self.summary)
        return self


class BatchAnalysis(BaseModel):
    items: list[dict] = Field(default_factory=list)


class BriefPointOut(BaseModel):
    model_config = ConfigDict(extra="ignore")

    text: str = Field(min_length=10, max_length=400)
    refs: list[str] = Field(default_factory=list)


class BriefOutput(BaseModel):
    """Resposta da IA para o resumo de uma seção inteira."""

    model_config = ConfigDict(extra="ignore")

    headline: str = Field(min_length=5, max_length=140)
    overview: str = Field(min_length=20, max_length=900)
    points: list[BriefPointOut] = Field(min_length=1, max_length=8)
    watch: str | None = Field(default=None, max_length=400)

    @model_validator(mode="before")
    @classmethod
    def _fix_text(cls, data):
        return _fix_all(data)

    @model_validator(mode="after")
    def _quality(self):
        _reject_corrupted(self.headline, self.overview, self.watch or "", *(p.text for p in self.points))
        return self
