"""Cliente mínimo e desacoplado da Gemini API (REST).

- A chave vai apenas no cabeçalho x-goog-api-key (nunca na URL, em logs ou em erros).
- Sem fallback para APIs pagas. Cota esgotada => QuotaExceeded, e o pipeline segue sem IA.
"""
from __future__ import annotations

import json
import logging
import random
import re
import time
from typing import Callable, Protocol

import requests

log = logging.getLogger(__name__)
API_BASE = "https://generativelanguage.googleapis.com/v1beta"


class GeminiError(Exception):
    """Erro genérico e recuperável (o lote é marcado como falho e o pipeline segue)."""


class QuotaExceeded(GeminiError):
    """Cota gratuita atingida: nenhuma nova chamada nesta execução (nem no dia, se daily=True)."""

    def __init__(self, message: str, *, daily: bool = True):
        super().__init__(message)
        self.daily = daily


class FatalGeminiError(GeminiError):
    """Chave inválida, modelo inexistente etc.: interrompe a IA nesta execução."""


class SchemaRejected(GeminiError):
    """A API recusou o responseJsonSchema (o chamador repete sem schema)."""


class ServiceUnavailable(GeminiError):
    """Todos os modelos sobrecarregados: problema transitório do serviço, não do conteúdo."""


class ModelUnavailable(GeminiError):
    """Modelo sobrecarregado (503 persistente) ou descontinuado (404): tenta o próximo modelo gratuito."""


class JsonGenerator(Protocol):
    model: str

    def generate_json(self, system: str, prompt: str, schema: dict | None = None) -> dict: ...


def _parse_retry_delay(details: list) -> float | None:
    for d in details or []:
        if str(d.get("@type", "")).endswith("RetryInfo"):
            m = re.match(r"([\d.]+)s", str(d.get("retryDelay", "")))
            if m:
                return float(m.group(1))
    return None


def _is_daily_quota(details: list, message: str) -> bool:
    for d in details or []:
        for v in d.get("violations", []) or []:
            if "PerDay" in str(v.get("quotaId", "")) or "per_day" in str(v.get("quotaMetric", "")).lower():
                return True
    return "per day" in message.lower()


