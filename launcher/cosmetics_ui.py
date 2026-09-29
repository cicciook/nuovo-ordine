from pathlib import Path
from PySide6.QtWidgets import (QDialog, QVBoxLayout, QHBoxLayout, QPushButton,
    QLabel, QFileDialog, QMessageBox, QColorDialog, QComboBox, QCheckBox)
from PySide6.QtGui import QPixmap, QColor
from PySide6.QtCore import Qt
from .config import INSTANCE
from .cosmetics import save_texture, create_cape


class CosmeticsDialog(QDialog):
    def __init__(self, parent=None):
        super().__init__(parent)
        self.setWindowTitle('Skin e laboratorio mantelli')
        self.setMinimumWidth(470)
        self.base, self.accent = '#151b25', '#29b6f6'
        layout = QVBoxLayout(self)
        text = QLabel('Importa la skin e crea il tuo mantello.\nLe modifiche vengono caricate al prossimo ingresso nel server.\nVisibili agli altri giocatori con Nuovo Ordine Cosmetics.')
        text.setWordWrap(True)
        layout.addWidget(text)
        for kind, title in [('skin', 'Importa skin PNG'), ('cape', 'Importa mantello PNG')]:
            row = QHBoxLayout()
            button = QPushButton(title)
            button.clicked.connect(lambda checked=False, k=kind: self.import_png(k))
            row.addWidget(button)
            remove = QPushButton('Ripristina originale')
            remove.clicked.connect(lambda checked=False, k=kind: self.remove(k))
            row.addWidget(remove)
            layout.addLayout(row)
        self.slim = QCheckBox('Skin con braccia sottili (Alex)')
        self.slim.setChecked((INSTANCE / 'config/nuovoordine-cosmetics/slim').exists())
        self.slim.toggled.connect(self.set_slim)
        layout.addWidget(self.slim)
        self.pattern = QComboBox()
        self.pattern.addItems(['Striscia', 'Croce', 'Bordo'])
        self.pattern.currentIndexChanged.connect(self.preview)
        layout.addWidget(self.pattern)
        for key, label in [('base', 'Colore mantello'), ('accent', 'Colore motivo')]:
            b = QPushButton(label)
            b.clicked.connect(lambda checked=False, k=key: self.color(k))
            layout.addWidget(b)
        self.image = QLabel()
        self.image.setAlignment(Qt.AlignCenter)
        layout.addWidget(self.image)
        save = QPushButton('Usa questo mantello')
        save.clicked.connect(self.save_cape)
        layout.addWidget(save)
        self.status = QLabel('')
        layout.addWidget(self.status)
        self.preview()

    def set_slim(self, enabled):
        path = INSTANCE / 'config/nuovoordine-cosmetics/slim'
        path.parent.mkdir(parents=True, exist_ok=True)
        if enabled:
            path.touch()
        else:
            path.unlink(missing_ok=True)

    def color(self, key):
        color = QColorDialog.getColor(QColor(getattr(self, key)), self)
        if color.isValid():
            setattr(self, key, color.name())
            self.preview()

    def preview(self):
        self.png = create_cape(self.base, self.accent, ['stripe', 'cross', 'border'][self.pattern.currentIndex()])
        pix = QPixmap()
        pix.loadFromData(self.png)
        self.image.setPixmap(pix.copy(1, 1, 10, 16).scaled(120, 192, Qt.KeepAspectRatio, Qt.FastTransformation))

    def save_cape(self):
        save_texture(INSTANCE, 'cape', self.png)
        self.status.setText('Mantello salvato. Rientra nel server per applicarlo.')

    def import_png(self, kind):
        path, _ = QFileDialog.getOpenFileName(self, 'Scegli PNG', '', 'PNG (*.png)')
        if not path:
            return
        try:
            if Path(path).stat().st_size > 32768:
                raise ValueError('Dimensione massima: 32 KB.')
            save_texture(INSTANCE, kind, Path(path).read_bytes())
            self.status.setText('Salvato. Rientra nel server per applicarlo.')
        except Exception as exc:
            QMessageBox.warning(self, 'Immagine non valida', str(exc))

    def remove(self, kind):
        (INSTANCE / 'config' / 'nuovoordine-cosmetics' / (kind + '.png')).unlink(missing_ok=True)
        self.status.setText('Ripristinato. Rientra nel server per applicarlo.')
