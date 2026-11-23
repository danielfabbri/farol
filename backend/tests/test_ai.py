import json
from datetime import timedelta

import pytest

from backend.ai.gemini_client import FatalGeminiError, GeminiClient, GeminiError, QuotaExceeded
from backend.ai.mock_client import MockGeminiClient
from backend.ai.news_processor import AiUsage, NewsProcessor
from backend.collectors.normalization import utcnow
from backend.config.settings import CONFIG_DIR
from backend.models.analysis import ArticleAnalysis
from backend.models.article import Article
from backend.tests.conftest import FakeResponse, ScriptedSession

NOW = utcnow()
FAKE_KEY = "TEST-KEY-1234567890-never-real"
CATEGORY = json.loads((CONFIG_DIR / "categories.json").read_text(encoding="utf-8"))["categories"][0]


def art(i: int) -> Article:
    return Article(id=f"id{i}", original_title=f"Example agent news {i}", source_id="s", source_name="S",
                   source_url=f"https://example.com/{i}", published_at=NOW - timedelta(hours=1),
                   collected_at=NOW, category="technology_ai", excerpt="Fictional excerpt.")


def ok_body(obj: dict) -> dict:
    return {"candidates": [{"content": {"parts": [{"text": json.dumps(obj)}]}, "finishReason": "STOP"}]}


def client(session, **kw) -> GeminiClient:
    return GeminiClient(FAKE_KEY, "models/gemini-test", session=session, sleep=lambda s: None, min_interval=0, **kw)


# ---------- cliente Gemini (respostas simuladas, sem rede) ----------
def test_client_sends_key_only_in_header_and_parses_json():
    s = ScriptedSession([FakeResponse(200, ok_body({"items": []}))])
    assert client(s).generate_json("sys", "prompt") == {"items": []}
    req = s.requests[0]
    assert FAKE_KEY not in req["url"] and req["headers"]["x-goog-api-key"] == FAKE_KEY
    assert req["url"].endswith("/models/gemini-test:generateContent")
    assert FAKE_KEY not in repr(client(s))


def test_client_daily_quota_raises_quota_exceeded():
    err = {"error": {"code": 429, "status": "RESOURCE_EXHAUSTED", "message": "quota", "details": [
        {"@type": "type.googleapis.com/google.rpc.QuotaFailure",
         "violations": [{"quotaId": "GenerateRequestsPerDayPerProjectPerModel-FreeTier"}]},
        {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "20s"}]}}
    with pytest.raises(QuotaExceeded) as exc:
        client(ScriptedSession([FakeResponse(429, err)])).generate_json("s", "p")
    assert FAKE_KEY not in str(exc.value)


def test_client_per_minute_limit_waits_and_retries():
    err = {"error": {"code": 429, "status": "RESOURCE_EXHAUSTED", "message": "rate", "details": [
        {"@type": "type.googleapis.com/google.rpc.RetryInfo", "retryDelay": "12s"}]}}
    slept = []
    c = GeminiClient(FAKE_KEY, "m", session=ScriptedSession([FakeResponse(429, err), FakeResponse(200, ok_body({"items": []}))]),
                     sleep=slept.append, min_interval=0)
    assert c.generate_json("s", "p") == {"items": []}
    assert slept == [13.0] and c.calls_made == 2


def test_client_falls_back_to_free_model_when_overloaded():
    s = ScriptedSession([FakeResponse(503, {})] * 3 + [FakeResponse(200, ok_body({"items": []}))])
    c = client(s, fallback_models=["gemini-alt"])
    assert c.generate_json("s", "p") == {"items": []}
    assert s.requests[-1]["url"].endswith("/models/gemini-alt:generateContent")
    assert c.model == "gemini-alt" and c.calls_made == 1  # 503 não consome o orçamento


def test_client_falls_back_when_model_retired():
    s = ScriptedSession([FakeResponse(404, {"error": {"status": "NOT_FOUND"}}), FakeResponse(200, ok_body({"items": []}))])
    c = client(s, fallback_models=["gemini-alt"])
    assert c.generate_json("s", "p") == {"items": []} and c.model == "gemini-alt"


