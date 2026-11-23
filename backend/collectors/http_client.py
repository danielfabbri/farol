"""HTTP com requisições condicionais (ETag/Last-Modified) e retentativas com backoff exponencial."""
from __future__ import annotations

import logging
import random
import time
from dataclasses import dataclass
from typing import Callable

import requests

log = logging.getLogger(__name__)

# Identificação genérica: não envia dados pessoais nem do projeto a serviços externos.
# O prefixo "Mozilla/5.0 (compatible; ...)" é a convenção de leitores de feed (alguns sites,
# como o MIT Technology Review, recusam com 403 agentes sem ele); continua identificando um bot.
USER_AGENT = "Mozilla/5.0 (compatible; FarolRSS/0.1; personal RSS reader)"
RETRYABLE_STATUS = {408, 425, 429, 500, 502, 503, 504}


class FetchError(Exception):
    pass


@dataclass
class FetchResult:
    status: int
    content: bytes = b""
    etag: str | None = None
    last_modified: str | None = None

    @property
    def not_modified(self) -> bool:
        return self.status == 304


def fetch(
    url: str,
    session: requests.Session,
    *,
    etag: str | None = None,
    last_modified: str | None = None,
    timeout: int = 20,
    retries: int = 3,
    backoff: float = 1.5,
    sleep: Callable[[float], None] = time.sleep,
) -> FetchResult:
    headers = {
        "User-Agent": USER_AGENT,
        "Accept": "application/rss+xml, application/atom+xml, application/xml;q=0.9, text/xml;q=0.8, */*;q=0.5",
    }
    if etag:
        headers["If-None-Match"] = etag
    if last_modified:
        headers["If-Modified-Since"] = last_modified

    last_error = "erro desconhecido"
    for attempt in range(retries):
        try:
            resp = session.get(url, headers=headers, timeout=timeout)
        except (requests.ConnectionError, requests.Timeout) as exc:
            last_error = type(exc).__name__
        else:
            if resp.status_code == 304:
                return FetchResult(status=304, etag=etag, last_modified=last_modified)
            if resp.status_code < 400:
                return FetchResult(
                    status=resp.status_code,
                    content=resp.content,
                    etag=resp.headers.get("ETag"),
                    last_modified=resp.headers.get("Last-Modified"),
                )
            if resp.status_code not in RETRYABLE_STATUS:
                raise FetchError(f"HTTP {resp.status_code}")
            last_error = f"HTTP {resp.status_code}"
        if attempt < retries - 1:
            delay = backoff * (2 ** attempt) + random.uniform(0, 0.5)
            log.info("Retentativa %d/%d em %.1fs (%s)", attempt + 2, retries, delay, last_error)
            sleep(delay)
    raise FetchError(f"falhou após {retries} tentativas: {last_error}")
