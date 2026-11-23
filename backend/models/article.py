from __future__ import annotations

from datetime import datetime
from typing import Literal

from pydantic import BaseModel, ConfigDict, Field

Potential = Literal["low", "medium", "high"]
SourceType = Literal["primary", "press", "aggregator", "research"]


class Article(BaseModel):
    """Notícia armazenada. Guarda metadados, link e resumo próprio — nunca o texto integral."""

    model_config = ConfigDict(extra="ignore")

    id: str
    event_id: str | None = None
    original_title: str
    title_pt: str | None = None
    source_id: str
    source_name: str
    source_type: SourceType = "press"
    source_url: str
    discussion_url: str | None = None
    image_url: str | None = None
    image_checked: bool = False  # já tentamos achar imagem na página da notícia
    published_at: datetime | None = None
    collected_at: datetime
    language: str = "en"
    excerpt: str = ""
    excerpt_pt: str | None = None
    category: str
    subcategory: str | None = None
    summary: str | None = None
    why_it_matters: str | None = None
    key_points: list[str] = Field(default_factory=list)
    potential_impacts: list[str] = Field(default_factory=list)
    uncertainties: list[str] = Field(default_factory=list)
    entities: list[str] = Field(default_factory=list)
    technologies: list[str] = Field(default_factory=list)
    tags: list[str] = Field(default_factory=list)
    relevance_score: int | None = None
    novelty_score: int | None = None
    business_potential: Potential | None = None
    career_potential: Potential | None = None
    study_potential: Potential | None = None
    off_topic: bool = False
    ai_processed: bool = False
    ai_model: str | None = None
    ai_processed_at: datetime | None = None
    ai_attempts: int = 0
    source_priority: int = 3
    is_sample: bool = False

    @property
    def reference_time(self) -> datetime:
        return self.published_at or self.collected_at

    @property
    def display_title(self) -> str:
        return self.title_pt or self.original_title
