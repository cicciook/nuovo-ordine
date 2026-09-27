import re
import sys
from pathlib import Path

from PySide6.QtCore import Qt, QThread, Signal, QUrl, QTimer, QLockFile
from PySide6.QtGui import QDesktopServices, QPainter, QColor, QLinearGradient, QPixmap
from PySide6.QtWidgets import (
    QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout, QLabel,
    QPushButton, QProgressBar, QPlainTextEdit, QDialog, QFormLayout,
    QLineEdit, QSpinBox, QDialogButtonBox, QMessageBox, QFrame, QInputDialog
)

from . import VERSION, auth, engine, selfupdate
from .config import DATA, load_config, atomic_json, validate_config


def resource_path(relative):
    base = Path(getattr(sys, "_MEIPASS", Path(__file__).resolve().parents[1]))
    return base / relative


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
QFrame#card {
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
"""


class LogoHero(QWidget):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.image = QPixmap(str(resource_path("launcher/assets/nuovo-ordine-logo.jpg")))
        self.setMinimumHeight(320)

    def paintEvent(self, event):
        painter = QPainter(self)
        painter.fillRect(self.rect(), QColor("#06111f"))
        if not self.image.isNull():
            scaled = self.image.scaled(
                self.size(),
                Qt.AspectRatioMode.KeepAspectRatioByExpanding,
                Qt.TransformationMode.SmoothTransformation,
            )
            x = (scaled.width() - self.width()) // 2
            y = (scaled.height() - self.height()) // 2
            painter.drawPixmap(self.rect(), scaled, scaled.rect().adjusted(x, y, -x, -y))
        shade = QLinearGradient(0, 0, 0, self.height())
        shade.setColorAt(0, QColor(2, 10, 20, 35))
        shade.setColorAt(0.7, QColor(2, 10, 20, 80))
        shade.setColorAt(1, QColor(2, 10, 20, 190))
        painter.fillRect(self.rect(), shade)
        painter.end()


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
                "AzureAppNotPermitted": "L'app Microsoft non è abilitata alle API Minecraft. Il proprietario deve richiedere l'abilitazione del Client ID.",
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
        self.setWindowTitle("Nuovo Ordine • Impostazioni")
        self.setMinimumWidth(560)
        form = QFormLayout(self)
        self.fields = {}
        for key, title, placeholder in [
            ("repository", "Repository pubblico GitHub", "nomeutente/nuovo-ordine"),
            ("branch", "Ramo degli aggiornamenti", "main"),
            ("microsoft_client_id", "Client ID Microsoft", "ID applicazione, non il client secret"),
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
            "Il launcher si aggiorna automaticamente dalle Release GitHub.\n"
            "L'indirizzo pubblicato nel modpack ha precedenza sul server di riserva."
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
        return {
            **{k: v.text().strip() for k, v in self.fields.items()},
            "ram_mb": self.ram.value(),
        }


class MicrosoftLoginDialog(QDialog):
    def __init__(self, parent):
        super().__init__(parent)
        self.setWindowTitle("Aggiungi account Microsoft")
        self.setMinimumWidth(470)
        box = QVBoxLayout(self)
        title = QLabel("ACCEDI CON MICROSOFT")
        title.setObjectName("title")
        box.addWidget(title)
        text = QLabel(
            "Il launcher aprirà il browser predefinito, come Prism Launcher.\n\n"
            "1. Accedi al tuo account Microsoft nel browser.\n"
            "2. Autorizza Minecraft.\n"
            "3. Quando compare la conferma, torna qui.\n\n"
            "La password non passa mai dal launcher."
        )
        text.setWordWrap(True)
        text.setObjectName("subtitle")
        box.addWidget(text)
        buttons = QDialogButtonBox()
        go = buttons.addButton("Apri Microsoft nel browser", QDialogButtonBox.ButtonRole.AcceptRole)
        buttons.addButton("Annulla", QDialogButtonBox.ButtonRole.RejectRole)
        go.clicked.connect(self.accept)
        buttons.rejected.connect(self.reject)
        box.addWidget(buttons)


class Window(QMainWindow):
    def __init__(self):
        super().__init__()
        self.cfg = load_config()
        self.session = None
        self.worker = None
        self.failed = False
        self.after_finish = None
        self.setWindowTitle("Nuovo Ordine • Launcher")
        self.resize(1120, 760)
        self.setMinimumSize(980, 700)

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

        folder = QPushButton("Apri cartella launcher")
        folder.clicked.connect(lambda: QDesktopServices.openUrl(QUrl.fromLocalFile(str(DATA))))
        side.addWidget(folder)

        self.releases = QPushButton("Versioni del launcher")
        self.releases.clicked.connect(self.open_releases)
        side.addWidget(self.releases)

        version = QLabel(f"LAUNCHER {VERSION}\nAggiornamento automatico attivo")
        version.setObjectName("muted")
        side.addWidget(version)
        row.addWidget(sidebar)

        content = QVBoxLayout()
        content.setContentsMargins(28, 24, 28, 22)
        content.setSpacing(15)

        top = QHBoxLayout()
        label = QLabel("NUOVO ORDINE LAUNCHER")
        label.setObjectName("eyebrow")
        top.addWidget(label)
        top.addStretch()
        badge = QLabel("MINECRAFT 1.20.1  /  FORGE")
        badge.setObjectName("badge")
        top.addWidget(badge)
        content.addLayout(top)

        hero = LogoHero()
        content.addWidget(hero)

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
        self.log.setMinimumHeight(95)
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

    def has_microsoft_login(self):
        client_id = self.cfg.get("microsoft_client_id", "").strip()
        return bool(client_id and (self.session or auth.saved_token(client_id)))

    def refresh_auth_controls(self):
        busy = bool(self.worker and self.worker.isRunning())
        online = self.has_microsoft_login()
        self.play_button.setText("GIOCA ONLINE  →" if online else "GIOCA OFFLINE  →")
        self.play_button.setEnabled(not busy)
        self.update_button.setEnabled(not busy)
        self.login_button.setEnabled(not busy and not online)
        self.logout_button.setEnabled(not busy and online)
        self.settings_button.setEnabled(not busy)
        if self.session:
            self.account.setText("Connesso come\n" + self.session["name"])
        elif online:
            self.account.setText("Account Microsoft\nAccesso salvato")
        else:
            self.account.setText("Modalità offline\nNessun account Microsoft")

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
        try:
            validate_config(self.cfg, require_login=True)
        except ValueError as exc:
            self.error(str(exc))
            return
        dialog = MicrosoftLoginDialog(self)
        if dialog.exec() != QDialog.DialogCode.Accepted:
            return
        self.report("Apro Microsoft nel browser…")
        self.begin(
            lambda w: auth.login(
                self.cfg["microsoft_client_id"], w.status.emit, w.browser.emit
            ),
            self.set_account,
        )

    def logout(self):
        try:
            auth.forget(self.cfg.get("microsoft_client_id", ""))
            self.session = None
            self.refresh_auth_controls()
            self.report("Account disconnesso. Ora puoi giocare in modalità offline.")
        except RuntimeError as exc:
            self.error(str(exc))

    def pack_ready(self, pack):
        self.news.setText(str(pack.get("news", "Benvenuto su Nuovo Ordine.")))

    def update_pack(self):
        self.begin(
            lambda w: engine.get_pack(self.cfg, w.status.emit),
            self.pack_ready,
        )

    def ask_offline_name(self):
        default = str(self.cfg.get("offline_name", "Player"))
        while True:
            name, ok = QInputDialog.getText(
                self,
                "Nome modalità offline",
                "Come vuoi chiamarti in modalità offline?\n"
                "Usa 3–16 caratteri: lettere, numeri o _",
                text=default,
            )
            if not ok:
                return None
            name = name.strip()
            if re.fullmatch(r"[A-Za-z0-9_]{3,16}", name):
                self.cfg["offline_name"] = name
                atomic_json(DATA / "settings.json", self.cfg)
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

    def settings(self):
        dialog = Settings(self.cfg, self)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            values = dialog.values()
            if "offline_name" in self.cfg:
                values["offline_name"] = self.cfg["offline_name"]
            if values.get("microsoft_client_id") != self.cfg.get("microsoft_client_id"):
                try:
                    auth.forget(self.cfg.get("microsoft_client_id", ""))
                except RuntimeError as exc:
                    self.error(str(exc))
                    return
                self.session = None
            self.cfg = values
            atomic_json(DATA / "settings.json", values)
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
