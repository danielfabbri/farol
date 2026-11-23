from __future__ import annotations

from dataclasses import replace
from pathlib import Path

import pytest

from backend.config.settings import CONFIG_DIR, FIXTURES_DIR, Settings


class NoNetworkSession:
    """Garante que nenhum teste acesse a internet."""

    def get(self, *a, **k):
        raise AssertionError("acesso à rede proibido nos testes")

    post = get


class FakeResponse:
    def __init__(self, status_code: int, json_data=None, content: bytes = b"", headers=None):
        self.status_code = status_code
        self._json = json_data
        self.content = content
        self.headers = headers or {}

    def json(self):
        if self._json is None:
            raise ValueError("sem json")
        return self._json


class ScriptedSession:
    """Devolve respostas pré-definidas em sequência e registra as requisições."""

    def __init__(self, responses: list):
        self.responses = list(responses)
        self.requests: list[dict] = []

    def _next(self, method, url, **kw):
        self.requests.append({"method": method, "url": url, **kw})
        r = self.responses.pop(0)
        if isinstance(r, Exception):
            raise r
        return r

    def get(self, url, **kw):
        return self._next("GET", url, **kw)

    def post(self, url, **kw):
        return self._next("POST", url, **kw)


@pytest.fixture
def settings(tmp_path: Path) -> Settings:
    return Settings(
        data_dir=tmp_path / "data",
        sources_path=FIXTURES_DIR / "dev_sources.json",
        categories_path=CONFIG_DIR / "categories.json",
        gemini_api_key=None,
        ai_mock=True,
        dev_mode=True,
        min_minutes_between_runs=0,
        ai_min_interval_seconds=0,
    )


@pytest.fixture
def prod_like(settings: Settings) -> Settings:
    return replace(settings, min_minutes_between_runs=30, dev_mode=False)
