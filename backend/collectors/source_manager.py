"""Carrega e valida as fontes de backend/config/sources.json."""
from __future__ import annotations

import json
from pathlib import Path

from pydantic import BaseModel, ConfigDict, Field, field_validator

from backend.models.article import SourceType


class Source(BaseModel):
    model_config = ConfigDict(extra="ignore")

    id: str = Field(pattern=r"^[a-z0-9][a-z0-9\-]*$")
    name: str
    url: str
    homepage: str | None = None
    category: str
    source_type: SourceType = "press"
    language: str = "en"
    priority: int = Field(default=3, ge=1, le=5)
    max_items_per_run: int = Field(default=8, ge=1, le=50)
    require_keywords: bool = False
    enabled: bool = True
    notes: str | None = None

    @field_validator("url")
    @classmethod
    def _https(cls, v: str) -> str:
        if not (v.startswith("https://") or v.startswith("fixture://")):
            raise ValueError("as fontes devem usar HTTPS")
        return v


def load_sources(path: Path, *, include_disabled: bool = False) -> list[Source]:
    data = json.loads(Path(path).read_text(encoding="utf-8"))
    sources = [Source.model_validate(s) for s in data.get("sources", [])]
    ids = [s.id for s in sources]
    dupes = {i for i in ids if ids.count(i) > 1}
    if dupes:
        raise ValueError(f"ids de fonte duplicados: {sorted(dupes)}")
    return sources if include_disabled else [s for s in sources if s.enabled]