DAILY_429 = {"error": {"code": 429, "details": [{"violations": [{"quotaId": "RequestsPerDay"}]}]}}
MINUTE_429 = {"error": {"code": 429, "details": [{"@type": "x.RetryInfo", "retryDelay": "300s"}]}}


def test_client_daily_quota_of_one_model_moves_to_next_free_model():
    s = ScriptedSession([FakeResponse(429, DAILY_429), FakeResponse(200, ok_body({"items": []}))])
    c = client(s, fallback_models=["gemini-alt"])
    assert c.generate_json("s", "p") == {"items": []}
    assert c.exhausted_models == {"gemini-test"}


def test_client_skips_models_already_exhausted_today():
    s = ScriptedSession([FakeResponse(200, ok_body({"items": []}))])
    c = client(s, fallback_models=["gemini-alt"], exhausted_models={"gemini-test"})
    c.generate_json("s", "p")
    assert s.requests[0]["url"].endswith("/models/gemini-alt:generateContent")


def test_client_all_models_exhausted_makes_no_request():
    s = ScriptedSession([])
    with pytest.raises(QuotaExceeded):
        client(s, fallback_models=["gemini-alt"], exhausted_models={"gemini-test", "gemini-alt"}).generate_json("s", "p")
    assert s.requests == []


def test_client_per_minute_quota_stops_without_fallback():
    s = ScriptedSession([FakeResponse(429, MINUTE_429)])
    with pytest.raises(QuotaExceeded) as exc:
        client(s, fallback_models=["gemini-alt"]).generate_json("s", "p")
    assert not exc.value.daily and len(s.requests) == 1


def test_client_retries_server_errors_then_fails():
    with pytest.raises(GeminiError):
        client(ScriptedSession([FakeResponse(503, {})] * 3)).generate_json("s", "p")


@pytest.mark.parametrize("status", [401, 403, 404])
def test_client_fatal_errors(status):
    with pytest.raises(FatalGeminiError):
        client(ScriptedSession([FakeResponse(status, {"error": {"message": "x"}})])).generate_json("s", "p")


def test_client_invalid_json_is_an_error():
    body = {"candidates": [{"content": {"parts": [{"text": "isto não é json"}]}}]}
    with pytest.raises(GeminiError):
        client(ScriptedSession([FakeResponse(200, body)])).generate_json("s", "p")


# ---------- validação da resposta ----------
def test_analysis_validation_clamps_and_normalizes():
    a = ArticleAnalysis.model_validate({
        "ref": "a1", "title_pt": "Título", "summary": "Resumo objetivo da notícia.",
        "relevance_score": 150, "novelty_score": "x", "business_potential": "Alto",
        "key_points": ["a", "a", "b", "c", "d", "e", "f"], "related_event_id": "qualquer",
    })
    assert a.relevance_score == 100 and a.novelty_score == 50
    assert a.business_potential == "high" and a.key_points == ["a", "b", "c", "d", "e"]
    assert a.related_event_id is None


# ---------- processador em lote ----------
def processor(c, batch=2):
    return NewsProcessor(c, CATEGORY, batch_size=batch, max_input_chars=200, max_attempts=3)


def usage(daily=10, run=10, state=None):
    return AiUsage(state, NOW, daily_limit=daily, run_limit=run)


def test_processor_batches_and_applies_results():
    arts = [art(i) for i in range(5)]
    mock = MockGeminiClient()
    rep = processor(mock).process(arts, [], usage(), NOW)
    assert mock.calls == 3 and rep.processed == 5 and rep.failed == 0
    assert all(a.ai_processed and a.title_pt.startswith("[FICTÍCIO]") for a in arts)
    assert arts[0].subcategory == "ai_agents" and arts[0].ai_model == "mock"


def test_processor_respects_run_budget():
    arts = [art(i) for i in range(6)]
    u = usage(run=1)
    rep = processor(MockGeminiClient()).process(arts, [], u, NOW)
    assert rep.processed == 2 and rep.stopped_reason == "budget_reached"
    assert sum(a.ai_processed for a in arts) == 2


