"""Persistência em arquivos JSON versionados no Git.

Layout:
  data/latest.json                     visão consolidada consumida pelo app
  data/articles/AAAA-MM-DD.json        artigos por dia de publicação (UTC)
  data/editions/AAAA-MM-DD/HHMM.json   edições por horário de execução
  data/trends/latest.json              tendências atuais (+ instantâneos diários)
  data/state/*.json                    estado interno (cache HTTP, cota, execuções)
"""
from __future__ import annotations

import json
import os
import shutil
import tempfile
from collections import defaultdict
from datetime import date, timedelta
from pathlib import Path
from typing import Any, Iterable

from backend.models.article import Article

_DAY_FMT_LEN = len("2026-01-01")


class JsonRepository:
    def __init__(self, data_dir: Path):
        self.root = Path(data_dir)
        self.articles_dir = self.root / "articles"
        self.editions_dir = self.root / "editions"
        self.trends_dir = self.root / "trends"
        self.state_dir = self.root / "state"
        self.latest_path = self.root / "latest.json"
        for d in (self.articles_dir, self.editions_dir, self.trends_dir, self.state_dir):
            d.mkdir(parents=True, exist_ok=True)

    # ---------- utilitários ----------
    @staticmethod
    def read_json(path: Path, default: Any = None) -> Any:
        try:
            return json.loads(Path(path).read_text(encoding="utf-8"))
        except FileNotFoundError:
            return default

    @staticmethod
    def write_json(path: Path, data: Any, *, compact: bool = False) -> None:
        """Escrita atômica: grava em arquivo temporário e substitui."""
        path = Path(path)
        path.parent.mkdir(parents=True, exist_ok=True)
        text = json.dumps(
            data, ensure_ascii=False, indent=None if compact else 1,
            separators=(",", ":") if compact else None, default=str,
        )
        fd, tmp = tempfile.mkstemp(dir=path.parent, prefix=".tmp-", suffix=".json")
        try:
            with os.fdopen(fd, "w", encoding="utf-8", newline="\n") as fh:
                fh.write(text + "\n")
            os.replace(tmp, path)
        except BaseException:
            Path(tmp).unlink(missing_ok=True)
            raise

    # ---------- artigos ----------
    @staticmethod
    def day_key(article: Article) -> str:
        return article.reference_time.date().isoformat()

    def article_days(self) -> list[str]:
        return sorted(p.stem for p in self.articles_dir.glob("????-??-??.json"))

    def load_articles(self, days: Iterable[str]) -> dict[str, Article]:
        out: dict[str, Article] = {}
        for day in days:
            doc = self.read_json(self.articles_dir / f"{day}.json", {"articles": []})
            for raw in doc.get("articles", []):
                a = Article.model_validate(raw)
                out[a.id] = a
        return out

    def load_recent_articles(self, today: date, window_days: int) -> dict[str, Article]:
        start = (today - timedelta(days=window_days)).isoformat()
        return self.load_articles(d for d in self.article_days() if d >= start)

    def save_articles(self, articles: Iterable[Article]) -> list[str]:
        by_day: dict[str, list[Article]] = defaultdict(list)
        for a in articles:
            by_day[self.day_key(a)].append(a)
        for day, items in by_day.items():
            path = self.articles_dir / f"{day}.json"
            existing = {r["id"]: r for r in self.read_json(path, {"articles": []}).get("articles", [])}
            for a in items:
                existing[a.id] = a.model_dump(mode="json")
            ordered = sorted(existing.values(), key=lambda r: r.get("published_at") or r["collected_at"], reverse=True)
            self.write_json(path, {"date": day, "count": len(ordered), "articles": ordered})
        return sorted(by_day)

    # ---------- edições, tendências, latest ----------
    def save_edition(self, edition_id: str, doc: dict) -> Path:
        day, hhmm = edition_id[:_DAY_FMT_LEN], edition_id[_DAY_FMT_LEN + 1:]
        path = self.editions_dir / day / f"{hhmm}.json"
        self.write_json(path, doc)
        return path

    def save_latest(self, doc: dict) -> None:
        self.write_json(self.latest_path, doc, compact=True)

    def save_trends(self, doc: dict, day: str) -> None:
        self.write_json(self.trends_dir / "latest.json", doc)
        self.write_json(self.trends_dir / f"{day}.json", doc)

    # ---------- estado ----------
    def load_state(self, name: str, default: Any) -> Any:
        return self.read_json(self.state_dir / f"{name}.json", default)

    def save_state(self, name: str, data: Any) -> None:
        self.write_json(self.state_dir / f"{name}.json", data)

    # ---------- retenção ----------
    def apply_retention(self, today: date, *, article_days: int, edition_days: int, trend_days: int) -> list[str]:
        removed: list[str] = []
        art_cut = (today - timedelta(days=article_days)).isoformat()
        for p in self.articles_dir.glob("????-??-??.json"):
            if p.stem < art_cut:
                p.unlink()
                removed.append(str(p.relative_to(self.root)))
        ed_cut = (today - timedelta(days=edition_days)).isoformat()
        for d in self.editions_dir.iterdir():
            if d.is_dir() and d.name < ed_cut:
                shutil.rmtree(d)
                removed.append(str(d.relative_to(self.root)))
        tr_cut = (today - timedelta(days=trend_days)).isoformat()
        for p in self.trends_dir.glob("????-??-??.json"):
            if p.stem < tr_cut:
                p.unlink()
                removed.append(str(p.relative_to(self.root)))
        return removed
