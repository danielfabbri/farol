from datetime import timedelta

import pytest
import requests

from backend.collectors.http_client import FetchError, fetch
from backend.collectors.normalization import (
    article_id, clean_text, keyword_pattern, normalize_url, truncate, utcnow,
)
from backend.collectors.rss_collector import _load_fixture, collect_source, parse_entries
from backend.collectors.source_manager import Source, load_sources
from backend.config.settings import CONFIG_DIR
from backend.tests.conftest import FakeResponse, NoNetworkSession, ScriptedSession

NOW = utcnow()


def src(**kw) -> Source:
    base = dict(id="exemplo", name="Exemplo", url="fixture://sample_rss.xml", category="technology_ai", source_type="primary")
    base.update(kw)
    return Source(**base)


# ---------- normalização ----------
def test_clean_text_strips_html_scripts_and_entities():
    assert clean_text("<p>Olá&nbsp;<b>mundo</b></p><script>x()</script> &amp; ok") == "Olá mundo & ok"


def test_fix_unicode_escapes_from_model_output():
    from backend.collectors.normalization import fix_unicode_escapes
    assert fix_unicode_escapes("Agentes de c#00f3digo n#u00e3o") == "Agentes de código não"
    assert fix_unicode_escapes("retorno #u00e0 Nvidia, avalia\\u00e7\\u00e3o") == "retorno à Nvidia, avaliação"
    assert fix_unicode_escapes("Lançado em #2024 com #1") == "Lançado em #2024 com #1"


def test_detects_corrupted_generated_text():
    from backend.collectors.normalization import looks_corrupted
    assert looks_corrupted("Perplexidade e Inteligêêode")
    assert looks_corrupted("c#00f3digo")
    assert not looks_corrupted("Nvidia volta atrás e traz Jensen Huang de volta como CEO (#1)")


def test_truncate_respects_word_boundary():
    assert truncate("um dois três quatro", 10) == "um dois…"
    assert truncate("curto", 10) == "curto"


@pytest.mark.parametrize("a,b", [
    ("https://www.Example.com/a/?utm_source=x&b=2&a=1#frag", "https://example.com/a?a=1&b=2"),
    ("http://example.com/a/", "https://example.com/a"),
    ("https://arxiv.org/pdf/2411.01234v3", "https://arxiv.org/abs/2411.01234"),
    ("https://arxiv.org/abs/2411.01234v1", "https://arxiv.org/abs/2411.01234"),
])
def test_normalize_url(a, b):
    assert normalize_url(a) == b


def test_article_id_is_stable_for_equivalent_urls():
    assert article_id(normalize_url("https://x.com/a?utm_medium=rss")) == article_id(normalize_url("https://www.x.com/a/"))


def test_keyword_pattern_word_boundaries():
    p = keyword_pattern(["ai", "agents?"])
    assert p.search("New AI tool")
    assert p.search("multi agent systems")
    assert not p.search("The mountain trail")  # "ai" dentro de "trail" não conta


# ---------- leitura de feeds ----------
def test_parse_rss_fixture():
    arts = parse_entries(_load_fixture("sample_rss.xml", NOW), src(), NOW)
    assert len(arts) == 4  # o item sem título é descartado
    first = arts[0]
    assert first.source_url.startswith("https://example.com/news/atlas")
    assert "alert" not in first.excerpt and "<" not in first.excerpt
    assert first.language == "en"
    assert first.published_at is not None and abs(first.published_at - (NOW - timedelta(hours=2))) < timedelta(seconds=2)
    assert first.collected_at == NOW


def test_parse_atom_fixture_uses_content_when_no_summary():
    arts = parse_entries(_load_fixture("sample_atom.xml", NOW), src(url="fixture://sample_atom.xml"), NOW)
    assert len(arts) == 2
    assert "startup funding" in arts[1].excerpt


