import io

import pytest
from PIL import Image

from launcher.cosmetics import (
    MAX_CAPE_FRAMES, cape_frame_count, create_cape, prepare_cape_import,
    save_texture, validate_png,
)


def png(size=(64, 64), color="red"):
    out = io.BytesIO()
    Image.new("RGBA", size, color).save(out, format="PNG")
    return out.getvalue()


def animated_gif(frame_count=3, duration=80):
    frames = [Image.new("RGBA", (64, 32), (i * 30, 20, 200, 255)) for i in range(frame_count)]
    out = io.BytesIO()
    frames[0].save(out, format="GIF", save_all=True, append_images=frames[1:], duration=duration, loop=0)
    return out.getvalue()


@pytest.mark.parametrize("pattern", ["stripe", "cross", "border"])
def test_cape_roundtrip(pattern, tmp_path):
    data = create_cape(pattern=pattern)
    assert Image.open(io.BytesIO(data)).size == (64, 32)
    path = save_texture(tmp_path, "cape", data)
    assert path.read_bytes() == validate_png(data, "cape")
    assert not (tmp_path / "config/nuovoordine-cosmetics/cape.frame_ms").exists()


@pytest.mark.parametrize("size", [(32, 32), (128, 128), (64, 32), (1, 1)])
def test_reject_skin_dimensions(size):
    with pytest.raises(ValueError):
        validate_png(png(size), "skin")


def test_skin_roundtrip(tmp_path):
    path = save_texture(tmp_path, "skin", png())
    assert path.is_file()
    assert not path.with_suffix(".tmp").exists()


def test_reject_invalid_and_oversized_skin():
    for data in [b"not a PNG", b"x" * 32769]:
        with pytest.raises((ValueError, OSError)):
            validate_png(data, "skin")


def test_animated_gif_is_converted_to_vertical_cape(tmp_path):
    data, frame_ms, frames = prepare_cape_import(animated_gif())
    assert frames == 3
    assert frame_ms == 80
    assert cape_frame_count(data) == 3
    assert Image.open(io.BytesIO(data)).size == (64, 96)
    save_texture(tmp_path, "cape", data, frame_ms)
    assert (tmp_path / "config/nuovoordine-cosmetics/cape.frame_ms").read_text("ascii") == "80"


def test_vertical_sprite_sheet_is_accepted():
    sheet = png((64, 32 * 4))
    data, frame_ms, frames = prepare_cape_import(sheet)
    assert frames == 4 and frame_ms == 100
    assert cape_frame_count(data) == 4


def test_reject_too_many_cape_frames():
    with pytest.raises(ValueError):
        prepare_cape_import(png((64, 32 * (MAX_CAPE_FRAMES + 1))))


def test_failed_import_preserves_existing(tmp_path):
    path = save_texture(tmp_path, "skin", png())
    before = path.read_bytes()
    with pytest.raises(ValueError):
        save_texture(tmp_path, "skin", png((2, 2)))
    assert path.read_bytes() == before
