import re

import requests

from . import VERSION
from .config import validate_config


DEFAULT_CONTENT = {
    "discord_url": "",
    "changelog": [
        {
            "version": VERSION,
            "title": "Nuovo Ordine Launcher",
            "body": "Launcher aggiornato. Le novità verranno caricate automaticamente da GitHub.",
        }
    ],
    "events": [
        {
            "date": "",
            "title": "Nessun evento programmato",
            "body": "Gli eventi del server compariranno qui appena pubblicati.",
        }
    ],
}


def _clean_text(value, maximum):
    text = str(value or "").replace("\r", "").strip()
    return text[:maximum]


def _normalize_items(items, kind):
    result = []
    if not isinstance(items, list):
        return result
    for raw in items[:20]:
        if not isinstance(raw, dict):
            continue
        if kind == "changelog":
            item = {
                "version": _clean_text(raw.get("version"), 40),
                "title": _clean_text(raw.get("title"), 100),
                "body": _clean_text(raw.get("body"), 2500),
            }
        else:
            item = {
                "date": _clean_text(raw.get("date"), 80),
                "title": _clean_text(raw.get("title"), 120),
                "body": _clean_text(raw.get("body"), 2500),
            }
        if item["title"] or item["body"]:
            result.append(item)
    return result


def normalize_content(data):
    if not isinstance(data, dict):
        data = {}
    discord = _clean_text(data.get("discord_url"), 300)
    if discord and not re.fullmatch(r"https://(?:discord\.gg|(?:www\.)?discord\.com/invite)/[A-Za-z0-9_-]+/?", discord):
        discord = ""
    changelog = _normalize_items(data.get("changelog"), "changelog")
    events = _normalize_items(data.get("events"), "events")
    return {
        "discord_url": discord,
        "changelog": changelog or list(DEFAULT_CONTENT["changelog"]),
        "events": events or list(DEFAULT_CONTENT["events"]),
    }


def fetch_content(cfg, report=lambda text: None):
    """Fetch the small community board JSON without ever blocking launcher use."""
    try:
        validate_config(cfg)
        url = (
            f'https://raw.githubusercontent.com/{cfg["repository"]}/'
            f'{cfg["branch"]}/launcher-community.json'
        )
        report("Aggiorno changelog e bacheca eventi…")
        response = requests.get(
            url,
            timeout=(8, 15),
            headers={"User-Agent": f"NuovoOrdine/{VERSION}"},
        )
        response.raise_for_status()
        return normalize_content(response.json())
    except Exception:
        report("Bacheca community non disponibile: uso i dati locali.")
        return normalize_content(DEFAULT_CONTENT)


def format_changelog(items):
    blocks = []
    for item in items:
        heading = " ".join(part for part in [item.get("version", ""), item.get("title", "")] if part).strip()
        body = item.get("body", "").strip()
        blocks.append((heading + ("\n" + body if body else "")).strip())
    return "\n\n────────────\n\n".join(blocks) if blocks else "Nessun changelog disponibile."


def format_events(items):
    blocks = []
    for item in items:
        heading = " • ".join(part for part in [item.get("date", ""), item.get("title", "")] if part).strip()
        body = item.get("body", "").strip()
        blocks.append((heading + ("\n" + body if body else "")).strip())
    return "\n\n────────────\n\n".join(blocks) if blocks else "Nessun evento programmato."
