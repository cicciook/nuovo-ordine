import base64
import re
import sys
from pathlib import Path

from PySide6.QtCore import Qt, QThread, Signal, QUrl, QTimer, QLockFile
from PySide6.QtGui import QDesktopServices, QPixmap
from PySide6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QLabel,
    QPushButton, QProgressBar, QPlainTextEdit, QDialog, QFormLayout,
    QLineEdit, QSpinBox, QDialogButtonBox, QMessageBox, QFrame, QInputDialog,
    QTabWidget, QTextBrowser
)

from . import VERSION, auth, community, engine, selfupdate
from .config import DATA, load_config, atomic_json, validate_config


def resource_path(relative):
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[1]))
    return base / relative


def load_logo_pixmap():
    direct = resource_path("launcher/assets/nuovo-ordine-logo.jpg")
    if direct.exists() and direct.stat().st_size > 1024:
        pixmap = QPixmap(str(direct))
        if not pixmap.isNull():
            return pixmap

    parts = []
    for index in range(1, 7):
        path = resource_path(f"launcher/assets/logo.b64.{index:02d}")
        if not path.exists():
            return QPixmap()
        parts.append(path.read_text("ascii").strip())

    try:
        raw = base64.b64decode("".join(parts), validate=True)
    except Exception:
        return QPixmap()
    pixmap = QPixmap()
    pixmap.loadFromData(raw, "JPG")
    return pixmap


STYLE = """
QWidget {
    background: #07111f;
    color: #eaf4ff;
    font-family: 'Segoe UI', 'Helvetica Neue', sans-serif;
    font-size: 13px;
}
QMainWindow { background: #07111f; }
QDialog { background: #09182a; }
QLabel { background: transparent; }
QFrame#sidebar {
    background: #050b14;
    border-right: 1px solid #15324f;
}
QFrame#card, QFrame#communityCard {
    background: #0a1a2d;
    border: 1px solid #173b5e;
    border-radius: 10px;
}
QLabel#brand {
    color: #5ed7ff;
    font-size: 22px;
    font-weight: 800;
    letter-spacing: 4px;
}
QLabel#eyebrow {
    color: #61d6ff;
    font-size: 11px;
    font-weight: 700;
    letter-spacing: 3px;
}
QLabel#title {
    font-size: 22px;
    font-weight: 800;
}
QLabel#subtitle { color: #a7bfd5; font-size: 14px; }
QLabel#muted { color: #7893ad; font-size: 12px; }
QLabel#badge {
    color: #c8f3ff;
    background: #0b2940;
    border: 1px solid #1a557d;
    border-radius: 6px;
    padding: 6px 10px;
}
QPushButton {
    background: #0d2238;
    border: 1px solid #1b466c;
    border-radius: 7px;
    padding: 11px 16px;
    font-weight: 600;
}
QPushButton:hover {
    background: #123250;
    border-color: #36bff2;
}
QPushButton:disabled {
    color: #587087;
    background: #0a1725;
    border-color: #142a3e;
}
QPushButton#play {
    background: #178bc4;
    color: white;
    font-size: 19px;
    font-weight: 800;
    padding: 17px;
    border: 1px solid #37c7ff;
}
QPushButton#play:hover { background: #20a6e6; }
QPushButton#play:disabled {
    background: #16415d;
    color: #7fa8bf;
    border-color: #20506c;
}
QPushButton#discord {
    background: #18385b;
    border: 1px solid #3d70a3;
    font-size: 13px;
    font-weight: 800;
    letter-spacing: 1px;
}
QPushButton#discord:hover {
    background: #214d7a;
    border-color: #61d6ff;
}
QLineEdit, QSpinBox {
    background: #06101c;
    border: 1px solid #1b466c;
    padding: 8px;
    border-radius: 5px;
    selection-background-color: #168cc5;
}
QProgressBar {
    border: none;
    background: #102a40;
    border-radius: 3px;
    height: 6px;
}
QProgressBar::chunk {
    background: #26bff2;
    border-radius: 3px;
}
QPlainTextEdit {
    background: #050d17;
    border: 1px solid #17344f;
    padding: 8px;
    color: #8eb3cc;
    font-family: monospace;
    font-size: 11px;
}
QTabWidget#communityTabs::pane {
    border: 1px solid #173b5e;
    background: #071522;
    border-radius: 6px;
    top: -1px;
}
QTabWidget#communityTabs QTabBar::tab {
    background: #081827;
    color: #7893ad;
    border: 1px solid #173b5e;
    padding: 8px 13px;
    margin-right: 2px;
}
QTabWidget#communityTabs QTabBar::tab:selected {
    background: #0d2942;
    color: #61d6ff;
    border-bottom-color: #0d2942;
}
QTextBrowser#communityText {
    background: #071522;
    color: #c7dbeb;
    border: none;
    padding: 10px;
    font-size: 12px;
}
"""


