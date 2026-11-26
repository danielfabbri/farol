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
    assert feed.edition.new_articles == 9  # tec: 3 RSS + 2 Atom + 1 agregador (off-topic filtrado); política: 2 + 1
    titles = [a.original_title for a in feed.articles]
    assert not any("bread" in t for t in titles) and not any("Old archived" in t for t in titles)
    assert all(a.ai_processed for a in feed.articles)

    # Mesmo acontecimento coberto por duas fontes => um evento com as duas fontes.
    multi = [e for e in feed.events if e.source_count == 2]
    assert len(multi) == 2 and any("Atlas" in e.sources[0].title for e in multi)
    assert any("Câmara" in e.sources[0].title for e in multi)  # agrupamento entre portais em português
    assert feed.highlights and feed.highlights[0] in {e.id for e in feed.events}
    assert {"id": "agents", "label": "Agentes de IA"} in feed.filters

    # Persistência: arquivos por dia, edição e tendências.
    repo = JsonRepository(root)
    assert repo.article_days() and len(repo.load_recent_articles(now.date(), 8)) == 9
    assert list((root / "editions").rglob("*.json"))
    assert (root / "trends" / "latest.json").exists()
    runs = load(root / "state" / "runs.json")
    assert runs[-1]["status"] == "ok" and runs[-1]["ai"]["processed"] == 9


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
    arts = latest["articles"]
    assert arts and not any(a["ai_processed"] for a in arts)
    # Tudo em português: as estrangeiras sem tradução ficam guardadas até serem traduzidas.
    assert all(a["language"] == "pt" or a["title_pt"] for a in arts) and len(arts) < 9
    assert latest["highlights"]  # edição continua sendo gerada sem IA
    usage = load(settings.data_dir / "state" / "ai_usage.json")
    assert usage["quota_exhausted"] is True


class AnalysisDownTranslationUp(MockGeminiClient):
    """Simula a análise completa indisponível e a tradução leve funcionando."""

    def generate_json(self, system, prompt, schema=None):
        if "<<TRANSLATE>>" not in prompt:
            self.calls += 1
            from backend.ai.gemini_client import ServiceUnavailable
            raise ServiceUnavailable("503")
        return super().generate_json(system, prompt, schema)


def test_translation_fallback_keeps_everything_in_portuguese(settings):
    result = run(settings, session=NoNetworkSession(), ai_client=AnalysisDownTranslationUp(), now=utcnow())
    latest = load(settings.data_dir / "latest.json")
    assert latest["ai_status"]["state"] == "unavailable"
    assert latest["ai_status"]["translated_this_run"] == 6  # só as 6 em inglês; as em português não precisam
    assert "traduzidos" in latest["ai_status"]["message"]
    english = [a for a in latest["articles"] if a["language"] != "pt"]
    assert english and all(a["title_pt"].startswith("[FICTÍCIO]") and not a["ai_processed"] for a in english)
    assert result.summary["ai"]["translated"] == 6


def test_translation_never_overwrites_full_analysis(settings):
    run(settings, session=NoNetworkSession(), now=utcnow())
    latest = load(settings.data_dir / "latest.json")
    assert all(a["ai_processed"] and a["excerpt_pt"] is None for a in latest["articles"])


def test_new_filters_apply_to_existing_articles(settings):
    run(settings, session=NoNetworkSession(), now=utcnow())
    latest = load(settings.data_dir / "latest.json")
    agents = [a for a in latest["articles"] if "agents" in a["tags"]]
    assert agents and {"id": "research", "label": "Pesquisa"} in latest["filters"]


def test_thematic_sections_claim_matching_titles(settings):
    run(settings, session=NoNetworkSession(), now=utcnow())
    latest = load(settings.data_dir / "latest.json")
    robots = [a for a in latest["articles"] if "robot" in a["original_title"].lower()]
    assert robots and all(a["category"] == "robotica" for a in robots)
    assert any(s["id"] == "robotica" for s in latest["sections"])


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


def test_sections_and_briefs(settings):
    now = utcnow()
    run(settings, session=NoNetworkSession(), now=now)
    latest = load(settings.data_dir / "latest.json")
    secs = {s["id"]: s for s in latest["sections"]}
    assert [s["id"] for s in latest["sections"]] == ["brasil", "politica", "economia", "mundo", "ciencia_saude", "technology_ai", "robotica", "espaco"]
    tech, pol = secs["technology_ai"], secs["politica"]
    assert tech["highlights"] and pol["highlights"]
    assert {"id": "agents", "label": "Agentes de IA"} in tech["filters"] and pol["filters"] == []
    # Capa: o melhor de cada seção com conteúdo entra nos destaques gerais.
    assert pol["highlights"][0] in latest["highlights"] and tech["highlights"][0] in latest["highlights"]
    brief = tech["brief"]
    event_ids = {e["id"] for e in latest["events"]}
    assert brief and brief["points"] and all(set(p["event_ids"]) <= event_ids for p in brief["points"])


def test_brief_is_reused_when_section_unchanged(settings):
    now = utcnow()
    run(settings, session=NoNetworkSession(), now=now)
    mock = MockGeminiClient()
    run(settings, session=NoNetworkSession(), ai_client=mock, now=now + timedelta(hours=1))
    assert not any("<<BRIEF>>" in p for p in mock.prompts)  # nada mudou: nenhuma chamada nova


def test_brief_failure_keeps_previous_as_stale(settings):
    from backend.ai.gemini_client import ServiceUnavailable
    from backend.storage.json_repository import JsonRepository
    now = utcnow()
    run(settings, session=NoNetworkSession(), now=now)
    repo = JsonRepository(settings.data_dir)
    store = repo.read_json(repo.root / "briefs" / "latest.json", {})
    store["technology_ai"]["signature"] = "mudou"
    repo.write_json(repo.root / "briefs" / "latest.json", store)
    run(settings, session=NoNetworkSession(), ai_client=MockGeminiClient(fail_with=ServiceUnavailable("503")), now=now + timedelta(hours=1))
    latest = load(settings.data_dir / "latest.json")
    tech = next(s for s in latest["sections"] if s["id"] == "technology_ai")
    assert tech["brief"]["stale"] is True


def test_digest_writes_short_paragraph_for_recent_articles(settings):
    s = replace(settings, analysis_enabled=False)
    run(s, session=NoNetworkSession(), now=utcnow())
    latest = load(s.data_dir / "latest.json")
    with_text = [a for a in latest["articles"] if len(a["excerpt"]) >= s.digest_min_excerpt_chars]
    assert with_text and all(a["summary"].startswith("[FICTÍCIO] Parágrafo") for a in with_text)
    assert all(not a["ai_processed"] for a in latest["articles"])
    # Palavras-chave do tema: normalizadas (minúsculas) e sem as genéricas ("notícia").
    assert all(a["keywords"][0] == "tema simulado" and "notícia" not in a["keywords"] for a in with_text)
    evt = next(e for e in latest["events"] if set(e["article_ids"]) & {a["id"] for a in with_text})
    assert "tema simulado" in evt["keywords"]


def test_digest_disabled_leaves_summary_empty(settings):
    s = replace(settings, digest_enabled=False, analysis_enabled=False)
    run(s, session=NoNetworkSession(), now=utcnow())
    latest = load(s.data_dir / "latest.json")
    assert all(not a["summary"] for a in latest["articles"])
