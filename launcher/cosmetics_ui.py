import io
from pathlib import Path

from PIL import Image
from PySide6.QtWidgets import (
    QDialog,
    QVBoxLayout,
    QHBoxLayout,
    QPushButton,
    QLabel,
    QFileDialog,
    QMessageBox,
    QColorDialog,
    QComboBox,
    QCheckBox,
)
from PySide6.QtGui import QPixmap, QColor
from PySide6.QtCore import Qt

from .config import INSTANCE
from .cosmetics import cape_info, create_cape, save_texture, save_texture_file


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
        self.setMinimumWidth(470)
        self.base, self.accent = "#151b25", "#29b6f6"
        layout = QVBoxLayout(self)
        text = QLabel(
            "Importa la skin e crea il tuo mantello.\n"
            "I mantelli possono essere statici PNG oppure animati GIF/APNG in 64×32, 128×64, 256×128 o 512×256 per frame. "
            "Le animazioni vengono convertite in massimo 24 frame e riprodotte in game a 10 FPS.\n"
            "Le modifiche vengono caricate al prossimo ingresso nel server.\n"
            "Visibili agli altri giocatori con Nuovo Ordine Cosmetics."
        )
        text.setWordWrap(True)
        layout.addWidget(text)

        for kind, title in [
            ("skin", "Importa skin PNG"),
            ("cape", "Importa mantello PNG/GIF/APNG"),
        ]:
            row = QHBoxLayout()
            button = QPushButton(title)
            button.clicked.connect(lambda checked=False, k=kind: self.import_image(k))
            row.addWidget(button)
            remove = QPushButton("Ripristina originale")
            remove.clicked.connect(lambda checked=False, k=kind: self.remove(k))
            row.addWidget(remove)
            layout.addLayout(row)

        self.slim = QCheckBox("Skin con braccia sottili (Alex)")
        self.slim.setChecked((INSTANCE / "config/nuovoordine-cosmetics/slim").exists())
        self.slim.toggled.connect(self.set_slim)
        layout.addWidget(self.slim)

        self.resolution = QComboBox()
        self.resolution.addItems(["64×32", "128×64", "256×128 (HD)", "512×256 (HD+)"])
        self.resolution.setCurrentIndex(2)
        self.resolution.currentIndexChanged.connect(self.preview)
        layout.addWidget(QLabel("Risoluzione mantello"))
        layout.addWidget(self.resolution)

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

        self.face = QComboBox()
        self.face.addItems(["Retro del mantello", "Interno del mantello"])
        self.face.currentIndexChanged.connect(self.render_cape)
        layout.addWidget(self.face)
        layout.addWidget(QLabel("Disegna sull’anteprima con il colore del motivo."))

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

    def preview(self):
        self.png = create_cape(
            self.base,
            self.accent,
            ["stripe", "cross", "border"][self.pattern.currentIndex()],
            scale=[1, 2, 4, 8][self.resolution.currentIndex()],
        )
        if hasattr(self, "image"):
            self.render_cape()

    def render_cape(self):
        if not hasattr(self, "png"):
            return
        pix = QPixmap()
        pix.loadFromData(self.png)
        scale = max(1, pix.width() // 64)
        x = (1 if self.face.currentIndex() == 0 else 12) * scale
        self.image.setPixmap(
            pix.copy(x, 1 * scale, 10 * scale, 16 * scale).scaled(
                120, 192, Qt.KeepAspectRatio, Qt.FastTransformation
            )
        )

    def paint_pixel(self, x, y):
        image = Image.open(io.BytesIO(self.png)).convert("RGBA")
        from PIL import ImageColor

        scale = max(1, image.width // 64)
        offset = (1 if self.face.currentIndex() == 0 else 12) * scale
        color = ImageColor.getcolor(self.accent, "RGBA")
        for px in range(scale):
            for py in range(scale):
                image.putpixel(
                    (offset + x * scale + px, scale + y * scale + py),
                    color,
                )
        out = io.BytesIO()
        image.save(out, format="PNG")
        self.png = out.getvalue()
        self.render_cape()

    def save_cape(self):
        save_texture(INSTANCE, "cape", self.png)
        self.status.setText("Mantello statico salvato. Rientra nel server per applicarlo.")

    def import_image(self, kind):
        if kind == "cape":
            filters = "Mantelli (*.png *.gif *.apng);;PNG (*.png);;GIF (*.gif);;Tutti i file (*)"
        else:
            filters = "PNG (*.png)"
        path, _ = QFileDialog.getOpenFileName(self, "Scegli immagine", "", filters)
        if not path:
            return
        try:
            target = save_texture_file(INSTANCE, kind, Path(path))
            if kind == "cape":
                width, frame_height, frames = cape_info(target.read_bytes())
                if frames > 1:
                    self.status.setText(
                        f"Mantello animato {width}×{frame_height} salvato: {frames} frame a 10 FPS. "
                        "Rientra nel server per applicarlo."
                    )
                else:
                    self.status.setText(
                        f"Mantello {width}×{frame_height} salvato. Rientra nel server per applicarlo."
                    )
            else:
                self.status.setText("Skin salvata. Rientra nel server per applicarla.")
        except Exception as exc:
            QMessageBox.warning(self, "Immagine non valida", str(exc))

    def remove(self, kind):
        (INSTANCE / "config" / "nuovoordine-cosmetics" / (kind + ".png")).unlink(
            missing_ok=True
        )
        self.status.setText("Ripristinato. Rientra nel server per applicarlo.")