class Worker(QThread):
    status = Signal(str)
    progress = Signal(int, int)
    browser = Signal(str)
    account = Signal(dict)
    result = Signal(object)
    failure = Signal(str)

    def __init__(self, job):
        super().__init__()
        self.job = job

    def run(self):
        try:
            self.result.emit(self.job(self))
        except Exception as exc:
            kind = type(exc).__name__
            friendly = {
                "AzureAppNotPermitted": "L'app Microsoft Nuovo Ordine non è ancora abilitata alle API Minecraft.",
                "AccountNotOwnMinecraft": "Questo account non possiede Minecraft Java Edition o non ha un profilo Java attivo.",
                "InvalidRefreshToken": "L'accesso è scaduto. Accedi di nuovo con Microsoft.",
                "XSTSError": "Xbox non ha autorizzato l'account. Verifica profilo Xbox e autorizzazioni famiglia.",
                "HTTPError": "Download o servizio non disponibile. Controlla Internet e riprova.",
                "ConnectionError": "Connessione non disponibile. Controlla Internet e riprova.",
                "Timeout": "La connessione è scaduta. Riprova.",
                "ReadTimeout": "La connessione è scaduta. Riprova.",
            }
            message = friendly.get(kind)
            if message is None:
                message = str(exc) if type(exc) in (ValueError, RuntimeError) else f"Operazione non riuscita ({kind})."
            self.failure.emit(message)


class Settings(QDialog):
    def __init__(self, cfg, parent):
        super().__init__(parent)
        self.cfg = cfg
        self.setWindowTitle("Nuovo Ordine • Impostazioni")
        self.setMinimumWidth(560)
        form = QFormLayout(self)
        self.fields = {}
        for key, title, placeholder in [
            ("repository", "Repository pubblico GitHub", "nomeutente/nuovo-ordine"),
            ("branch", "Ramo degli aggiornamenti", "main"),
            ("server", "Server di riserva", "play.esempio.it:25565"),
            ("java_path", "Java 17 (vuoto = automatico)", "Percorso di java / java.exe"),
        ]:
            field = QLineEdit(str(cfg.get(key, "")))
            field.setPlaceholderText(placeholder)
            self.fields[key] = field
            form.addRow(title, field)

        self.ram = QSpinBox()
        self.ram.setRange(2048, 32768)
        self.ram.setSingleStep(1024)
        self.ram.setSuffix(" MB")
        self.ram.setValue(int(cfg.get("ram_mb", 6144)))
        form.addRow("RAM massima", self.ram)

        note = QLabel(
            "L'accesso Microsoft è configurato dal proprietario del launcher e non richiede ID agli utenti.\n"
            "Changelog, eventi e Discord sono gestiti da launcher-community.json su GitHub."
        )
        note.setWordWrap(True)
        form.addRow(note)

        buttons = QDialogButtonBox(
            QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel
        )
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        form.addRow(buttons)

    def values(self):
        values = {k: v.text().strip() for k, v in self.fields.items()}
        values["ram_mb"] = self.ram.value()
        return values


