from datetime import timedelta

from backend.ai.event_clusterer import apply_ai_relations, assign_events_by_title, build_events
from backend.collectors.normalization import utcnow
from backend.models.article import Article
from backend.storage.deduplication import Deduplicator, title_similarity

NOW = utcnow()


def art(id, title, source="s1", hours=0, category="technology_ai", **kw) -> Article:
    return Article(id=id, original_title=title, source_id=source, source_name=source.upper(),
                   source_url=f"https://example.com/{id}", published_at=NOW - timedelta(hours=hours),
                   collected_at=NOW, category=category, **kw)


def test_title_similarity_same_story_vs_different():
    same, shared = title_similarity("OpenAI launches GPT-6 with new reasoning mode",
                                    "OpenAI launches GPT-6, adds new reasoning mode for developers")
    diff, _ = title_similarity("OpenAI launches GPT-6 with new reasoning mode", "NVIDIA opens robotics lab in Taiwan")
    assert same >= 0.6 and shared >= 3
    assert diff < 0.3


def test_deduplicator_by_id_and_by_same_source_title():
    d = Deduplicator()
    a = art("a1", "Example launches thing")
    assert not d.is_duplicate(a)
    d.register(a)
    assert d.is_duplicate(art("a1", "anything"))
    assert d.is_duplicate(art("zz", "Example launches thing"))           # mesma fonte, outra URL
    assert not d.is_duplicate(art("zz", "Example launches thing", "s2"))  # outra fonte: não descarta


def test_deduplicator_prune_keeps_recent():
    d = Deduplicator({"old": {"s": "s", "t": "x", "d": "2000-01-01"}})
    d.prune(NOW.date(), keep_days=30)
    assert "old" not in d.seen


def test_assign_events_groups_independent_sources():
    a = art("a", "Example Labs launches Atlas agent framework for enterprise automation", "s1", hours=2)
    b = art("b", "Example Labs launches Atlas agent framework, taking aim at enterprise automation", "s2", hours=1)
    c = art("c", "Nimbus raises Series A for GPU cloud", "s2", hours=1)
    assign_events_by_title([a, b, c], [])
    assert a.event_id == b.event_id == "evt-a"
    assert c.event_id == "evt-c"
    events = {e.id: e for e in build_events([a, b, c])}
    assert events["evt-a"].source_count == 2
    assert [s.article_id for s in events["evt-a"].sources] == ["a", "b"]  # nenhuma fonte descartada


def test_same_source_is_never_grouped():
    a = art("a", "Example launches Atlas agent framework today", "s1")
    b = art("b", "Example launches Atlas agent framework today again", "s1")
    assign_events_by_title([a, b], [])
    assert a.event_id != b.event_id


def test_ai_relations_only_move_isolated_articles():
    a = art("a", "x", event_id="evt-a")
    b = art("b", "y", "s2", event_id="evt-b")
    c = art("c", "z", "s3", event_id="evt-a")
    arts = {x.id: x for x in (a, b, c)}
    moved = apply_ai_relations(arts, {"b": "evt-a", "a": "evt-b", "c": "evt-inexistente"})
    assert moved == 1 and b.event_id == "evt-a" and a.event_id == "evt-a"


def test_off_topic_articles_are_excluded_from_events():
    a = art("a", "Bread recipes", off_topic=True, event_id="evt-a")
    assert build_events([a]) == []


def test_cross_portal_grouping_with_different_wording():
    a = art("a", "Alemanha tem ameaças de bomba em mais de 450 escolas em meio a falsos alertas", "g1", hours=3, category="mundo")
    b = art("b", "Centenas de escolas na Alemanha e na Dinamarca recebem ameaças de bomba por email", "folha", hours=2, category="mundo")
    c = art("c", "Lula exonera ministro para votação do projeto de ajuste na Câmara", "g1", hours=2, category="politica")
    d = art("d", "Mesmo com ajuste, dólar deve seguir em alta enquanto Lula não indicar nome para a Fazenda", "folha", hours=1, category="politica")
    assign_events_by_title([a, b, c, d], [])
    assert a.event_id == b.event_id
    assert c.event_id != d.event_id


def test_drop_repeated_caption_prefix():
    from backend.collectors.normalization import drop_repeated_prefix
    t = "Lula durante live Ricardo Stuckert/PR Lula durante live Ricardo Stuckert/PR O presidente se reuniu hoje."
    assert drop_repeated_prefix(t) == "O presidente se reuniu hoje."
    assert drop_repeated_prefix("Texto normal sem repetição nenhuma aqui.") == "Texto normal sem repetição nenhuma aqui."


def test_exclusion_filter_drops_promotions():
    from backend.collectors.normalization import keyword_pattern
    p = keyword_pattern(["cupom", "menor pre[cç]o", "mega-sena"])
    assert p.search("Black Magalu: Edge 70 tem o menor preço que já vimos")
    assert p.search("Mega-Sena acumula e prêmio vai a R$ 65 milhões")
    assert not p.search("Câmara aprova ajuste fiscal")
