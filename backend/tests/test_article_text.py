from datetime import datetime, timezone

from backend.ai.mock_client import MockGeminiClient
from backend.ai.news_processor import AiUsage, summarize_articles
from backend.collectors.article_text import extract_text
from backend.models.article import Article

BODY = " ".join(["O governo anunciou nesta quarta-feira um pacote de R$ 3 bilhões para obras de saneamento."] * 6)


def test_extract_text_from_paragraphs_and_ld_json():
    html = f"""<html><head><script type="application/ld+json">
    {{"@type": "NewsArticle", "articleBody": "{BODY}"}}</script></head>
    <body><nav>Menu</nav><article><p>Leia também: outra coisa qualquer que não interessa aqui de jeito nenhum.</p>
    <p>{BODY}</p></article></body></html>"""
    text = extract_text(html, "https://exemplo.com.br/noticia")
    assert "R$ 3 bilhões" in text
    assert "Leia também" not in text


def _article(i: int, summary: str | None = None, ai: bool = False) -> Article:
    now = datetime.now(timezone.utc)
    return Article(
        id=f"a{i}", event_id=f"e{i}", original_title=f"Título {i}", source_id="s", source_name="Fonte",
        source_type="press", source_url=f"https://exemplo.com.br/{i}", collected_at=now, published_at=now,
        language="pt", excerpt="Chamada curta.", category="brasil", summary=summary, ai_processed=ai,
    )


def test_summary_is_rewritten_only_with_article_text():
    usage = AiUsage({}, datetime.now(timezone.utc), daily_limit=10, run_limit=10)
    with_body, without_body, analysed = _article(1, "Resumo raso antigo."), _article(2, "Resumo raso antigo."), _article(3, "Análise.", ai=True)
    done, stop = summarize_articles(
        MockGeminiClient(), [with_body, without_body, analysed], usage, batch_size=10, daily_limit=10, run_limit=10,
        bodies={"a1": BODY, "a3": BODY},
    )
    assert stop is None and done == 2
    assert with_body.summary != "Resumo raso antigo." and with_body.digest_version == 2
    assert without_body.summary == "Resumo raso antigo." and without_body.digest_version == 0
    assert analysed.digest_version == 2  # análise completa feita com a chamada também ganha o resumo do texto
    assert with_body.rich_attempts == 1