class Window(QMainWindow):
    def __init__(self):
        super().__init__()
        self.cfg = load_config()
        self.session = None
        self.worker = None
        self.failed = False
        self.after_finish = None
        self.discord_url = ""
        self.setWindowTitle("Nuovo Ordine • Launcher")
        self.resize(1240, 800)
        self.setMinimumSize(1080, 735)

        root = QWidget()
        self.setCentralWidget(root)
        row = QHBoxLayout(root)
        row.setContentsMargins(0, 0, 0, 0)
        row.setSpacing(0)

        sidebar = QFrame()
        sidebar.setObjectName("sidebar")
        sidebar.setFixedWidth(245)
        side = QVBoxLayout(sidebar)
        side.setContentsMargins(22, 30, 22, 24)
        side.setSpacing(14)

        brand = QLabel("NUOVO\nORDINE")
        brand.setObjectName("brand")
        side.addWidget(brand)
        small = QLabel("MINECRAFT COMMUNITY")
        small.setObjectName("muted")
        side.addWidget(small)
        side.addSpacing(24)

        account_card = QFrame()
        account_card.setObjectName("card")
        account_layout = QVBoxLayout(account_card)
        account_layout.setContentsMargins(14, 14, 14, 14)
        self.account = QLabel("Il tuo account\nNon connesso")
        self.account.setWordWrap(True)
        account_layout.addWidget(self.account)
        self.login_button = QPushButton("Accedi con Microsoft")
        self.login_button.clicked.connect(self.login)
        account_layout.addWidget(self.login_button)
        self.logout_button = QPushButton("Disconnetti")
        self.logout_button.clicked.connect(self.logout)
        account_layout.addWidget(self.logout_button)
        side.addWidget(account_card)

        side.addStretch()
        self.settings_button = QPushButton("Impostazioni")
        self.settings_button.clicked.connect(self.settings)
        side.addWidget(self.settings_button)
        self.cosmetics_button = QPushButton("Skin e mantelli")
        self.cosmetics_button.clicked.connect(self.cosmetics)
        side.addWidget(self.cosmetics_button)

        folder = QPushButton("Apri cartella launcher")
        folder.clicked.connect(lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(DATA))))
        side.addWidget(folder)

        self.releases = QPushButton("Versioni del launcher")
        self.releases.clicked.connect(self.open_releases)
        side.addWidget(self.releases)

        version = QLabel(f"LAUNCHER {VERSION}\nAggiornamenti tramite installer")
        version.setObjectName("muted")
        side.addWidget(version)
        row.addWidget(sidebar)

        content = QVBoxLayout()
        content.setContentsMargins(28, 24, 28, 22)
        content.setSpacing(13)

        top = QHBoxLayout()
        label = QLabel("NUOVO ORDINE LAUNCHER")
        label.setObjectName("eyebrow")
        top.addWidget(label)
        top.addStretch()
        badge = QLabel("MINECRAFT 1.20.1  /  FORGE")
        badge.setObjectName("badge")
        top.addWidget(badge)
        content.addLayout(top)

        hero_row = QHBoxLayout()
        hero_row.setSpacing(16)

        logo_card = QFrame()
        logo_card.setObjectName("communityCard")
        logo_layout = QVBoxLayout(logo_card)
        logo_layout.setContentsMargins(12, 12, 12, 12)
        self.logo = QLabel()
        self.logo.setAlignment(Qt.AlignmentFlag.AlignCenter)
        self.logo.setMinimumHeight(245)
        self.logo.setMaximumHeight(270)
        pixmap = load_logo_pixmap()
        if pixmap.isNull():
            self.logo.setText("NUOVO ORDINE")
            self.logo.setObjectName("title")
        else:
            self.logo.setPixmap(
                pixmap.scaled(
                    480,
                    255,
                    Qt.AspectRatioMode.KeepAspectRatio,
                    Qt.TransformationMode.SmoothTransformation,
                )
            )
        logo_layout.addWidget(self.logo)
        hero_row.addWidget(logo_card, 3)

        community_card = QFrame()
        community_card.setObjectName("communityCard")
        community_layout = QVBoxLayout(community_card)
        community_layout.setContentsMargins(12, 12, 12, 12)
        community_layout.setSpacing(9)

        community_title = QLabel("COMMUNITY")
        community_title.setObjectName("eyebrow")
        community_layout.addWidget(community_title)

        self.community_tabs = QTabWidget()
        self.community_tabs.setObjectName("communityTabs")
        self.community_tabs.setDocumentMode(True)

        self.changelog_text = QTextBrowser()
        self.changelog_text.setObjectName("communityText")
        self.changelog_text.setOpenExternalLinks(False)
        self.changelog_text.setPlainText("Caricamento changelog…")
        self.community_tabs.addTab(self.changelog_text, "CHANGELOG")

        self.events_text = QTextBrowser()
        self.events_text.setObjectName("communityText")
        self.events_text.setOpenExternalLinks(False)
        self.events_text.setPlainText("Caricamento bacheca eventi…")
        self.community_tabs.addTab(self.events_text, "EVENTI")

        community_layout.addWidget(self.community_tabs, 1)

        self.discord_button = QPushButton("DISCORD • CARICAMENTO…")
        self.discord_button.setObjectName("discord")
        self.discord_button.setEnabled(False)
        self.discord_button.clicked.connect(self.open_discord)
        community_layout.addWidget(self.discord_button)

        hero_row.addWidget(community_card, 2)
        content.addLayout(hero_row)

        self.news = QLabel("Controllo launcher e modpack in corso…")
        self.news.setWordWrap(True)
        self.news.setTextFormat(Qt.TextFormat.PlainText)
        content.addWidget(self.news)

        actions = QHBoxLayout()
        self.play_button = QPushButton("GIOCA OFFLINE  →")
        self.play_button.setObjectName("play")
        self.play_button.clicked.connect(self.play)
        actions.addWidget(self.play_button, 2)

        self.update_button = QPushButton("Verifica / aggiorna mod")
        self.update_button.clicked.connect(self.update_pack)
        actions.addWidget(self.update_button, 1)
        content.addLayout(actions)

        self.status_label = QLabel("Avvio • controllo aggiornamenti launcher")
        self.status_label.setWordWrap(True)
        self.status_label.setObjectName("muted")
        content.addWidget(self.status_label)

        self.bar = QProgressBar()
        self.bar.setTextVisible(False)
        self.bar.setValue(0)
        content.addWidget(self.bar)

        self.log = QPlainTextEdit()
        self.log.setReadOnly(True)
        self.log.setMaximumBlockCount(150)
        self.log.setMinimumHeight(80)
        content.addWidget(self.log, 1)

        row.addLayout(content, 1)
        self.controls = [
            self.play_button,
            self.update_button,
            self.login_button,
            self.logout_button,
            self.settings_button,
        ]
        self.refresh_auth_controls()
        QTimer.singleShot(350, self.startup)

    def microsoft_client_id(self):
        return str(self.cfg.get("microsoft_client_id", "")).strip()

    def has_microsoft_login(self):
        client_id = self.microsoft_client_id()
        return bool(client_id and (self.session or auth.saved_token(client_id)))

    def refresh_auth_controls(self):
        busy = bool(self.worker and self.worker.isRunning())
        online = self.has_microsoft_login()
        configured = bool(re.fullmatch(r"[0-9a-fA-F-]{36}", self.microsoft_client_id()))
        self.play_button.setText("GIOCA ONLINE  →" if online else "GIOCA OFFLINE  →")
        self.play_button.setEnabled(not busy)
        self.update_button.setEnabled(not busy)
        self.login_button.setEnabled(not busy and not online and configured)
        self.logout_button.setEnabled(not busy and online)
        self.settings_button.setEnabled(not busy)
        if online and self.session:
            self.account.setText("Connesso come\n" + self.session["name"])
        elif online:
            self.account.setText("Account Microsoft\nAccesso salvato")
        elif configured:
            self.account.setText("Modalità offline\nMicrosoft disponibile")
            self.login_button.setText("Accedi con Microsoft")
        else:
            self.account.setText("Modalità offline\nMicrosoft non configurato")
            self.login_button.setText("Microsoft non configurato")
            self.login_button.setToolTip("Il Client ID va configurato una sola volta dal proprietario del launcher, non dai giocatori.")

    def report(self, text):
        self.status_label.setText(text)
        if not text.startswith("Download "):
            self.log.appendPlainText(text)

    def begin(self, job, completed=None):
        if self.worker and self.worker.isRunning():
            return
        for control in self.controls:
            control.setEnabled(False)
        self.failed = False
        self.bar.setRange(0, 0)
        self.worker = Worker(job)
        self.worker.status.connect(self.report)
        self.worker.progress.connect(self.progress)
        self.worker.browser.connect(lambda url: QDesktopServices.openUrl(QUrl(url)))
        self.worker.account.connect(self.set_account)
        self.worker.failure.connect(self.error)
        if completed:
            self.worker.result.connect(completed)
        self.worker.finished.connect(self.finish)
        self.worker.start()

    def progress(self, n, maximum):
        if maximum >= 0:
            self.bar.setRange(0, max(1, maximum))
        self.bar.setValue(n)

    def finish(self):
        self.bar.setRange(0, 100)
        self.bar.setValue(0 if self.failed else 100)
        self.refresh_auth_controls()
        callback = self.after_finish
        self.after_finish = None
        if callback:
            QTimer.singleShot(0, callback)

    def error(self, message):
        self.failed = True
        self.report(message)
        QMessageBox.warning(self, "Nuovo Ordine", message)

    def set_account(self, data):
        self.session = data
        self.refresh_auth_controls()

    def startup(self):
        if not self.cfg.get("repository"):
            self.report("Configura il repository GitHub nelle impostazioni.")
            return
        self.begin(
            lambda w: selfupdate.prepare_update(self.cfg, w.status.emit),
            self.launcher_update_ready,
        )

    def launcher_update_ready(self, update):
        if update:
            self.report(f'Aggiornamento launcher {update["version"]} pronto. Riavvio…')
            try:
                selfupdate.apply_update(update)
            except Exception as exc:
                self.error(str(exc))
                self.after_finish = self.update_pack
                return
            QApplication.instance().quit()
            return
        self.after_finish = self.update_pack

    def login(self):
        client_id = self.microsoft_client_id()
        if not re.fullmatch(r"[0-9a-fA-F-]{36}", client_id):
            self.error("Login Microsoft non configurato nella build del launcher.")
            return
        self.report("Apro Microsoft nel browser…")
        self.begin(
            lambda w: auth.login(client_id, w.status.emit, w.browser.emit),
            self.set_account,
        )

    def logout(self):
        try:
            auth.forget(self.microsoft_client_id())
            self.session = None
            self.refresh_auth_controls()
            self.report("Account disconnesso. Ora puoi giocare in modalità offline.")
        except RuntimeError as exc:
            self.error(str(exc))

    def pack_ready(self, pack):
        self.news.setText(str(pack.get("news", "Benvenuto su Nuovo Ordine.")))
        self.after_finish = self.update_community

    def update_pack(self):
        self.begin(
            lambda w: engine.get_pack(self.cfg, w.status.emit),
            self.pack_ready,
        )

    def update_community(self):
        self.begin(
            lambda w: community.fetch_content(self.cfg, w.status.emit),
            self.community_ready,
        )

    def community_ready(self, data):
        self.changelog_text.setPlainText(community.format_changelog(data.get("changelog", [])))
        self.events_text.setPlainText(community.format_events(data.get("events", [])))
        self.discord_url = str(data.get("discord_url", "")).strip()
        if self.discord_url:
            self.discord_button.setText("ENTRA NEL DISCORD  →")
            self.discord_button.setEnabled(True)
        else:
            self.discord_button.setText("DISCORD • LINK DA CONFIGURARE")
            self.discord_button.setEnabled(False)
        self.report("Launcher, modpack e bacheca community aggiornati.")

    def open_discord(self):
        if self.discord_url:
            QDesktopServices.openUrl(QUrl(self.discord_url))

    def ask_offline_name(self):
        default = str(self.cfg.get("offline_name", "Player"))
        while True:
            name, ok = QInputDialog.getText(
                self,
                "Nome modalità offline",
                "Come vuoi chiamarti in modalità offline?\nUsa 3–16 caratteri: lettere, numeri o _",
                text=default,
            )
            if not ok:
                return None
            name = name.strip()
            if re.fullmatch(r"[A-Za-z0-9_]{3,16}", name):
                self.cfg["offline_name"] = name
                self.save_user_settings()
                return name
            QMessageBox.warning(
                self,
                "Nome non valido",
                "Il nome deve contenere da 3 a 16 caratteri: lettere, numeri o underscore (_).",
            )
            default = name

    def play(self):
        offline_name = None
        if not self.has_microsoft_login():
            offline_name = self.ask_offline_name()
            if not offline_name:
                return
        self.begin(
            lambda w: engine.play(
                self.cfg,
                self.session,
                w.status.emit,
                w.progress.emit,
                w.account.emit,
                offline_name,
            ),
            self.pack_ready,
        )

    def save_user_settings(self):
        allowed = {
            "repository": self.cfg.get("repository", ""),
            "branch": self.cfg.get("branch", "main"),
            "server": self.cfg.get("server", ""),
            "ram_mb": int(self.cfg.get("ram_mb", 6144)),
            "java_path": self.cfg.get("java_path", ""),
        }
        if self.cfg.get("offline_name"):
            allowed["offline_name"] = self.cfg["offline_name"]
        atomic_json(DATA / "settings.json", allowed)

    def cosmetics(self):
        from .cosmetics_ui import CosmeticsDialog
        CosmeticsDialog(self).exec()

    def settings(self):
        dialog = Settings(self.cfg, self)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            values = dialog.values()
            values["microsoft_client_id"] = self.microsoft_client_id()
            if "offline_name" in self.cfg:
                values["offline_name"] = self.cfg["offline_name"]
            self.cfg = values
            self.save_user_settings()
            self.refresh_auth_controls()
            self.report("Impostazioni salvate. Premi Verifica / aggiorna mod.")

    def open_releases(self):
        try:
            validate_config(self.cfg)
            QDesktopServices.openUrl(
                QUrl(f'https://github.com/{self.cfg["repository"]}/releases')
            )
        except ValueError as exc:
            self.error(str(exc))

    def closeEvent(self, event):
        if self.worker and self.worker.isRunning():
            QMessageBox.information(
                self,
                "Operazione in corso",
                "Attendi la fine dell'operazione prima di chiudere il launcher.",
            )
            event.ignore()
        else:
            event.accept()


def main():
    app = QApplication(sys.argv)
    app.setApplicationName("Nuovo Ordine")
    app.setStyleSheet(STYLE)
    DATA.mkdir(parents=True, exist_ok=True)
    lock = QLockFile(str(DATA / "launcher.lock"))
    lock.setStaleLockTime(0)
    if not lock.tryLock(100):
        QMessageBox.information(None, "Nuovo Ordine", "Il launcher è già aperto.")
        return
    window = Window()
    window.show()
    app.exec()
    lock.unlock()
