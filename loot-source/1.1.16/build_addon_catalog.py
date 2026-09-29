#!/usr/bin/env python3
import argparse
import json
import re
import zipfile
from pathlib import Path

WEIGHTS = {
    "pistol": 8,
    "smg": 6,
    "shotgun": 5,
    "rifle": 4,
    "mg": 2,
    "machine_gun": 2,
    "sniper": 2,
    "rpg": 1,
    "launcher": 1,
}

# Mantiene le esclusioni già richieste per il loot tattico: niente lanciatori/RPG
# e niente cecchini pesanti degli addon.
EXCLUDED = {
    "maxstuff:ai_awp": "heavy_sniper",
    "maxstuff:ai_aws": "heavy_sniper",
    "maxstuff:ar338": "heavy_sniper",
    "maxstuff:can_cannon": "launcher",
    "maxstuff:excaliber": "heavy_sniper",
    "maxstuff:gm6_lynx": "heavy_sniper",
    "maxstuff:m320t": "launcher",
    "maxstuff:m82a2": "heavy_sniper",
    "maxstuff:mk18_mjolnir": "heavy_sniper",
    "maxstuff:mrad": "heavy_sniper",
    "sfms:ar50": "heavy_sniper",
    "sfms:m109": "heavy_sniper",
    "sfms:m200": "heavy_sniper",
    "sfms:m202": "launcher",
    "sfms:mgl320": "launcher",
    "sfms:mgl40": "launcher",
    "sfms:mgl416": "launcher",
}

EXCLUDED_AMMO = {"tacz:40mm", "tacz:rpg_rocket"}


def text(zf, name):
    return zf.read(name).decode("utf-8-sig", errors="replace")


def field(raw, name, default=""):
    match = re.search(r'"' + re.escape(name) + r'"\s*:\s*"([^"]+)"', raw)
    return match.group(1) if match else default


def first_fire_mode(raw):
    match = re.search(r'"fire_mode"\s*:\s*\[\s*"([^"]+)"', raw)
    return (match.group(1) if match else "semi").upper()


def iter_index(zf, kind):
    seen = set()
    pattern = re.compile(r'(?:^|/)data/([^/]+)/index/' + re.escape(kind) + r'/([^/]+)\.json$')
    for name in zf.namelist():
        match = pattern.search(name)
        if not match:
            continue
        rid = f"{match.group(1)}:{match.group(2)}"
        if rid in seen:
            continue
        seen.add(rid)
        yield name, match.group(1), match.group(2), rid


def scan_pack(path, label):
    guns = []
    attachments = []
    excluded = []
    indexed_ammo = set()

    with zipfile.ZipFile(path) as zf:
        names = set(zf.namelist())

        for name, ns, ident, rid in iter_index(zf, "guns"):
            raw_index = text(zf, name)
            gun_type = field(raw_index, "type", "")
            data_ref = field(raw_index, "data", "")
            mode = "SEMI"
            ammo = ""
            if ":" in data_ref:
                data_ident = data_ref.split(":", 1)[1]
                data_path = f"{name.split('/index/guns/')[0]}/data/guns/{data_ident}.json"
                if data_path in names:
                    raw_data = text(zf, data_path)
                    mode = first_fire_mode(raw_data)
                    ammo = field(raw_data, "ammo", "")

            if rid in EXCLUDED:
                excluded.append({"id": rid, "reason": EXCLUDED[rid], "ammo": ammo})
                continue

            guns.append({
                "id": rid,
                "type": gun_type,
                "mode": mode,
                "ammo": ammo,
            })

        for _, _, _, rid in iter_index(zf, "attachments"):
            attachments.append(rid)
        for _, _, _, rid in iter_index(zf, "ammo"):
            indexed_ammo.add(rid)

    return {
        "label": label,
        "guns": guns,
        "attachments": attachments,
        "indexed_ammo": sorted(indexed_ammo),
        "excluded": excluded,
    }


def parse_existing_ammo(lines):
    result = set()
    for line in lines:
        match = re.search(r'AmmoId:"([^"]+)"', line)
        if match:
            result.add(match.group(1))
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base-weapons", required=True)
    parser.add_argument("--base-ammo", required=True)
    parser.add_argument("--arips", required=True)
    parser.add_argument("--maxstuff", required=True)
    parser.add_argument("--mobius", required=True)
    parser.add_argument("--out-weapons", required=True)
    parser.add_argument("--out-ammo", required=True)
    parser.add_argument("--report", required=True)
    args = parser.parse_args()

    base_weapons = Path(args.base_weapons).read_text("utf-8").splitlines()
    base_ammo = Path(args.base_ammo).read_text("utf-8").splitlines()
    existing_ammo = parse_existing_ammo(base_ammo)

    packs = [
        scan_pack(args.arips, "ARIPS 1.3.0"),
        scan_pack(args.maxstuff, "Maxstuff Legacy 1.8.3 hotfix"),
        scan_pack(args.mobius, "MS-Mobius Gunspack 1.5.8"),
    ]

    addon_weapon_rows = []
    addon_ammo = set()

    for pack in packs:
        for gun in pack["guns"]:
            weight = WEIGHTS.get(gun["type"].lower(), 2)
            nbt = (
                '{GunId:"' + gun["id"] + '",GunFireMode:"' + gun["mode"]
                + '",GunCurrentAmmoCount:8,HasBulletInBarrel:1b}'
            )
            addon_weapon_rows.append(
                f"tacz:modern_kinetic_gun\t{weight}\t1\t1\t{nbt}"
            )
            ammo = gun["ammo"]
            if ammo and ammo not in existing_ammo and ammo not in EXCLUDED_AMMO:
                addon_ammo.add(ammo)

        # Gli accessori sono oggetti TACZ con AttachmentId NBT e possono quindi
        # essere inseriti nello stesso pool tattico delle armi.
        for attachment in pack["attachments"]:
            addon_weapon_rows.append(
                'tacz:attachment\t1\t1\t1\t{AttachmentId:"' + attachment + '"}'
            )

    addon_ammo_rows = [
        'tacz:ammo\t3\t20\t32\t{AmmoId:"' + ammo + '"}'
        for ammo in sorted(addon_ammo)
    ]

    all_weapons = base_weapons + addon_weapon_rows
    all_ammo = base_ammo + addon_ammo_rows
    Path(args.out_weapons).write_text("\n".join(all_weapons) + "\n", encoding="utf-8")
    Path(args.out_ammo).write_text("\n".join(all_ammo) + "\n", encoding="utf-8")

    report = {
        "base_weapon_rows": len(base_weapons),
        "base_ammo_rows": len(base_ammo),
        "addon_guns": sum(len(p["guns"]) for p in packs),
        "addon_attachments": sum(len(p["attachments"]) for p in packs),
        "addon_ammo": len(addon_ammo_rows),
        "total_weapon_rows": len(all_weapons),
        "total_ammo_rows": len(all_ammo),
        "packs": {
            p["label"]: {
                "guns": len(p["guns"]),
                "attachments": len(p["attachments"]),
                "excluded": p["excluded"],
            }
            for p in packs
        },
        "addon_ammo_ids": sorted(addon_ammo),
    }
    Path(args.report).write_text(json.dumps(report, indent=2) + "\n", encoding="utf-8")
    print(json.dumps(report, indent=2))


if __name__ == "__main__":
    main()
