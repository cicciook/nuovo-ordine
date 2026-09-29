from PySide6.QtCore import Qt, QRectF
from PySide6.QtGui import QPainter, QPainterPath, QPixmap


def _rounded_pixmap(source, radius=18):
    if source is None or source.isNull():
        return source
    result = QPixmap(source.size())
    result.fill(Qt.GlobalColor.transparent)
    painter = QPainter(result)
    painter.setRenderHint(QPainter.RenderHint.Antialiasing, True)
    path = QPainterPath()
    path.addRoundedRect(QRectF(result.rect()), radius, radius)
    painter.setClipPath(path)
    painter.drawPixmap(0, 0, source)
    painter.end()
    return result


def apply(ui_module):
    """Apply small visual refinements without changing launcher behavior."""
    original_init = ui_module.Window.__init__

    def patched_init(self, *args, **kwargs):
        original_init(self, *args, **kwargs)

        # The old logo container shared the same bordered style as the community card.
        # Make only the logo container transparent and borderless.
        logo_frame = self.logo.parentWidget()
        if logo_frame is not None:
            logo_frame.setObjectName("logoFrame")
            logo_frame.setStyleSheet(
                "QFrame#logoFrame { background: transparent; border: none; }"
            )
            if logo_frame.layout() is not None:
                logo_frame.layout().setContentsMargins(0, 0, 0, 0)

        self.logo.setStyleSheet("background: transparent; border: none;")
        pixmap = self.logo.pixmap()
        if pixmap is not None and not pixmap.isNull():
            self.logo.setPixmap(_rounded_pixmap(pixmap, 18))

        # Firma GitHub sotto la versione, come richiesto.
        for label in self.findChildren(ui_module.QLabel):
            text = label.text()
            if text.startswith("LAUNCHER ") and "Aggiornamento automatico attivo" in text:
                label.setText(
                    f"LAUNCHER {ui_module.VERSION}\n"
                    "GITHUB • cicciook\n"
                    "Aggiornamento automatico attivo"
                )
                break

    ui_module.Window.__init__ = patched_init
