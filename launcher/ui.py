import sys
from pathlib import Path
from PySide6.QtCore import Qt, QThread, Signal, QUrl, QTimer, QLockFile
from PySide6.QtGui import QDesktopServices, QPainter, QColor, QLinearGradient, QPolygonF
from PySide6.QtCore import QPointF
from PySide6.QtWidgets import (QApplication, QMainWindow, QWidget, QVBoxLayout, QHBoxLayout,
    QLabel, QPushButton, QProgressBar, QPlainTextEdit, QDialog, QFormLayout,
    QLineEdit, QSpinBox, QDialogButtonBox, QMessageBox, QFileDialog, QFrame)
from . import VERSION, auth, engine
from .config import DATA, INSTANCE, load_config, atomic_json, validate_config

STYLE = """
QWidget { background: #111618; color: #e8ece8; font-family: 'Segoe UI', 'Helvetica Neue', sans-serif; font-size: 13px; }
QMainWindow { background: #111618; }
QLabel { background: transparent; }
QFrame#sidebar { background: #0c1113; border-right: 1px solid #273035; }
QLabel#brand { color: #e0bc74; font-size: 22px; font-weight: 800; letter-spacing: 4px; }
QLabel#eyebrow { color: #d7b676; font-size: 11px; font-weight: 700; letter-spacing: 3px; }
QLabel#title { font-size: 56px; font-weight: 900; letter-spacing: 1px; }
QLabel#subtitle { color: #bec7c4; font-size: 15px; }
QLabel#muted { color: #83938e; font-size: 12px; }
QLabel#badge { color: #b7cfbf; background: #203029; border: 1px solid #365144; border-radius: 5px; padding: 6px 10px; }
QPushButton { background: #212a2d; border: 1px solid #354044; border-radius: 6px; padding: 11px 16px; font-weight: 600; }
QPushButton:hover { background: #2e393d; border-color: #a79572; }
QPushButton:disabled { color: #6d7677; background: #1a2023; border-color: #282f32; }
QPushButton#play { background: #dcba78; color: #191d1b; font-size: 19px; font-weight: 800; padding: 17px; border: none; }
QPushButton#play:hover { background: #f0cf8a; }
QPushButton#play:disabled { background: #5d5643; color: #a59d89; }
QLineEdit, QSpinBox { background: #0b1012; border: 1px solid #344147; padding: 8px; border-radius: 4px; }
QProgressBar { border: none; background: #26302f; border-radius: 3px; height: 6px; }
QProgressBar::chunk { background: #dcba78; border-radius: 3px; }
QPlainTextEdit { background: #0c1113; border: 1px solid #263135; padding: 8px; color: #97aba2; font-family: monospace; font-size: 11px; }
"""


class Landscape(QWidget):
    def paintEvent(self, event):
        p = QPainter(self)
        gradient = QLinearGradient(0, 0, self.width(), self.height())
        gradient.setColorAt(0, QColor("#263b37"))
        gradient.setColorAt(1, QColor("#10191a"))
        p.fillRect(self.rect(), gradient)
        p.setPen(Qt.PenStyle.NoPen)
        w, h = self.width(), self.height()
        p.setBrush(QColor("#b89b60"))
        p.drawEllipse(QPointF(w * .81, h * .23), 39, 39)
        for points, color in [([(0,.85),(.2,.4),(.35,.67),(.56,.32),(.82,.8),(1,.48)], "#304b43"),
                              ([(0,.94),(.28,.65),(.4,.85),(.66,.5),(.82,.7),(1,.52)], "#21372f"),
                              ([(0,.9),(.24,.8),(.45,.95),(.67,.73),(.87,.92),(1,.75)], "#172922")]:
            poly = QPolygonF([QPointF(x*w,y*h) for x,y in points] + [QPointF(w,h), QPointF(0,h)])
            p.setBrush(QColor(color))
            p.drawPolygon(poly)
        shade = QLinearGradient(0,0,w,0)
        shade.setColorAt(0,QColor(8,18,17,190))
        shade.setColorAt(1,QColor(8,18,17,0))
        p.fillRect(self.rect(),shade)
        p.end()


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
                "HTTPError": "Download o servizio non disponibile. Controlla che repository, pack.json e release siano pubblici e corretti.",
                "ConnectionError": "Connessione non disponibile. Controlla Internet e riprova.",
                "Timeout": "La connessione è scaduta. Riprova.",
                "ReadTimeout": "La connessione è scaduta. Riprova.",
            }
            # Third-party exception strings can contain credentials/response bodies.
            message = friendly.get(kind)
            if message is None:
                message = str(exc) if type(exc) in (ValueError, RuntimeError) else f"Operazione non riuscita ({kind}). Controlla configurazione e connessione."
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
            ("java_path", "Java 17 (vuoto = automatico)", "Percorso di java / java.exe")]:
            field = QLineEdit(str(cfg.get(key, "")))
            field.setPlaceholderText(placeholder)
            self.fields[key] = field
            form.addRow(title, field)
        self.ram = QSpinBox()
        self.ram.setRange(2048,32768)
        self.ram.setSingleStep(1024)
        self.ram.setSuffix(" MB")
        self.ram.setValue(int(cfg.get("ram_mb",6144)))
        form.addRow("RAM massima", self.ram)
        note = QLabel("Per distribuire il launcher, imposta questi valori in launcher-config.json prima della build.\nL'indirizzo pubblicato nel modpack ha precedenza sul server di riserva.")
        note.setWordWrap(True)
        form.addRow(note)
        buttons = QDialogButtonBox(QDialogButtonBox.StandardButton.Save | QDialogButtonBox.StandardButton.Cancel)
        buttons.accepted.connect(self.accept)
        buttons.rejected.connect(self.reject)
        form.addRow(buttons)

    def values(self):
        return {**{k:v.text().strip() for k,v in self.fields.items()}, "ram_mb": self.ram.value()}