class GeminiClient:
    def __init__(
        self,
        api_key: str,
        model: str,
        *,
        fallback_models: list[str] | None = None,
        exhausted_models: set[str] | None = None,
        session: requests.Session | None = None,
        timeout: int = 120,
        max_retries: int = 3,
        min_interval: float = 6.0,
        sleep: Callable[[float], None] = time.sleep,
        clock: Callable[[], float] = time.monotonic,
    ):
        if not api_key:
            raise FatalGeminiError("GEMINI_API_KEY não configurada")
        self._api_key = api_key
        self.model = model.removeprefix("models/")
        self.models = list(dict.fromkeys(
            [self.model] + [m.strip().removeprefix("models/") for m in (fallback_models or []) if m.strip()]
        ))
        # A cota gratuita é por modelo: os esgotados hoje são pulados até a cota reiniciar.
        self.exhausted_models: set[str] = set(exhausted_models or ())
        self._session = session or requests.Session()
        self._timeout = timeout
        self._max_retries = max_retries
        self._min_interval = min_interval
        self._sleep = sleep
        self._clock = clock
        self._last_call: float | None = None
        self.calls_made = 0

    def __repr__(self) -> str:  # nunca expõe a chave
        return f"GeminiClient(model={self.model!r})"

    def _throttle(self) -> None:
        if self._last_call is not None:
            wait = self._min_interval - (self._clock() - self._last_call)
            if wait > 0:
                self._sleep(wait)

    def generate_json(self, system: str, prompt: str, schema: dict | None = None) -> dict:
        """Tenta o modelo principal e, se sobrecarregado/descontinuado, os alternativos (todos gratuitos)."""
        errors: list[str] = []
        available = [m for m in self.models if m not in self.exhausted_models]
        if not available:
            raise QuotaExceeded("cota diária de todos os modelos gratuitos configurados atingida", daily=True)
        for model in available:
            try:
                result = self._generate_with(model, system, prompt, schema)
            except ModelUnavailable as exc:
                errors.append(f"{model}: {exc}")
                log.warning("Modelo %s indisponível (%s)", model, exc)
                continue
            except QuotaExceeded as exc:
                if not exc.daily:
                    raise  # limite por minuto: encerra a IA nesta execução
                self.exhausted_models.add(model)
                errors.append(f"{model}: cota diária")
                log.warning("Cota diária do modelo %s atingida; tentando o próximo modelo gratuito", model)
                continue
            if model != self.model:
                log.info("Usando modelo alternativo %s", model)
                # Promove o modelo que funcionou para evitar novas tentativas inúteis nesta execução.
                self.models.remove(model)
                self.models.insert(0, model)
                self.model = model
            return result
        if all(m in self.exhausted_models for m in self.models):
            raise QuotaExceeded("cota diária de todos os modelos gratuitos configurados atingida", daily=True)
        if errors and all("HTTP 404" in e for e in errors):
            raise FatalGeminiError(f"nenhum modelo disponível; ajuste GEMINI_MODEL ({'; '.join(errors)})")
        raise ServiceUnavailable(f"todos os modelos indisponíveis: {'; '.join(errors)}")

    def _generate_with(self, model: str, system: str, prompt: str, schema: dict | None) -> dict:
        body: dict = {
            "systemInstruction": {"parts": [{"text": system}]},
            "contents": [{"role": "user", "parts": [{"text": prompt}]}],
            "generationConfig": {
                "responseMimeType": "application/json",
                "temperature": 0.2,
                "maxOutputTokens": 16384,
            },
        }
        if schema is not None:
            body["generationConfig"]["responseJsonSchema"] = schema
        url = f"{API_BASE}/models/{model}:generateContent"
        headers = {"x-goog-api-key": self._api_key, "Content-Type": "application/json"}

        last = "erro desconhecido"
        for attempt in range(self._max_retries):
            self._throttle()
            self._last_call = self._clock()
            try:
                resp = self._session.post(url, headers=headers, json=body, timeout=self._timeout)
            except (requests.ConnectionError, requests.Timeout) as exc:
                last = type(exc).__name__
                self._backoff(attempt, last)
                continue
            # Contabiliza no orçamento toda resposta que não seja falha do servidor (5xx),
            # inclusive 429 — postura conservativa em relação à cota.
            if resp.status_code < 500:
                self.calls_made += 1

            if resp.status_code == 200:
                return self._extract_json(resp)

            err = self._error_payload(resp)
            status, message, details = resp.status_code, err.get("message", ""), err.get("details", [])
            short = f"HTTP {status} {err.get('status', '')}".strip()
            if status == 429:
                delay = _parse_retry_delay(details)
                daily = _is_daily_quota(details, message)
                if daily or delay is None or delay > 65 or attempt == self._max_retries - 1:
                    raise QuotaExceeded(f"cota {'diária' if daily else 'por minuto'} da Gemini atingida ({short})", daily=daily)
                log.warning("Limite por minuto atingido; aguardando %.0fs", delay)
                self._sleep(delay + 1)
                continue
            if status == 400 and schema is not None and ("schema" in message.lower() or "responseJsonSchema" in message):
                raise SchemaRejected(short)
            if status in (401, 403):
                raise FatalGeminiError(f"acesso negado pela Gemini API ({short}); verifique GEMINI_API_KEY")
            if status == 404:
                raise ModelUnavailable(short)
            if status in (500, 502, 503, 504):
                last = short
                self._backoff(attempt, last)
                continue
            raise GeminiError(f"{short}: {message[:160]}")
        if last.startswith("HTTP 503"):
            raise ModelUnavailable(f"sobrecarregado após {self._max_retries} tentativas ({last})")
        raise GeminiError(f"falhou após {self._max_retries} tentativas: {last}")

    def _backoff(self, attempt: int, reason: str) -> None:
        if attempt < self._max_retries - 1:
            # Picos de demanda do plano gratuito duram segundos: espera 5s, 10s, ...
            delay = 5.0 * (2 ** attempt) + random.uniform(0, 2)
            log.warning("Gemini: %s; nova tentativa em %.1fs", reason, delay)
            self._sleep(delay)

    @staticmethod
    def _error_payload(resp) -> dict:
        try:
            return resp.json().get("error", {}) or {}
        except ValueError:
            return {}

    @staticmethod
    def _extract_json(resp) -> dict:
        try:
            data = resp.json()
            cand = (data.get("candidates") or [{}])[0]
            parts = cand.get("content", {}).get("parts", [])
            text = "".join(p.get("text", "") for p in parts if not p.get("thought"))
        except (ValueError, AttributeError, IndexError) as exc:
            raise GeminiError(f"resposta inesperada: {type(exc).__name__}") from None
        if not text:
            raise GeminiError(f"resposta vazia (finishReason={cand.get('finishReason')})")
        text = text.strip()
        if text.startswith("```"):
            text = re.sub(r"^```(?:json)?\s*|\s*```$", "", text)
        try:
            return json.loads(text)
        except json.JSONDecodeError:
            raise GeminiError("a IA não retornou JSON válido") from None
