#!/usr/bin/env python3
"""Audit minimal des points d'intégration utilisés par Dams Tunnel."""

from __future__ import annotations

import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
APP = ROOT / "V2rayNG"

CHECKS = [
    ("compileSdk", APP / "app/build.gradle.kts", r"compileSdk\s*=\s*37"),
    ("minSdk", APP / "app/build.gradle.kts", r"minSdk\s*=\s*24"),
    ("applicationId", APP / "app/build.gradle.kts", r'applicationId\s*=\s*"com\.v2ray\.ang"'),
    ("hev package name", ROOT / "compile-hevtun.sh", r"PKGNAME=com/v2ray/ang/service"),
    ("VPN service", APP / "app/src/main/java/com/v2ray/ang/service/CoreVpnService.kt", r"class CoreVpnService"),
    ("VPN exclusion", APP / "app/src/main/java/com/v2ray/ang/service/CoreVpnService.kt", r"addDisallowedApplication"),
    ("VPN protect", APP / "app/src/main/java/com/v2ray/ang/service/CoreVpnService.kt", r"protect\("),
    ("hev stats", APP / "app/src/main/java/com/v2ray/ang/service/TProxyService.kt", r"TProxyGetStats"),
    ("custom JSON", APP / "app/src/main/java/com/v2ray/ang/core/CoreConfigManager.kt", r"buildV2rayCustomConfig"),
    ("SOCKS port", APP / "app/src/main/java/com/v2ray/ang/handler/SettingsManager.kt", r"getSocksPort"),
    ("import links", APP / "app/src/main/java/com/v2ray/ang/handler/AngConfigManager.kt", r"importBatchConfig"),
    ("MMKV", APP / "app/src/main/java/com/v2ray/ang/handler/MmkvManager.kt", r"MMKV"),
]


def main() -> int:
    rows: list[str] = ["# Repères v2rayNG (audit automatique)", "", "| Repère | Fichier | Lignes |", "|---|---|---:|"]
    missing = 0
    for label, path, pattern in CHECKS:
        relative = path.relative_to(ROOT)
        if not path.exists():
            rows.append(f"| {label} | `{relative}` | FICHIER ABSENT |")
            missing += 1
            continue
        lines = path.read_text(encoding="utf-8", errors="replace").splitlines()
        hits = [str(index) for index, line in enumerate(lines, 1) if re.search(pattern, line)]
        if not hits:
            missing += 1
        rows.append(f"| {label} | `{relative}` | {', '.join(hits[:8]) or 'ABSENT'} |")
    rows += ["", f"Repères absents : {missing}", ""]
    output = "\n".join(rows)
    print(output)
    if len(sys.argv) > 1:
        Path(sys.argv[1]).write_text(output, encoding="utf-8")
    return 1 if missing else 0


if __name__ == "__main__":
    raise SystemExit(main())
