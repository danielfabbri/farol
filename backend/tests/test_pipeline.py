import json
from dataclasses import replace
from datetime import timedelta

from backend.ai.gemini_client import QuotaExceeded
from backend.ai.mock_client import MockGeminiClient
from backend.collectors.normalization import utcnow
from backend.models.edition import LatestFeed
from backend.pipeline import run
from backend.storage.json_repository import JsonRepository
from backend.tests.conftest import NoNetworkSession


def load(path):
    return json.loads(path.read_text(encoding="utf-8"))


def test_end_to_end_dev_run(settings):
    now = utcnow()
    result = run(settings, session=NoNetworkSession(), now=now)
    assert result.status == "ok"
    root = settings.data_dir

    latest = load(root / "latest.json")
    feed = LatestFeed.model_validate(latest)  # validação do contrato com o app
    assert feed.is_sample and "FICTÍCIOS" in feed.notice
    assert feed.edition.new_articles == 6  # 3 do RSS + 2 do Atom + 1 do agregador (o off-topic é filtrado)
    titles = [a.original_title for a in feed.articles]
    assert not any("bread" in t for t in titles) and not any("Old archived" in t for t in titles)
    assert all(a.ai_processed for a in feed.articles)

    # Mesmo acontecimento coberto por duas fontes => um evento com as duas fontes.
    multi = [e for e in feed.events if e.source_count == 2]
    assert len(multi) == 1 and "Atlas" in multi[0].sources[0].title
    assert feed.highlights and feed.highlights[0] in {e.id for e in feed.events}
    assert {"id": "agents", "label": "Agentes de IA"} in feed.filters

    # Persistência: arquivos por dia, edição e tendências.
    repo = JsonRepository(root)
    assert repo.article_days() and len(repo.load_recent_articles(now.date(), 8)) == 6
    assert list((root / "editions").rglob("*.json"))
    assert (root / "trends" / "latest.json").exists()
    runs = load(root / "state" / "runs.json")
    assert runs[-1]["status"] == "ok" and runs[-1]["ai"]["processed"] == 6


def test_second_run_is_incremental(settings):
    now = utcnow()
    run(settings, session=NoNetworkSession(), now=now)
    mock = MockGeminiClient()
    result = run(settings, session=NoNetworkSession(), ai_client=mock, now=now + timedelta(hours=1))
    assert result.summary["new_articles"] == 0
    assert mock.calls == 0  # nada é reprocessado


def test_duplicate_run_protection(settings):
    s = replace(settings, min_minutes_between_runs=30)
    now = utcnow()
    assert run(s, session=NoNetworkSession(), now=now).status == "ok"
    assert run(s, session=NoNetworkSession(), now=now + timedelta(minutes=5)).status == "skipped"
    assert run(s, session=NoNetworkSession(), now=now + timedelta(minutes=5), force=True).status == "ok"


def test_quota_exhausted_still_publishes_collected_data(settings):
    now = utcnow()
    result = run(settings, session=NoNetworkSession(), ai_client=MockGeminiClient(fail_with=QuotaExceeded("cota")), now=now)
    assert result.status == "partial"
    latest = load(settings.data_dir / "latest.json")
    assert latest["ai_status"]["state"] == "quota_exhausted"
    assert len(latest["articles"]) == 6 and not any(a["ai_processed"] for a in latest["articles"])
    assert latest["highlights"]  # edição continua sendo gerada sem IA
    usage = load(settings.data_dir / "state" / "ai_usage.json")
    assert usage["quota_exhausted"] is True


def test_without_key_runs_collection_only(settings):
    s = replace(settings, ai_mock=False, gemini_api_key=None)
    result = run(s, session=NoNetworkSession(), now=utcnow())
    latest = load(s.data_dir / "latest.json")
    assert result.status == "ok" and latest["ai_status"]["state"] == "no_key"


def test_retention_removes_old_files(settings):
    repo = JsonRepository(settings.data_dir)
    repo.write_json(repo.articles_dir / "2000-01-01.json", {"articles": []})
    (repo.editions_dir / "2000-01-01").mkdir(parents=True)
    run(settings, session=NoNetworkSession(), now=utcnow())
    assert not (repo.articles_dir / "2000-01-01.json").exists()
    assert not (repo.editions_dir / "2000-01-01").exists()
