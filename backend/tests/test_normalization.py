

def test_unwrap_folha_redirect_dedups_across_sections():
    from backend.collectors.normalization import normalize_url, unwrap_redirect
    real = "https://www1.folha.uol.com.br/colunas/x/2026/11/texto.shtml"
    a = f"https://redir.folha.com.br/redir/online/ciencia/rss091/*{real}"
    b = f"https://redir.folha.com.br/redir/online/ambiente/rss091/*{real}"
    assert unwrap_redirect(a) == real and normalize_url(a) == normalize_url(b) == normalize_url(real)


def test_bad_translation_detection():
    from backend.collectors.normalization import is_bad_translation, looks_english
    assert looks_english("Making Sense of Chaotic Supernova Ejecta With the JWST")
    assert not looks_english("Entendendo os destroços caóticos de supernovas com o JWST")
    assert is_bad_translation("Google alternative Kagi launches an email service", "Google alternative Kagi launches an email service")
    assert not is_bad_translation("Welcome to elite impunity, Jensen Huang", "Bem-vindo à impunidade de elite, Jensen Huang")
    assert is_bad_translation("Anything", None)
