import io
from pathlib import Path

from PIL import Image, ImageColor
from PySide6.QtWidgets import (
    QDialog, QVBoxLayout, QHBoxLayout, QPushButton, QLabel, QFileDialog,
    QMessageBox, QColorDialog, QComboBox, QCheckBox, QSpinBox,
)
from PySide6.QtGui import QPixmap, QColor
from PySide6.QtCore import Qt, QTimer

from .config import INSTANCE
from .cosmetics import (
    MAX_CAPE_IMPORT, cape_frame_count, create_cape, normalize_frame_ms,
    prepare_cape_import, remove_texture, save_texture,
)


class CapeCanvas(QLabel):
    def __init__(self, paint, parent=None):
        super().__init__(parent)
        self.paint_pixel = paint

    def mousePressEvent(self, event):
        self.draw(event)

    def mouseMoveEvent(self, event):
        if event.buttons() & Qt.LeftButton:
            self.draw(event)

    def draw(self, event):
        pix = self.pixmap()
        if pix is None:
            return
        x = event.position().x() - (self.width() - pix.width()) / 2
        y = event.position().y() - (self.height() - pix.height()) / 2
        if 0 <= x < pix.width() and 0 <= y < pix.height():
            self.paint_pixel(int(x * 10 / pix.width()), int(y * 16 / pix.height()))


class CosmeticsDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle("Skin e laboratorio mantelli")
        self.setMinimumWidth(500)
        self.base, self.accent = "#151b25", "#29b6f6"
        self.png = b""
        self.frames = 1
        self.frame_index = 0
        self.frame_ms = 0
        self.preview_timer = QTimer(self)
        self.preview_timer.timeout.connect(self.advance_frame)

        layout = QVBoxLayout(self)
        text = QLabel(
            "Importa la skin e crea il tuo mantello.\n"
            "I mantelli possono essere statici o animati (GIF/APNG o sprite PNG verticale).\n"
            "Le modifiche vengono caricate al prossimo ingresso nel server e sono visibili "
            "agli altri giocatori con Nuovo Ordine Cosmetics."
        )
        text.setWordWrap(True)
        layout.addWidget(text)

        for kind, title in [("skin", "Importa skin PNG"), ("cape", "Importa mantello PNG / GIF / APNG")]:
            row = QHBoxLayout()
            button = QPushButton(title)
            button.clicked.connect(lambda checked=False, k=kind: self.import_texture(k))
            row.addWidget(button)
            remove = QPushButton("Ripristina originale")
            remove.clicked.connect(lambda checked=False, k=kind: self.remove(k))
            row.addWidget(remove)
            layout.addLayout(row)

        self.slim = QCheckBox("Skin con braccia sottili (Alex)")
        self.slim.setChecked((INSTANCE / "config/nuovoordine-cosmetics/slim").exists())
        self.slim.toggled.connect(self.set_slim)
        layout.addWidget(self.slim)

        self.pattern = QComboBox()
        self.pattern.addItems(["Striscia", "Croce", "Bordo"])
        self.pattern.currentIndexChanged.connect(self.preview)
        regenerate = QPushButton("Applica colori e motivo (sostituisce il disegno)")
        regenerate.clicked.connect(self.preview)
        layout.addWidget(regenerate)
        layout.addWidget(self.pattern)

        for key, label in [("base", "Colore mantello"), ("accent", "Colore motivo")]:
            button = QPushButton(label)
            button.clicked.connect(lambda checked=False, k=key: self.color(k))
            layout.addWidget(button)

        animation_row = QHBoxLayout()
        animation_row.addWidget(QLabel("Velocità animazione"))
        self.fps = QSpinBox()
        self.fps.setRange(1, 25)
        self.fps.setValue(10)
        self.fps.setSuffix(" FPS")
        self.fps.setEnabled(False)
        self.fps.valueChanged.connect(self.change_fps)
        animation_row.addWidget(self.fps)
        animation_row.addStretch()
        layout.addLayout(animation_row)

        self.face = QComboBox()
        self.face.addItems(["Retro del mantello", "Interno del mantello"])
        self.face.currentIndexChanged.connect(self.render_cape)
        layout.addWidget(self.face)
        layout.addWidget(QLabel("Disegna sull’anteprima con il colore del motivo. Nei mantelli animati modifichi il frame visibile."))

        self.image = CapeCanvas(self.paint_pixel)
        self.image.setAlignment(Qt.AlignCenter)
        layout.addWidget(self.image)

        save = QPushButton("Usa questo mantello")
        save.clicked.connect(self.save_cape)
        layout.addWidget(save)
        self.status = QLabel("")
        self.status.setWordWrap(True)
        layout.addWidget(self.status)
        self.preview()

    def set_slim(self, enabled):
        path = INSTANCE / "config/nuovoordine-cosmetics/slim"
        path.parent.mkdir(parents=True, exist_ok=True)
        if enabled:
            path.touch()
        else:
            path.unlink(missing_ok=True)

    def color(self, key):
        color = QColorDialog.getColor(QColor(getattr(self, key)), self)
        if color.isValid():
            setattr(self, key, color.name())

    def set_preview_data(self, data, frame_ms=0):
        self.png = data
        self.frames = cape_frame_count(data)
        self.frame_index = 0
        self.frame_ms = normalize_frame_ms(frame_ms or 100) if self.frames > 1 else 0
        self.fps.blockSignals(True)
        self.fps.setEnabled(self.frames > 1)
        if self.frames > 1:
            self.fps.setValue(max(1, min(25, round(1000 / self.frame_ms))))
            self.preview_timer.start(self.frame_ms)
        else:
            self.preview_timer.stop()
        self.fps.blockSignals(False)
        self.render_cape()

    def preview(self):
        data = create_cape(
            self.base,
            self.accent,
            ["stripe", "cross", "border"][self.pattern.currentIndex()],
        )
        self.set_preview_data(data, 0)

    def change_fps(self, fps):
        if self.frames <= 1:
            return
        self.frame_ms = normalize_frame_ms(round(1000 / max(1, fps)))
        self.preview_timer.setInterval(self.frame_ms)
        self.status.setText("Velocità modificata. Premi “Usa questo mantello” per salvarla.")

    def advance_frame(self):
        if self.frames <= 1:
            return
        self.frame_index = (self.frame_index + 1) % self.frames
        self.render_cape()

    def render_cape(self):
        if not self.png:
            return
        pix = QPixmap()
        if not pix.loadFromData(self.png):
            return
        x = 1 if self.face.currentIndex() == 0 else 12
        y = self.frame_index * 32 + 1
        self.image.setPixmap(
            pix.copy(x, y, 10, 16).scaled(120, 192, Qt.KeepAspectRatio, Qt.FastTransformation)
        )

    def paint_pixel(self, x, y):
        if not self.png:
            return
        image = Image.open(io.BytesIO(self.png)).convert("RGBA")
        offset_x = 1 if self.face.currentIndex() == 0 else 12
        offset_y = self.frame_index * 32 + 1
        image.putpixel((offset_x + x, offset_y + y), ImageColor.getcolor(self.accent, "RGBA"))
        out = io.BytesIO()
        image.save(out, format="PNG", optimize=True)
        self.png = out.getvalue()
        self.render_cape()

    def save_cape(self):
        try:
            save_texture(INSTANCE, "cape", self.png, self.frame_ms)
            if self.frames > 1:
                self.status.setText(f"Mantello animato salvato: {self.frames} frame a circa {round(1000 / self.frame_ms)} FPS. Rientra nel server per applicarlo.")
            else:
                self.status.setText("Mantello salvato. Rientra nel server per applicarlo.")
        except Exception as exc:
            QMessageBox.warning(self, "Mantello non valido", str(exc))

    def import_texture(self, kind):
        file_filter = "PNG (*.png)" if kind == "skin" else "Mantelli (*.png *.gif *.apng *.webp)"
        path, _ = QFileDialog.getOpenFileName(self, "Scegli immagine", "", file_filter)
        if not path:
            return
        try:
            raw = Path(path).read_bytes()
            if kind == "skin":
                save_texture(INSTANCE, "skin", raw)
                self.status.setText("Skin salvata. Rientra nel server per applicarla.")
                return
            if len(raw) > MAX_CAPE_IMPORT:
                raise ValueError("File sorgente troppo grande (massimo 8 MB).")
            data, frame_ms, frames = prepare_cape_import(raw)
            save_texture(INSTANCE, "cape", data, frame_ms)
            self.set_preview_data(data, frame_ms)
            if frames > 1:
                self.status.setText(f"Mantello animato importato: {frames} frame a circa {round(1000 / self.frame_ms)} FPS.")
            else:
                self.status.setText("Mantello statico importato.")
        except Exception as exc:
            QMessageBox.warning(self, "Immagine non valida", str(exc))

    def remove(self, kind):
        remove_texture(INSTANCE, kind)
        if kind == "cape":
            self.preview()
        self.status.setText("Ripristinato. Rientra nel server per applicarlo.")