def test_processor_stops_on_quota_and_keeps_articles():
    arts = [art(i) for i in range(4)]
    u = usage()
    rep = processor(MockGeminiClient(fail_with=QuotaExceeded("cota"))).process(arts, [], u, NOW)
    assert rep.stopped_reason == "quota_exhausted" and u.quota_exhausted
    assert not any(a.ai_processed for a in arts)
    # Na mesma janela diária, nenhuma nova chamada é feita.
    u2 = usage(state=u.state)
    mock = MockGeminiClient()
    processor(mock).process(arts, [], u2, NOW)
    assert mock.calls == 0


def test_processor_per_minute_quota_does_not_block_the_day():
    u = usage()
    processor(MockGeminiClient(fail_with=QuotaExceeded("rpm", daily=False))).process([art(1)], [], u, NOW)
    assert not u.quota_exhausted


def test_processor_invalid_items_count_as_attempts():
    arts = [art(1), art(2)]
    raw = {"items": [{"ref": "a1", "title_pt": "ok título", "summary": "Resumo válido aqui."}, {"ref": "a2", "summary": ""}]}
    rep = processor(MockGeminiClient(raw_response=raw)).process(arts, [], usage(), NOW)
    assert rep.processed == 1 and rep.failed == 1
    assert arts[0].ai_processed and not arts[1].ai_processed and arts[1].ai_attempts == 1


def test_processor_generic_error_marks_attempt_and_continues():
    arts = [art(i) for i in range(4)]
    rep = processor(MockGeminiClient(fail_with=GeminiError("falha"))).process(arts, [], usage(), NOW)
    assert rep.failed == 4 and rep.batches == 2 and all(a.ai_attempts == 1 for a in arts)


def test_processor_overload_does_not_penalize_articles():
    from backend.ai.gemini_client import ServiceUnavailable
    arts = [art(i) for i in range(4)]
    mock = MockGeminiClient(fail_with=ServiceUnavailable("503"))
    rep = processor(mock).process(arts, [], usage(), NOW)
    assert rep.stopped_reason == "unavailable" and mock.calls == 1
    assert all(a.ai_attempts == 0 and not a.ai_processed for a in arts)


def test_processor_skips_articles_that_exhausted_attempts():
    a = art(1)
    a.ai_attempts = 3
    mock = MockGeminiClient()
    processor(mock).process([a], [], usage(), NOW)
    assert mock.calls == 0


def test_fallback_title_translation_and_budget():
    from backend.ai.fallback_translator import translate_titles
    ok = lambda t: FakeResponse(200, {"responseStatus": 200, "responseData": {"translatedText": t}})
    arts = [art(1), art(2), art(3)]
    s = ScriptedSession([ok("Notícia de agentes 1"), ok("Not\\u00edcia corrompida"), ok("x" * 10)])
    state = {}
    n = translate_titles(arts, s, state, run_char_limit=1000)
    assert n == 2 and arts[0].title_pt == "Notícia de agentes 1" and arts[1].title_pt is None
    assert state["fallback_chars"] == sum(len(a.original_title) for a in arts)
    # Orçamento diário esgotado: nenhuma requisição.
    s2 = ScriptedSession([])
    assert translate_titles([art(4)], s2, {"fallback_chars": 4500}) == 0 and s2.requests == []


def test_fallback_stops_when_service_quota_ends():
    from backend.ai.fallback_translator import translate_titles
    warn = FakeResponse(200, {"responseStatus": 429, "quotaFinished": True,
                              "responseData": {"translatedText": "MYMEMORY WARNING: YOU USED ALL AVAILABLE FREE TRANSLATIONS"}})
    state = {}
    arts = [art(1), art(2)]
    assert translate_titles(arts, ScriptedSession([warn]), state) == 0
    assert state["fallback_chars"] == 4500 and arts[0].title_pt is None


def test_prompt_never_contains_api_key_and_is_truncated():
    a = art(1)
    a.excerpt = "x " * 1000
    mock = MockGeminiClient()
    processor(mock).process([a], [{"id": "evt-1", "title": "Evento"}], usage(), NOW)
    assert "evt-1" in mock.prompts[0] and len(mock.prompts[0]) < 4000
