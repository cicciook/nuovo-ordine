#!/usr/bin/env python3
from pathlib import Path
import argparse
import re

SUPPRESSOR_IDS = {
    "maxstuff:supressed_brake",
    "atea:muzzle_hybrid46m",
    "atea:muzzle_omega300",
    "atea:muzzle_osprey45",
    "atea:muzzle_osprey9",
    "atea:muzzle_rotex5i",
    "atea:muzzle_salvo12",
    "atea:muzzle_ultra5",
    "atea:muzzle_ultra50",
    "sfms:muzzle_sl_hvq",
    "sfms:muzzle_sl_knight",
    "sfms:muzzle_sl_sass1",
    "sfms:muzzle_sl_sass2",
}

def attachment_id(line):
    m = re.search(r'AttachmentId:"([^"]+)"', line)
    return m.group(1) if m else None

def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--base-weapons", required=True)
    ap.add_argument("--base-ammo", required=True)
    ap.add_argument("--out-weapons", required=True)
    ap.add_argument("--out-ammo", required=True)
    args = ap.parse_args()

    weapons = []
    changed = []
    for raw in Path(args.base_weapons).read_text("utf-8").splitlines():
        if not raw.strip():
            continue
        parts = raw.split("\t")
        aid = attachment_id(raw)
        if aid in SUPPRESSOR_IDS and len(parts) == 5:
            old = int(parts[1])
            parts[1] = str(max(old, 5))
            raw = "\t".join(parts)
            changed.append((aid, old, int(parts[1])))
        weapons.append(raw)

    Path(args.out_weapons).write_text("\n".join(weapons) + "\n", encoding="utf-8")
    ammo = Path(args.base_ammo).read_text("utf-8")
    Path(args.out_ammo).write_text(ammo if ammo.endswith("\n") else ammo + "\n", encoding="utf-8")

    assert len(changed) == len(SUPPRESSOR_IDS), (len(changed), sorted(SUPPRESSOR_IDS - {x[0] for x in changed}))
    print(f"Weapon rows: {len(weapons)}")
    print(f"Suppressor addon weights increased: {len(changed)}")
    for aid, old, new in changed:
        print(f"  {aid}: {old} -> {new}")

if __name__ == "__main__":
    main()
