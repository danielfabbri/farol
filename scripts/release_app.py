"""Publica uma nova versão do app para a atualização pelo próprio app (Ajustes → Atualizar app).

    python scripts/release_app.py --notes "O que mudou nesta versão"

1. Lê versionCode/versionName de android/app/build.gradle.kts (aumente-os antes).
2. Gera o APK de release (assinado com a chave desta máquina — a mesma do app instalado).
3. Copia para releases/farol.apk e escreve releases/version.json com tamanho e SHA-256.
Depois, faça commit e push: o app baixa de https://raw.githubusercontent.com/<repo>/main/releases/.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import shutil
import subprocess
import sys
from datetime import datetime, timezone
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ANDROID = ROOT / "android"
APK = ANDROID / "app" / "build" / "outputs" / "apk" / "release" / "app-release.apk"
OUT = ROOT / "releases"


def main() -> int:
    p = argparse.ArgumentParser()
    p.add_argument("--notes", default="")
    p.add_argument("--skip-build", action="store_true")
    args = p.parse_args()

    gradle = (ANDROID / "app" / "build.gradle.kts").read_text(encoding="utf-8")
    code = int(re.search(r"versionCode\s*=\s*(\d+)", gradle).group(1))
    name = re.search(r'versionName\s*=\s*"([^"]+)"', gradle).group(1)

    current = OUT / "version.json"
    if current.exists() and json.loads(current.read_text(encoding="utf-8"))["version_code"] >= code:
        print(f"versionCode {code} não é maior que o já publicado. Aumente versionCode em build.gradle.kts.")
        return 1

    if not args.skip_build:
        gradlew = ANDROID / ("gradlew.bat" if sys.platform == "win32" else "gradlew")
        subprocess.run([str(gradlew), "assembleRelease", "--console=plain", "-q"], cwd=ANDROID, check=True)

    OUT.mkdir(exist_ok=True)
    shutil.copy(APK, OUT / "farol.apk")
    data = (OUT / "farol.apk").read_bytes()
    info = {
        "version_code": code,
        "version_name": name,
        "apk": "releases/farol.apk",
        "sha256": hashlib.sha256(data).hexdigest(),
        "size": len(data),
        "notes": args.notes,
        "published_at": datetime.now(timezone.utc).replace(microsecond=0).isoformat().replace("+00:00", "Z"),
    }
    current.write_text(json.dumps(info, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Versão {name} ({code}) pronta em releases/. Faça commit e push para publicar.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
