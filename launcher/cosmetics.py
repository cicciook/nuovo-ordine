"""Cosmetic import helpers shared by the launcher and Forge companion."""
import io
import os
from pathlib import Path

from PIL import Image, ImageDraw

MAX_SKIN_PNG = 32 * 1024
# Keep the serialized Forge payload below the practical custom-packet ceiling.
MAX_CAPE_PNG = 900 * 1024
MAX_CAPE_FRAMES = 24
CAPE_WIDTHS = (64, 128, 256, 512)
DEFAULT_CAPE_SCALE = 4
SIZES = {"skin": {(64, 64)}}


def _normalized_png(image):
    out = io.BytesIO()
    image.convert("RGBA").save(out, format="PNG", optimize=True)
    return out.getvalue()


def _cape_geometry(width, height):
    if width not in CAPE_WIDTHS:
        raise ValueError("Mantello: larghezza supportata 64, 128, 256 o 512 px.")
    frame_height = width // 2
    if height < frame_height or height % frame_height:
        raise ValueError(
            "Mantello: ogni frame deve mantenere rapporto 2:1 "
            "(64×32, 128×64, 256×128 o 512×256)."
        )
    frames = height // frame_height
    if frames > MAX_CAPE_FRAMES:
        raise ValueError(f"Troppi frame: massimo {MAX_CAPE_FRAMES}.")
    return frame_height, frames


def validate_png(data, kind):
    if kind not in ("skin", "cape"):
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
            _cape_geometry(*image.size)
        image.load()
        result = _normalized_png(image)
    if len(result) > limit:
        raise ValueError(
            f"Immagine troppo grande dopo la conversione (massimo {limit // 1024} KB)."
        )
    return result


def cape_info(data):
    normalized = validate_png(data, "cape")
    with Image.open(io.BytesIO(normalized)) as image:
        frame_height, frames = _cape_geometry(*image.size)
        return image.width, frame_height, frames


def cape_frame_count(data):
    return cape_info(data)[2]


def prepare_cape_file(path):
    """Convert PNG/GIF/APNG cape input into a vertical HD cape sprite-sheet."""
    path = Path(path)
    with Image.open(path) as image:
        frame_count = int(getattr(image, "n_frames", 1) or 1)
        if frame_count <= 1:
            if image.format == "PNG":
                return validate_png(path.read_bytes(), "cape")
            _cape_geometry(*image.size)
            return validate_png(_normalized_png(image), "cape")

        if frame_count > MAX_CAPE_FRAMES:
            raise ValueError(f"Troppi frame: massimo {MAX_CAPE_FRAMES}.")

        frames = []
        expected_size = None
        for index in range(frame_count):
            image.seek(index)
            frame = image.convert("RGBA")
            _cape_geometry(*frame.size)
            if expected_size is None:
                expected_size = frame.size
            if frame.size != expected_size:
                raise ValueError("Tutti i frame del mantello devono avere la stessa risoluzione.")
            frames.append(frame.copy())

    width, frame_height = expected_size
    sheet = Image.new(
        "RGBA", (width, frame_height * len(frames)), (0, 0, 0, 0)
    )
    for index, frame in enumerate(frames):
        sheet.paste(frame, (0, index * frame_height))
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


def create_cape(base="#151b25", accent="#29b6f6", pattern="stripe", scale=DEFAULT_CAPE_SCALE):
    if scale not in (1, 2, 4, 8):
        raise ValueError("Scala mantello non valida.")
    image = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # Vanilla cape UV: 10×16 front/back, 1 pixel depth.
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
    if scale != 1:
        image = image.resize((64 * scale, 32 * scale), Image.Resampling.NEAREST)
    return _normalized_png(image)
