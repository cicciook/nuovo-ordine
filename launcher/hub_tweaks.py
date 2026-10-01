"""Live Nuovo Ordine server hub tabs for the launcher."""
from __future__ import annotations

import json
import time
import urllib.request

from PySide6.QtCore import QThread, Signal, QTimer
from PySide6.QtWidgets import QLineEdit, QTextBrowser


class HubWorker(QThread):
    result = Signal(dict)
    failure = Signal(str)

    def __init__(self, url: str):
        super().__init__()
        self.url = url

    def run(self):
        try:
            req = urllib.request.Request(self.url, headers={"User-Agent": "NuovoOrdineLauncher-Hub/1.0"}, method="GET")
            with urllib.request.urlopen(req, timeout=3.5) as response:
                if response.status != 200:
                    raise RuntimeError(f"HTTP {response.status}")
                raw = response.read(512 * 1024)
            data = json.loads(raw.decode("utf-8"))
            if not isinstance(data, dict):
                raise ValueError("risposta non valida")
            self.result.emit(data)
        except Exception as exc:
            self.failure.emit(str(exc))


def _safe_hub_url(cfg: dict) -> str:
    explicit = str(cfg.get("hub_url", "")).strip()
    if explicit:
        return explicit if explicit.startswith(("http://", "https://")) else ""
    server = str(cfg.get("server", "")).strip()
    if not server:
        return ""
    host = server
    if server.startswith("[") and "]" in server:
        host = server[1:server.index("]")]
    elif server.count(":") == 1:
        host = server.rsplit(":", 1)[0]
    if not host or any(ch in host for ch in "/?#"):
        return ""
    return f"http://{host}:8765/api/all"


def _age_label(timestamp_ms) -> str:
    try:
        age = max(0, int(time.time() - float(timestamp_ms) / 1000))
    except Exception:
        return "orario sconosciuto"
    if age < 10:
        return "adesso"
    if age < 60:
        return f"{age}s fa"
    return f"{age // 60}m fa"


def _fmt_number(value) -> str:
    try:
        value = float(value)
    except Exception:
        return str(value)
    if value.is_integer():
        return f"{int(value):,}".replace(",", ".")
    return f"{value:,.2f}".replace(",", "X").replace(".", ",").replace("X", ".")


def _server_text(data: dict) -> str:
    lines = [
        f"SERVER LIVE • dati {_age_label(data.get('timestamp'))}", "",
        f"Giocatori online: {data.get('online', '—')}",
        f"Convoglio: {data.get('convoy', '—')}",
        f"Airdrop: {data.get('airdrop') or 'nessuno attivo'}",
        f"Denaro rimosso dall'economia: ${_fmt_number(data.get('moneySunk', 0))}",
    ]
    event = data.get("event")
    lines.extend(["", f"EVENTO CALDO: {event.get('name', event.get('point', '—'))}" if isinstance(event, dict) else "Nessun evento strategico attivo."])
    return "\n".join(lines)


def _ranking_text(data: dict) -> str:
    lines = ["INFLUENZA TOWN / NAZIONI", ""]
    influence = data.get("influence") or []
    for i, row in enumerate(influence[:15], 1):
        stars = int(row.get("legacy", 0) or 0)
        lines.append(f"{i:>2}. {row.get('name', '—')}  —  {_fmt_number(row.get('score', 0))}" + (f"  ★{stars}" if stars else ""))
    if not influence:
        lines.append("Nessun punteggio disponibile.")
    lines.extend(["", "PLAYER • KILL / CONTRATTI / LOOT"])
    players = data.get("players") or []
    for i, row in enumerate(players[:15], 1):
        lines.append(f"{i:>2}. {row.get('name', '—')}  —  K {row.get('kills', 0)} / C {row.get('contracts', 0)} / L {row.get('loot', 0)} / MTS {row.get('mtsKm', 0)} km")
    if not players:
        lines.append("Nessuna statistica disponibile.")
    return "\n".join(lines)


def _market_text(data: dict) -> str:
    lines = [f"CONTRATTI APERTI: {data.get('openContracts', 0)}", "", "MERCATO VEICOLI USATI", ""]
    used = data.get("used") or []
    for row in used[:25]:
        lines.append(f"#{row.get('id', '—')}  {row.get('vehicle', 'Veicolo')} [{row.get('plate', '—')}]\n    ${_fmt_number(row.get('price', 0))} • {row.get('seller', '—')}")
    if not used:
        lines.append("Nessun veicolo usato in vendita.")
    return "\n".join(lines)


def apply(ui):
    if getattr(ui.Window, "_nuovo_ordine_hub_patched", False):
        return
    original_window_init = ui.Window.__init__
    original_community_ready = ui.Window.community_ready
    original_settings_init = ui.Settings.__init__

    def settings_init(self, cfg, parent):
        original_settings_init(self, cfg, parent)
        field = QLineEdit(str(cfg.get("hub_url", "")))
        field.setPlaceholderText("http://host-server:8765/api/all")
        self.fields["hub_url"] = field
        form = self.layout()
        try:
            form.insertRow(max(0, form.rowCount() - 1), "Hub live del server", field)
        except Exception:
            form.addRow("Hub live del server", field)

    def window_init(self, *args, **kwargs):
        original_window_init(self, *args, **kwargs)
        self.server_text = QTextBrowser(); self.server_text.setObjectName("communityText"); self.server_text.setPlainText("Connessione al server live…"); self.community_tabs.addTab(self.server_text, "SERVER")
        self.ranking_text = QTextBrowser(); self.ranking_text.setObjectName("communityText"); self.ranking_text.setPlainText("Caricamento classifiche…"); self.community_tabs.addTab(self.ranking_text, "CLASSIFICHE")
        self.market_text = QTextBrowser(); self.market_text.setObjectName("communityText"); self.market_text.setPlainText("Caricamento mercato…"); self.community_tabs.addTab(self.market_text, "MERCATO")
        self._hub_worker = None
        self._hub_timer = QTimer(self); self._hub_timer.setInterval(30_000); self._hub_timer.timeout.connect(self.refresh_live_hub); self._hub_timer.start()
        QTimer.singleShot(1200, self.refresh_live_hub)

    def refresh_live_hub(self):
        if self._hub_worker and self._hub_worker.isRunning():
            return
        url = _safe_hub_url(self.cfg)
        if not url:
            message = "Hub live non configurato. Imposta 'Hub live del server' nelle Impostazioni."
            self.server_text.setPlainText(message); self.ranking_text.setPlainText(message); self.market_text.setPlainText(message); return
        self._hub_worker = HubWorker(url); self._hub_worker.result.connect(self._hub_ready); self._hub_worker.failure.connect(self._hub_failed); self._hub_worker.start()

    def hub_ready(self, data):
        self.server_text.setPlainText(_server_text(data)); self.ranking_text.setPlainText(_ranking_text(data)); self.market_text.setPlainText(_market_text(data))

    def hub_failed(self, reason):
        text = f"Dati live non disponibili.\n{reason}\n\nIl launcher continuerà a riprovare automaticamente."
        self.server_text.setPlainText(text); self.ranking_text.setPlainText(text); self.market_text.setPlainText(text)

    def community_ready(self, data):
        original_community_ready(self, data); self.refresh_live_hub()

    ui.Settings.__init__ = settings_init
    ui.Window.__init__ = window_init
    ui.Window.refresh_live_hub = refresh_live_hub
    ui.Window._hub_ready = hub_ready
    ui.Window._hub_failed = hub_failed
    ui.Window.community_ready = community_ready
    ui.Window._nuovo_ordine_hub_patched = True
