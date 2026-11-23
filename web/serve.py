"""Prévia web do FAROL + distribuição do APK pela rede local.

    python web/serve.py            # http://localhost:8080 e http://<IP-do-PC>:8080 (celular no mesmo Wi-Fi)

Segurança: NÃO serve a pasta do repositório (que contém o .env com a chave).
Monta uma pasta temporária só com a página, cópias dos JSON públicos e o APK.
"""
from __future__ import annotations

import argparse
import functools
import http.server
import shutil
import socket
import tempfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA_SETS = {"local": ROOT / "data-local", "dev": ROOT / "data-dev"}
APK = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"


def build_site() -> Path:
    site = Path(tempfile.mkdtemp(prefix="farol-web-"))
    shutil.copy(ROOT / "web" / "index.html", site / "index.html")
    for name, src in DATA_SETS.items():
        for rel in ("latest.json", "trends/latest.json"):
            if (src / rel).exists():
                (site / name / rel).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy(src / rel, site / name / rel)
    if APK.exists():
        shutil.copy(APK, site / "farol.apk")
    return site


def lan_ip() -> str:
    with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
        try:
            s.connect(("10.255.255.255", 1))  # não envia nada; só descobre a interface de saída
            return s.getsockname()[0]
        except OSError:
            return "127.0.0.1"


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {**http.server.SimpleHTTPRequestHandler.extensions_map,
                      ".apk": "application/vnd.android.package-archive", ".json": "application/json"}


def main() -> None:
    p = argparse.ArgumentParser()
    p.add_argument("--port", type=int, default=8080)
    args = p.parse_args()
    site = build_site()
    print(f"Pasta servida (somente arquivos públicos): {site}")
    print(f"  Neste PC:  http://localhost:{args.port}")
    print(f"  Celular:   http://{lan_ip()}:{args.port}   (mesmo Wi-Fi)")
    print(f"  APK:       {'disponível em /farol.apk' if (site / 'farol.apk').exists() else 'não encontrado (rode gradlew assembleRelease)'}")
    server = http.server.ThreadingHTTPServer(("0.0.0.0", args.port), functools.partial(Handler, directory=str(site)))
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