class Window(QMainWindow):
    def __init__(self):
        super().__init__()
        self.cfg = load_config()
        self.session = None
        self.worker = None
        self.failed = False
        self.setWindowTitle("Nuovo Ordine • Launcher")
        self.resize(1080, 740)
        self.setMinimumSize(950, 700)
        root = QWidget()
        self.setCentralWidget(root)
        row = QHBoxLayout(root)
        row.setContentsMargins(0,0,0,0)
        row.setSpacing(0)
        sidebar = QFrame()
        sidebar.setObjectName("sidebar")
        sidebar.setFixedWidth(236)
        side = QVBoxLayout(sidebar)
        side.setContentsMargins(22,32,22,25)
        side.setSpacing(16)
        brand = QLabel("NUOVO\nORDINE")
        brand.setObjectName("brand")
        side.addWidget(brand)
        small = QLabel("MINECRAFT COMMUNITY")
        small.setObjectName("muted")
        side.addWidget(small)
        side.addSpacing(28)
        self.account = QLabel("Il tuo account\nNon connesso")
        self.account.setWordWrap(True)
        side.addWidget(self.account)
        self.login_button = QPushButton("Accedi con Microsoft")
        self.login_button.clicked.connect(self.login)
        side.addWidget(self.login_button)
        self.logout_button = QPushButton("Disconnetti")
        self.logout_button.clicked.connect(self.logout)
        side.addWidget(self.logout_button)
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
        version = QLabel(f"LAUNCHER {VERSION}\nClient indipendente, non ufficiale")
        version.setObjectName("muted")
        side.addWidget(version)
        row.addWidget(sidebar)
        content = QVBoxLayout()
        content.setContentsMargins(30,28,30,24)
        content.setSpacing(17)
        top = QHBoxLayout()
        label = QLabel("IL TUO PROSSIMO CAPITOLO")
        label.setObjectName("eyebrow")
        top.addWidget(label)
        top.addStretch()
        badge = QLabel("MINECRAFT 1.20.1  /  FORGE")
        badge.setObjectName("badge")
        top.addWidget(badge)
        content.addLayout(top)
        hero = Landscape()
        hero.setMinimumHeight(285)
        hero_layout = QVBoxLayout(hero)
        hero_layout.setContentsMargins(30,28,30,28)
        hero_layout.addStretch()
        title = QLabel("NUOVO\nORDINE")
        title.setObjectName("title")
        hero_layout.addWidget(title)
        sub = QLabel("Il tuo mondo. Le tue regole.")
        sub.setObjectName("subtitle")
        hero_layout.addWidget(sub)
        content.addWidget(hero)
        self.news = QLabel("Configura il repository per scaricare il modpack del server.")
        self.news.setWordWrap(True)
        self.news.setTextFormat(Qt.TextFormat.PlainText)
        content.addWidget(self.news)
        actions = QHBoxLayout()
        self.play_button = QPushButton("GIOCA OFFLINE  →")
        self.play_button.setObjectName("play")
        self.play_button.clicked.connect(self.play)
        actions.addWidget(self.play_button,2)
        self.update_button = QPushButton("Verifica / aggiorna mod")
        self.update_button.clicked.connect(self.update_pack)
        actions.addWidget(self.update_button,1)
        content.addLayout(actions)
        self.status_label = QLabel("Pronto • senza login puoi giocare solo offline")
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
        content.addWidget(self.log,1)
        row.addLayout(content,1)
        self.controls = [self.play_button,self.update_button,self.login_button,self.logout_button,self.settings_button]
        self.refresh_auth_controls()
        if self.cfg.get("repository"):
            QTimer.singleShot(300,self.update_pack)

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
            self.account.setText("Il tuo account\nNon connesso • modalità offline")

    def report(self, text):
        self.status_label.setText(text)
        # Avoid flooding UI with per-chunk output.
        if not text.startswith("Download "):
            self.log.appendPlainText(text)

    def begin(self, job, completed=None):
        if self.worker and self.worker.isRunning():
            return
        for c in self.controls:
            c.setEnabled(False)
        self.failed = False
        self.bar.setRange(0,0)
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
            self.bar.setRange(0,max(1,maximum))
        self.bar.setValue(n)

    def finish(self):
        self.bar.setRange(0,100)
        self.bar.setValue(0 if self.failed else 100)
        self.refresh_auth_controls()

    def error(self,message):
        self.failed = True
        self.report(message)
        QMessageBox.warning(self,"Nuovo Ordine",message)

    def set_account(self,data):
        self.session = data
        self.refresh_auth_controls()

    def login(self):
        try:
            validate_config(self.cfg,require_login=True)
        except ValueError as exc:
            self.error(str(exc))
            return
        self.begin(lambda w: auth.login(self.cfg["microsoft_client_id"],w.status.emit,w.browser.emit),self.set_account)

    def logout(self):
        try:
            auth.forget(self.cfg.get("microsoft_client_id", ""))
            self.session = None
            self.refresh_auth_controls()
            self.report("Account disconnesso. Ora il launcher avvierà Minecraft in modalità offline.")
        except RuntimeError as exc:
            self.error(str(exc))

    def pack_ready(self,pack):
        self.news.setText(str(pack.get("news", "Benvenuto su Nuovo Ordine.")))

    def update_pack(self):
        self.begin(lambda w: engine.get_pack(self.cfg,w.status.emit),self.pack_ready)

    def play(self):
        self.begin(lambda w: engine.play(self.cfg,self.session,w.status.emit,w.progress.emit,w.account.emit),self.pack_ready)

    def settings(self):
        dialog = Settings(self.cfg,self)
        if dialog.exec() == QDialog.DialogCode.Accepted:
            values = dialog.values()
            if values.get("microsoft_client_id") != self.cfg.get("microsoft_client_id"):
                try:
                    auth.forget(self.cfg.get("microsoft_client_id", ""))
                except RuntimeError as exc:
                    self.error(str(exc))
                    return
                self.session = None
            self.cfg = values
            atomic_json(DATA / "settings.json",values)
            self.refresh_auth_controls()
            self.report("Impostazioni salvate. Premi Verifica / aggiorna mod.")

    def open_releases(self):
        try:
            validate_config(self.cfg)
            QDesktopServices.openUrl(QUrl(f'https://github.com/{self.cfg["repository"]}/releases'))
        except ValueError as exc:
            self.error(str(exc))

    def closeEvent(self,event):
        if self.worker and self.worker.isRunning():
            QMessageBox.information(self,"Operazione in corso","Attendi la fine dell'operazione o chiudi Minecraft prima di uscire. Il login nel browser scade dopo 3 minuti.")
            event.ignore()
        else:
            event.accept()


def main():
    app = QApplication(sys.argv)
    app.setApplicationName("Nuovo Ordine")
    app.setStyleSheet(STYLE)
    DATA.mkdir(parents=True,exist_ok=True)
    lock = QLockFile(str(DATA / "launcher.lock"))
    lock.setStaleLockTime(0)
    if not lock.tryLock(100):
        QMessageBox.information(None,"Nuovo Ordine","Il launcher è già aperto.")
        return
    window = Window()
    window.show()
    app.exec()
    lock.unlock()
