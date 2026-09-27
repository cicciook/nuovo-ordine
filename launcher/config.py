import json
import os
import re
import sys
from pathlib import Path
from platformdirs import user_data_path

DATA = user_data_path("NuovoOrdine", appauthor=False)
INSTANCE = DATA / "minecraft"


def atomic_json(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    with tmp.open("w", encoding="utf-8") as out:
        json.dump(value, out, ensure_ascii=False, indent=2)
        out.flush()
        os.fsync(out.fileno())
    os.replace(tmp, path)


def load_config():
    resource = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[1]))
    cfg = json.loads((resource / "launcher-config.json").read_text("utf-8"))
    path = DATA / "settings.json"
    if path.exists():
        user = json.loads(path.read_text("utf-8"))
        # Il Client ID appartiene alla build del launcher, non alle impostazioni dei giocatori.
        user.pop("microsoft_client_id", None)
        cfg.update(user)
    return cfg


def validate_config(cfg, require_login=False):
    if not re.fullmatch(r"[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+", cfg.get("repository", "")):
        raise ValueError("Configura il repository pubblico GitHub: nomeutente/nuovo-ordine.")
    if not re.fullmatch(r"[A-Za-z0-9_.-]+", cfg.get("branch", "")):
        raise ValueError("Il ramo GitHub deve avere un nome semplice, per esempio main.")
    if require_login and not re.fullmatch(r"[0-9a-fA-F-]{36}", cfg.get("microsoft_client_id", "")):
        raise ValueError("Il login Microsoft non è configurato in questa build del launcher.")
    if not 2048 <= int(cfg.get("ram_mb", 0)) <= 32768:
        raise ValueError("Imposta la RAM tra 2048 e 32768 MB.")


def manifest_url(cfg):
    validate_config(cfg)
    return f'https://raw.githubusercontent.com/{cfg["repository"]}/{cfg["branch"]}/pack.json'
