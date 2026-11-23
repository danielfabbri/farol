"""Normalização de textos, URLs e datas antes do armazenamento e do envio à IA."""
from __future__ import annotations

import calendar
import hashlib
import html
import re
import unicodedata
from datetime import datetime, timezone
from urllib.parse import parse_qsl, urlencode, urlsplit, urlunsplit

_TAG_RE = re.compile(r"<[^>]+>")
_SCRIPT_RE = re.compile(r"<(script|style)[^>]*>.*?</\1>", re.IGNORECASE | re.DOTALL)
_WS_RE = re.compile(r"\s+")
_TRACKING_PARAMS = {
    "fbclid", "gclid", "mc_cid", "mc_eid", "ref", "ref_src", "source", "cmpid", "ocid",
    "guccounter", "guce_referrer", "guce_referrer_sig", "_hsenc", "_hsmi", "mkt_tok",
}
_ARXIV_RE = re.compile(r"arxiv\.org/(?:abs|pdf|html)/([0-9]{4}\.[0-9]{4,5}|[a-z\-]+/[0-9]{7})(?:v[0-9]+)?", re.I)


def clean_text(value: str | None) -> str:
    """Remove HTML, decodifica entidades e colapsa espaços."""
    if not value:
        return ""
    text = _SCRIPT_RE.sub(" ", value)
    text = _TAG_RE.sub(" ", text)
    text = html.unescape(text)
    text = unicodedata.normalize("NFC", text).replace(" ", " ")
    return _WS_RE.sub(" ", text).strip()


_ESCAPED_CHAR_RE = re.compile(r"(?:\\|#)u?([0-9a-fA-F]{4})")


def fix_unicode_escapes(text: str) -> str:
    """Corrige acentos que alguns modelos devolvem como código ("c#00f3digo", "#u00e0", "\\u00e7").

    Só converte letras latinas acentuadas (U+00A0–U+017F), para não tocar em hashtags como "#2024".
    """
    if not text or ("#" not in text and "\\" not in text):
        return text

    def repl(m: re.Match) -> str:
        code = int(m.group(1), 16)
        return chr(code) if 0xA0 <= code <= 0x17F else m.group(0)

    return _ESCAPED_CHAR_RE.sub(repl, text)


_CORRUPT_RE = re.compile(
    r"([áéíóúâêôãõçà])\1"            # acento duplicado: "Inteligêêode"
    r"|[A-Za-zÀ-ÿ]#[0-9a-fA-F]{2}"     # resto de código: "c#00f3"
    r"|\\u[0-9a-fA-F]{4}"              # escape literal
    r"|[Ѐ-ӿ֐-ۿ぀-ヿ一-鿿]"  # alfabetos inesperados
    r"|�"                         # caractere de substituição
)


def looks_corrupted(text: str | None) -> bool:
    """Heurística para texto gerado com defeito (modelos "lite" às vezes corrompem acentos)."""
    return bool(text) and bool(_CORRUPT_RE.search(text))


def truncate(text: str, limit: int) -> str:
    if len(text) <= limit:
        return text
    cut = text[:limit].rsplit(" ", 1)[0].rstrip(" ,.;:")
    return cut + "…"


def normalize_url(url: str) -> str:
    """URL canônica para deduplicação: sem rastreamento, fragmento, www ou barra final."""
    url = (url or "").strip()
    m = _ARXIV_RE.search(url)
    if m:
        return f"https://arxiv.org/abs/{m.group(1).lower()}"
    parts = urlsplit(url)
    scheme = "https" if parts.scheme in ("http", "https", "") else parts.scheme
    host = parts.netloc.lower().removeprefix("www.")
    if host.endswith(":443") or host.endswith(":80"):
        host = host.rsplit(":", 1)[0]
    path = re.sub(r"/{2,}", "/", parts.path or "/")
    if len(path) > 1:
        path = path.rstrip("/")
    query = [
        (k, v) for k, v in parse_qsl(parts.query, keep_blank_values=False)
        if not k.lower().startswith("utm_") and k.lower() not in _TRACKING_PARAMS
    ]
    query.sort()
    return urlunsplit((scheme, host, path, urlencode(query), ""))


def article_id(normalized_url: str) -> str:
    return hashlib.sha1(normalized_url.encode("utf-8")).hexdigest()[:16]


def struct_to_datetime(value) -> datetime | None:
    """Converte time.struct_time (UTC, como entregue pelo feedparser) em datetime UTC."""
    if not value:
        return None
    try:
        return datetime.fromtimestamp(calendar.timegm(value), tz=timezone.utc)
    except (TypeError, ValueError, OverflowError):
        return None


def utcnow() -> datetime:
    return datetime.now(timezone.utc).replace(microsecond=0)


def keyword_pattern(keywords: list[str]) -> re.Pattern:
    """Padrão com fronteira de palavra; as palavras-chave podem conter regex simples."""
    alts = "|".join(f"(?:{k})" for k in keywords if k)
    return re.compile(rf"(?<![\w-])(?:{alts})(?![\w])", re.IGNORECASE)
