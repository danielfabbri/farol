from __future__ import annotations

from datetime import datetime

from pydantic import BaseModel, Field

from backend.models.article import Article


class EventSource(BaseModel):
    article_id: str
    source_id: str
    source_name: str
    source_url: str
    title: str
    published_at: datetime | None = None


class Event(BaseModel):
    """Um acontecimento, possivelmente coberto por várias fontes independentes."""

    id: str
    title: str
    summary: str | None = None
    category: str
    subcategory: str | None = None
    first_seen: datetime
    last_seen: datetime
    article_ids: list[str]
    sources: list[EventSource]
    source_count: int
    entities: list[str] = Field(default_factory=list)
    technologies: list[str] = Field(default_factory=list)
    tags: list[str] = Field(default_factory=list)
    relevance_score: int | None = None
    score: float = 0.0
    ai_processed: bool = False


class EditionInfo(BaseModel):
    id: str
    label: str
    generated_at: datetime
    highlight_count: int
    article_count: int
    ai_processed_count: int
    sources_ok: int
    sources_failed: int
    new_articles: int


class Trend(BaseModel):
    """Sinal de tendência: contagem determinística, sem previsões da IA."""

    key: str
    label: str
    kind: str
    first_seen: datetime
    last_seen: datetime
    article_count: int
    source_count: int
    event_count: int
    daily_counts: dict[str, int]
    related_entities: list[str] = Field(default_factory=list)
    event_ids: list[str] = Field(default_factory=list)
    status: str


class LatestFeed(BaseModel):
    """Contrato de dados consumido pelo aplicativo Android (data/latest.json)."""

    schema_version: int = 1
    generated_at: datetime
    is_sample: bool = False
    edition: EditionInfo
    category: dict
    subcategories: dict[str, str]
    filters: list[dict]
    highlights: list[str]
    events: list[Event]
    articles: list[Article]
    archive_days: list[str]
    ai_status: dict
    notice: str | None = None
