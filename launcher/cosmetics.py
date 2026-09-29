"""Bounded PNG imports and the local cosmetic hand-off to the Forge companion."""
import io
import os
from pathlib import Path
from PIL import Image, ImageDraw

MAX_PNG = 32768
SIZES = {'skin': {(64, 64)}, 'cape': {(64, 32)}}


def validate_png(data, kind):
    if kind not in SIZES or len(data) > MAX_PNG:
        raise ValueError('PNG troppo grande (massimo 32 KB).')
    with Image.open(io.BytesIO(data)) as image:
        if image.format != 'PNG' or image.size not in SIZES[kind]:
            raise ValueError('Skin: 64×64 PNG. Mantello: 64×32 PNG.')
        image.load()
        result = io.BytesIO()
        image.convert('RGBA').save(result, format='PNG')
    return result.getvalue()


def save_texture(instance, kind, data):
    data = validate_png(data, kind)
    target = Path(instance) / 'config' / 'nuovoordine-cosmetics' / (kind + '.png')
    target.parent.mkdir(parents=True, exist_ok=True)
    tmp = target.with_suffix('.tmp')
    tmp.write_bytes(data)
    os.replace(tmp, target)
    return target


def create_cape(base='#151b25', accent='#29b6f6', pattern='stripe'):
    image = Image.new('RGBA', (64, 32), (0, 0, 0, 0))
    draw = ImageDraw.Draw(image)
    # Vanilla cape UV: 10×16 front/back, 1 pixel depth; no stretched image.
    draw.rectangle((0, 0, 21, 16), fill=base)
    for x in (1, 12):
        if pattern == 'cross':
            draw.rectangle((x + 4, 1, x + 5, 16), fill=accent)
            draw.rectangle((x, 7, x + 9, 9), fill=accent)
        elif pattern == 'border':
            draw.rectangle((x, 1, x + 9, 16), outline=accent)
        elif pattern == 'stripe':
            draw.rectangle((x + 4, 1, x + 5, 16), fill=accent)
        else:
            raise ValueError('Motivo del mantello non valido.')
    out = io.BytesIO()
    image.save(out, format='PNG')
    return out.getvalue()
