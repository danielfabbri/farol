"""Resumos de seção escritos fora do pipeline (ex.: pelo Claude no VS Code).

    python -m backend.briefing_cli export  > pedido.json     # material de cada seção (mesmo da IA automática)
    python -m backend.briefing_cli import respostas.json --author "Claude (VS Code)"

O arquivo de respostas tem o formato {"politica": {"headline", "overview", "points": [{"text", "refs"}], "watch"}, ...}.
A importação valida tudo com as mesmas regras da Gemini (BriefOutput), descarta tópicos sem origem e
grava em data/briefs/latest.json e em data/latest.json. O pipeline reaproveita esses resumos enquanto
os principais acontecimentos da seção não mudarem.
"""
from __future__ import annotations

import argparse
import json
import sys
from pathlib import Path

from backend.ai.section_briefer import BRIEF_SYSTEM_PROMPT, brief_items, signature, to_brief, top_events
from backend.collectors.normalization import utcnow
from backend.config.settings import REPO_ROOT
from backend.models.analysis import BriefOutput
from backend.models.article import Article
from backend.models.edition import Event, LatestFeed
from backend.storage.json_repository import JsonRepository


def _sections_material(latest: dict) -> dict:
    events = [Event.model_validate(e) for e in latest["events"]]
    articles = {a["id"]: Article.model_validate(a) for a in latest["articles"]}
    out = {}
    for sec in latest.get("sections", []):
        sec_events = [e for e in events if e.category == sec["id"]]
        if len(sec_events) < 2:
            continue
        top = top_events(sec_events)
        items, refmap = brief_items(top, articles)
        out[sec["id"]] = {"label": sec["label"], "signature": signature(top), "items": items, "_refmap": refmap}
    return out


def main(argv: list[str] | None = None) -> int:
    sys.stdout.reconfigure(encoding="utf-8")
    p = argparse.ArgumentParser()
    p.add_argument("command", choices=["export", "import"])
    p.add_argument("file", nargs="?")
    p.add_argument("--author", default="Claude (VS Code)")
    p.add_argument("--data-dir", default=str(REPO_ROOT / "data"))
    args = p.parse_args(argv)

    repo = JsonRepository(Path(args.data_dir))
    latest = repo.read_json(repo.latest_path)
    if not latest:
        print("data/latest.json não encontrado", file=sys.stderr)
        return 1
    material = _sections_material(latest)

    if args.command == "export":
        doc = {
            "instrucoes": BRIEF_SYSTEM_PROMPT,
            "edicao": latest["edition"]["label"],
            "secoes": {k: {kk: vv for kk, vv in v.items() if kk != "_refmap"} for k, v in material.items()},
        }
        print(json.dumps(doc, ensure_ascii=False, indent=1))
        return 0

    answers = json.loads(Path(args.file).read_text(encoding="utf-8"))
    store = repo.read_json(repo.root / "briefs" / "latest.json", {})
    now = utcnow()
    ok = 0
    for sid, raw in answers.items():
        if sid not in material:
            print(f"- {sid}: seção sem material nesta edição, ignorada")
            continue
        try:
            brief = to_brief(BriefOutput.model_validate(raw), material[sid]["_refmap"], now=now,
                             model=None, author=args.author, sig=material[sid]["signature"])
        except Exception as exc:  # noqa: BLE001
            print(f"- {sid}: inválido ({exc})")
            continue
        store[sid] = brief.model_dump(mode="json")
        for sec in latest["sections"]:
            if sec["id"] == sid:
                sec["brief"] = store[sid]
        ok += 1
        print(f"- {sid}: ok ({len(brief.points)} tópicos)")
    LatestFeed.model_validate(latest)
    repo.write_json(repo.root / "briefs" / "latest.json", store)
    repo.save_latest(latest)
    print(f"{ok} resumo(s) importado(s).")
    return 0 if ok else 1


if __name__ == "__main__":
    sys.exit(main())
