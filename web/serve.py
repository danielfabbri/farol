"""Prévia web do FAROL + distribuição do APK pela rede local.

    python web/serve.py            # http://localhost:8080 e http://<IP-do-PC>:8080 (celular no mesmo Wi-Fi)

Segurança: NÃO serve a pasta do repositório (que contém o .env com a chave).
Monta uma pasta temporária só com a página, cópias dos JSON públicos e o APK.
"""
from __future__ import annotations

import argparse
import functools
import http.server
import json
import os
import shutil
import socket
import subprocess
import sys
import tempfile
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DATA_SETS = {"local": ROOT / "data-local", "dev": ROOT / "data-dev"}
APK = ROOT / "android" / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"


def copy_data(site: Path) -> None:
    for name, src in DATA_SETS.items():
        for rel in ("latest.json", "trends/latest.json"):
            if (src / rel).exists():
                (site / name / rel).parent.mkdir(parents=True, exist_ok=True)
                shutil.copy(src / rel, site / name / rel)


def build_site() -> Path:
    site = Path(tempfile.mkdtemp(prefix="farol-web-"))
    shutil.copy(ROOT / "web" / "index.html", site / "index.html")
    copy_data(site)
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


SCAN_LOCK = threading.Lock()


class Handler(http.server.SimpleHTTPRequestHandler):
    extensions_map = {**http.server.SimpleHTTPRequestHandler.extensions_map,
                      ".apk": "application/vnd.android.package-archive", ".json": "application/json"}

    def _json(self, code: int, payload: dict) -> None:
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_POST(self) -> None:  # noqa: N802
        if self.path.rstrip("/") != "/api/varrer":
            return self._json(404, {"erro": "não encontrado"})
        # Só o próprio PC pode disparar a varredura (ela usa a chave do .env e consome cota).
        if self.client_address[0] not in ("127.0.0.1", "::1"):
            return self._json(403, {"erro": "a varredura local só pode ser iniciada neste computador"})
        if not SCAN_LOCK.acquire(blocking=False):
            return self._json(409, {"erro": "já existe uma varredura em andamento"})
        try:
            env = {**os.environ, "FAROL_DATA_DIR": str(DATA_SETS["local"]), "PYTHONIOENCODING": "utf-8"}
            proc = subprocess.run(
                [sys.executable, "-m", "backend.main", "--force"], cwd=ROOT, env=env,
                capture_output=True, text=True, encoding="utf-8", timeout=900,
            )
            copy_data(Path(self.directory))
            tail = [l for l in proc.stdout.splitlines() if "Concluído" in l or "IA:" in l]
            self._json(200 if proc.returncode in (0, 1) else 500, {"ok": proc.returncode == 0, "resumo": tail})
        except subprocess.TimeoutExpired:
            self._json(504, {"erro": "a varredura passou de 15 minutos"})
        finally:
            SCAN_LOCK.release()


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
