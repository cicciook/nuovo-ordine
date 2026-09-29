"""Generate the server's TACZ selector from verified addon archives.

Usage: python3 generate_addons.py ARIPS.zip Maxstuff.jar Mobius.zip EndlessAmmo.jar
The four archives are inputs, never redistributed in the Armeria JAR.
"""
import json
import re
import sys
import zipfile
from pathlib import Path

LABELS = ("ARIPS 1.3.0", "MaxStuff Legacy 1.8.3", "MS-Mobius 1.5.8", "Endless Ammo 2.0")
KINDS = {"guns": "Armi TACZ", "attachments": "Accessori TACZ", "ammo": "Munizioni TACZ"}
INDEX = re.compile(r"(?:^|/)data/([^/]+)/index/(guns|attachments|ammo)/([^/]+)\.json$")


def generate(paths):
    entries = []
    for path, pack in zip(paths, LABELS):
        with zipfile.ZipFile(path) as archive:
            for name in archive.namelist():
                match = INDEX.search(name)
                if not match:
                    continue
                namespace, kind, ident = match.groups()
                item_id = f"{namespace}:{ident}"
                raw = archive.read(name).decode("utf-8-sig", errors="replace")
                display_key = re.search(r'"name"\s*:\s*"([^"]+)"', raw)
                row = {"id": item_id, "pack": pack, "kind": kind,
                       "category": KINDS[kind],
                       "name": ident.replace("_", " ").replace("-", " ").upper(),
                       "translation": display_key.group(1) if display_key else ""}
                if kind == "guns":
                    data = re.search(r'"data"\s*:\s*"([^":]+):([^":]+)"', raw)
                    row["mode"] = "SEMI"
                    if data:
                        data_path = name.split("/index/guns/")[0] + "/data/guns/" + data.group(2) + ".json"
                        try:
                            content = archive.read(data_path).decode("utf-8-sig", errors="replace")
                            mode = re.search(r'"fire_mode"\s*:\s*\[\s*"([A-Za-z_]+)"', content)
                            if mode:
                                row["mode"] = mode.group(1).upper()
                        except KeyError:
                            pass
                entries.append(row)
    unique = {(row["kind"], row["id"]): row for row in entries}
    return sorted(unique.values(), key=lambda row: (row["pack"], row["kind"], row["id"]))


if __name__ == "__main__":
    if len(sys.argv) != 5:
        sys.exit(__doc__)
    output = Path(__file__).parent / "resources" / "armeria_addons.json"
    data = generate(sys.argv[1:])
    output.write_text(json.dumps(data, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
    print(output, len(data), "entries")