def test_collect_filters_old_items_and_keywords():
    kw = keyword_pattern(["ai", "llm", "agents?"])
    res = collect_source(src(), NoNetworkSession(), cache={}, collected_at=NOW, max_age_days=4, keyword_re=kw)
    assert res.ok and res.entries_seen == 4
    assert all("Old archived" not in a.original_title for a in res.articles)

    hn = src(id="agg", url="fixture://sample_hn.xml", source_type="aggregator", require_keywords=True)
    res = collect_source(hn, NoNetworkSession(), cache={}, collected_at=NOW, max_age_days=4, keyword_re=kw)
    assert [a.original_title for a in res.articles] == ["[EXAMPLE] Show: an open source tool to evaluate LLM agents"]
    assert res.articles[0].discussion_url == "https://example.net/item?id=1"
    assert res.articles[0].excerpt == ""


def test_collect_handles_http_errors_without_raising():
    session = ScriptedSession([FakeResponse(404)])
    res = collect_source(src(url="https://example.com/feed"), session, cache={}, collected_at=NOW, max_age_days=4, keyword_re=None)
    assert not res.ok and res.error == "HTTP 404"


def test_collect_not_modified_uses_etag():
    session = ScriptedSession([FakeResponse(304)])
    res = collect_source(src(url="https://example.com/feed"), session, cache={"etag": '"abc"'},
                         collected_at=NOW, max_age_days=4, keyword_re=None)
    assert res.ok and res.status == "not_modified" and res.articles == []
    assert session.requests[0]["headers"]["If-None-Match"] == '"abc"'


def test_fetch_retries_with_backoff_then_succeeds():
    sleeps = []
    session = ScriptedSession([requests.ConnectionError(), FakeResponse(503), FakeResponse(200, content=b"<rss/>")])
    res = fetch("https://example.com/feed", session, sleep=sleeps.append)
    assert res.status == 200 and len(sleeps) == 2 and sleeps[1] > sleeps[0]


def test_fetch_gives_up_after_retries():
    session = ScriptedSession([FakeResponse(503)] * 3)
    with pytest.raises(FetchError):
        fetch("https://example.com/feed", session, sleep=lambda s: None)


# ---------- configuração ----------
def test_production_sources_are_valid_and_https():
    sources = load_sources(CONFIG_DIR / "sources.json", include_disabled=True)
    assert len(sources) >= 15
    assert all(s.url.startswith("https://") for s in sources)


def test_source_rejects_plain_http():
    with pytest.raises(ValueError):
        src(url="http://insecure.example.com/feed")


def test_extract_image_from_media_enclosure_and_img():
    from backend.collectors.rss_collector import extract_image
    assert extract_image({"media_content": [{"url": "https://img.example.com/a.jpg", "medium": "image"}]}, "") == "https://img.example.com/a.jpg"
    assert extract_image({"media_thumbnail": [{"url": "http://img.example.com/t.jpg"}]}, "") == "https://img.example.com/t.jpg"
    assert extract_image({"links": [{"rel": "enclosure", "type": "image/jpeg", "href": "https://x.com/e.jpg"}]}, "") == "https://x.com/e.jpg"
    assert extract_image({}, '<p><img src="https://x.com/i.png?w=1&amp;h=2"> texto</p>') == "https://x.com/i.png?w=1&h=2"
    assert extract_image({}, '<img src="https://x.com/pixel.gif">') is None
    assert extract_image({}, "sem imagem") is None


def test_section_rules_classify_general_feeds_and_drop_others():
    from backend.collectors.rss_collector import section_for
    s = src(url="https://example.com/feed", category="brasil",
            section_rules={"politica/": "politica", "internacional/": "mundo"})
    assert section_for(s, "https://www.cnnbrasil.com.br/politica/lula-stf/") == "politica"
    assert section_for(s, "https://www.cnnbrasil.com.br/internacional/x/") == "mundo"
    assert section_for(s, "https://www.cnnbrasil.com.br/esportes/futebol/x/") is None
    assert section_for(src(), "https://example.com/qualquer") == "technology_ai"


def test_rss_fixture_images_are_extracted():
    arts = parse_entries(_load_fixture("sample_rss.xml", NOW), src(), NOW)
    assert arts[0].image_url == "https://example.com/img/atlas.jpg"
