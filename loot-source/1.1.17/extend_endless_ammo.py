"""Extend Lootr Tactical 1.1.16 with verified Endless Ammo TACZ definitions."""
import json
import re
import sys
import zipfile
from pathlib import Path

HERE = Path(__file__).resolve().parent
PATTERN = re.compile(r"(?:^|/)data/([^/]+)/index/ammo/([^/]+)\.json$")


def build(base_jar, endless_jar):
    with zipfile.ZipFile(base_jar) as source:
        lines = source.read("META-INF/loot-update-source/AMMO.tsv").decode().splitlines()
        report = json.loads(source.read("META-INF/loot-update-source/ADDON_REPORT.json"))
    existing = set(re.findall(r'AmmoId:"([^"]+)"', "\n".join(lines)))
    with zipfile.ZipFile(endless_jar) as addon:
        indexed = {f"{m.group(1)}:{m.group(2)}" for name in addon.namelist()
                   if (m := PATTERN.search(name))}
    added = sorted(indexed - existing - {"tacz:40mm", "tacz:rpg_rocket"})
    lines.extend(f'tacz:ammo\t3\t20\t32\t{{AmmoId:"{rid}"}}' for rid in added)
    (HERE / "AMMO.tsv").write_text("\n".join(lines) + "\n", encoding="utf-8")
    report["addon_ammo"] += len(added)
    report["total_ammo_rows"] = len(lines)
    report["packs"]["Endless Ammo 2.0"] = {
        "indexed_ammo": len(indexed), "new_ammo_rows": len(added),
        "already_present": len(indexed & existing),
    }
    report["endless_ammo_indexed_ids"] = sorted(indexed)
    text = json.dumps(report, ensure_ascii=False, indent=2) + "\n"
    (HERE / "ADDON_REPORT.json").write_text(text, encoding="utf-8")
    (HERE / "catalog-report.json").write_text(text, encoding="utf-8")
    return len(indexed), len(added), len(lines)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        sys.exit("Usage: extend_endless_ammo.py Lootr-1.1.16.jar EndlessAmmo-2.0.jar")
    print(build(sys.argv[1], sys.argv[2]))
