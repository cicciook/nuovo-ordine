"""Safe skin/cape imports and local cosmetic hand-off to the Forge companion."""
import io
import os
from pathlib import Path

from PIL import Image, ImageDraw

MAX_SKIN_PNG = 32 * 1024
MAX_CAPE_PNG = 512 * 1024
MAX_CAPE_IMPORT = 8 * 1024 * 1024
MAX_CAPE_FRAMES = 64
CAPE_WIDTH = 64
CAPE_FRAME_HEIGHT = 32
SIZES = {"skin": {(64, 64)}}


def _atomic_bytes(path, data):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_bytes(data)
    os.replace(tmp, path)


def _atomic_text(path, value):
    path = Path(path)
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_name(path.name + ".tmp")
    tmp.write_text(value, encoding="ascii")
    os.replace(tmp, path)


def normalize_frame_ms(value):
    return max(40, min(1000, int(value)))


def cape_frame_count(data):
    with Image.open(io.BytesIO(data)) as image:
        if image.format != "PNG":
            raise ValueError("Il mantello interno deve essere un PNG.")
        width, height = image.size
        if width != CAPE_WIDTH or height < CAPE_FRAME_HEIGHT or height % CAPE_FRAME_HEIGHT:
            raise ValueError("Mantello: PNG 64×32 oppure sprite verticale 64×(32×frame).")
        frames = height // CAPE_FRAME_HEIGHT
        if frames > MAX_CAPE_FRAMES:
            raise ValueError(f"Troppi frame: massimo {MAX_CAPE_FRAMES}.")
        return frames


def validate_png(data, kind):
    if kind == "skin":
        if len(data) > MAX_SKIN_PNG:
            raise ValueError("Skin troppo grande (massimo 32 KB).")
        with Image.open(io.BytesIO(data)) as image:
            if image.format != "PNG" or image.size not in SIZES[kind] or getattr(image, "n_frames", 1) != 1:
                raise ValueError("Skin: PNG 64×64.")
            image.load()
            result = io.BytesIO()
            image.convert("RGBA").save(result, format="PNG", optimize=True)
        normalized = result.getvalue()
        if len(normalized) > MAX_SKIN_PNG:
            raise ValueError("Skin troppo grande (massimo 32 KB).")
        return normalized

    if kind != "cape":
        raise ValueError("Tipo texture non valido.")
    if len(data) > MAX_CAPE_PNG:
        raise ValueError("Mantello troppo grande (massimo 512 KB).")
    frames = cape_frame_count(data)
    with Image.open(io.BytesIO(data)) as image:
        image.load()
        result = io.BytesIO()
        image.convert("RGBA").save(result, format="PNG", optimize=True)
    normalized = result.getvalue()
    if len(normalized) > MAX_CAPE_PNG:
        raise ValueError("Mantello troppo grande (massimo 512 KB).")
    if cape_frame_count(normalized) != frames:
        raise ValueError("Mantello animato non valido.")
    return normalized


def prepare_cape_import(data):
    """Convert GIF/APNG/WebP or a PNG sprite sheet into a vertical 64x32 frame PNG.

    Returns ``(png_bytes, frame_ms, frame_count)``. Static capes use frame_ms=0.
    """
    if len(data) > MAX_CAPE_IMPORT:
        raise ValueError("File sorgente troppo grande (massimo 8 MB).")
    try:
        image = Image.open(io.BytesIO(data))
    except OSError as exc:
        raise ValueError("Immagine mantello non leggibile.") from exc

    with image:
        frame_total = int(getattr(image, "n_frames", 1) or 1)
        if frame_total == 1 and image.format == "PNG" and image.width == CAPE_WIDTH \
                and image.height >= CAPE_FRAME_HEIGHT and image.height % CAPE_FRAME_HEIGHT == 0:
            normalized = validate_png(data, "cape")
            frames = cape_frame_count(normalized)
            return normalized, (100 if frames > 1 else 0), frames

        if frame_total > MAX_CAPE_FRAMES:
            raise ValueError(f"Animazione troppo lunga: massimo {MAX_CAPE_FRAMES} frame.")
        if frame_total < 1:
            raise ValueError("Animazione senza frame.")

        frames = []
        durations = []
        for index in range(frame_total):
            image.seek(index)
            frame = image.convert("RGBA")
            if frame.size != (CAPE_WIDTH, CAPE_FRAME_HEIGHT):
                raise ValueError("Ogni frame del mantello deve essere 64×32 pixel.")
            frames.append(frame.copy())
            duration = int(image.info.get("duration") or 100)
            durations.append(normalize_frame_ms(duration))

        sheet = Image.new("RGBA", (CAPE_WIDTH, CAPE_FRAME_HEIGHT * len(frames)), (0, 0, 0, 0))
        for index, frame in enumerate(frames):
            sheet.paste(frame, (0, CAPE_FRAME_HEIGHT * index))
        out = io.BytesIO()
        sheet.save(out, format="PNG", optimize=True)
        normalized = validate_png(out.getvalue(), "cape")
        frame_ms = 0 if len(frames) == 1 else normalize_frame_ms(round(sum(durations) / len(durations)))
        return normalized, frame_ms, len(frames)


def save_texture(instance, kind, data, frame_ms=None):
    data = validate_png(data, kind)
    root = Path(instance) / "config" / "nuovoordine-cosmetics"
    target = root / (kind + ".png")
    _atomic_bytes(target, data)

    if kind == "cape":
        meta = root / "cape.frame_ms"
        frames = cape_frame_count(data)
        if frames > 1:
            _atomic_text(meta, str(normalize_frame_ms(100 if frame_ms is None else frame_ms)))
        else:
            meta.unlink(missing_ok=True)
    return target


def remove_texture(instance, kind):
    root = Path(instance) / "config" / "nuovoordine-cosmetics"
    (root / (kind + ".png")).unlink(missing_ok=True)
    if kind == "cape":
        (root / "cape.frame_ms").unlink(missing_ok=True)


def create_cape(base="#151b25", accent="#29b6f6", pattern="stripe"):
    image = Image.new("RGBA", (64, 32), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
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
    out = io.BytesIO()
    image.save(out, format="PNG")
    return out.getvalue()
