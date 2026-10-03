"""Cosmetic import helpers shared by the launcher and Forge companion."""
import io
import os
from pathlib import Path

from PIL import Image, ImageDraw

MAX_SKIN_PNG = 32 * 1024
MAX_CAPE_PNG = 256 * 1024
MAX_CAPE_FRAMES = 24
CAPE_SIZE = (64, 32)
SIZES = {"skin": {(64, 64)}, "cape": {CAPE_SIZE}}


def _normalized_png(image):
    out = io.BytesIO()
    image.convert("RGBA").save(out, format="PNG", optimize=True)
    return out.getvalue()


def validate_png(data, kind):
    if kind not in SIZES:
        raise ValueError("Tipo di texture non valido.")
    limit = MAX_SKIN_PNG if kind == "skin" else MAX_CAPE_PNG
    if len(data) > limit:
        raise ValueError(f"Immagine troppo grande (massimo {limit // 1024} KB).")
    with Image.open(io.BytesIO(data)) as image:
        if image.format != "PNG":
            raise ValueError("Il file deve essere un PNG valido.")
        if kind == "skin":
            if image.size != (64, 64):
                raise ValueError("Skin: PNG 64×64.")
        else:
            width, height = image.size
            if width != 64 or height < 32 or height % 32 or height // 32 > MAX_CAPE_FRAMES:
                raise ValueError(
                    f"Mantello: PNG 64×32 oppure sprite-sheet verticale fino a {MAX_CAPE_FRAMES} frame."
                )
        image.load()
        result = _normalized_png(image)
    if len(result) > limit:
        raise ValueError(f"Immagine troppo grande dopo la conversione (massimo {limit // 1024} KB).")
    return result


def cape_frame_count(data):
    normalized = validate_png(data, "cape")
    with Image.open(io.BytesIO(normalized)) as image:
        return image.height // 32


def prepare_cape_file(path):
    """Convert PNG/GIF/APNG cape input into a vertical 64x32-per-frame PNG sheet."""
    path = Path(path)
    with Image.open(path) as image:
        frame_count = int(getattr(image, "n_frames", 1) or 1)
        if frame_count <= 1:
            if image.format == "PNG":
                return validate_png(path.read_bytes(), "cape")
            if image.size != CAPE_SIZE:
                raise ValueError("Mantello statico: immagine 64×32.")
            return validate_png(_normalized_png(image), "cape")

        if frame_count > MAX_CAPE_FRAMES:
            raise ValueError(f"Troppi frame: massimo {MAX_CAPE_FRAMES}.")
        frames = []
        for index in range(frame_count):
            image.seek(index)
            frame = image.convert("RGBA")
            if frame.size != CAPE_SIZE:
                raise ValueError("Ogni frame del mantello animato deve essere 64×32.")
            frames.append(frame.copy())

    sheet = Image.new("RGBA", (64, 32 * len(frames)), (0, 0, 0, 0))
    for index, frame in enumerate(frames):
        sheet.paste(frame, (0, index * 32))
    return validate_png(_normalized_png(sheet), "cape")


def save_texture(instance, kind, data):
    data = validate_png(data, kind)
    target = Path(instance) / "config" / "nuovoordine-cosmetics" / (kind + ".png")
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix(".tmp")
    tmp.write_bytes(data)
    os.replace(tmp, target)
    return target


def save_texture_file(instance, kind, source):
    source = Path(source)
    if kind == "cape":
        data = prepare_cape_file(source)
    else:
        if source.stat().st_size > MAX_SKIN_PNG:
            raise ValueError("Dimensione massima skin: 32 KB.")
        data = source.read_bytes()
    return save_texture(instance, kind, data)


def create_cape(base="#151b25", accent="#29b6f6", pattern="stripe"):
    image = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # Vanilla cape UV: 10×16 front/back, 1 pixel depth; no stretched image.
    draw.rectangle((0, 0, 21, 16), fill=base)
    for x in (1, 12):
        if pattern == "cross":
            draw.rectangle((x + 4, 1, x + 5, 16), fill=accent)
            draw.rectangle((x, 7, x + 9, 9), fill=accent)
        elif pattern == "border":
            draw.rectangle((x, 1, x + 9, 16), outline=accent)
        elif pattern == "stripe":
            draw.rectangle((x + 4, 1, x + 5, 16), fill=accent)
        else:
            raise ValueError("Motivo del mantello non valido.")
    return _normalized_png(image)
