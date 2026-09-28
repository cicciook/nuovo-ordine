"""Build a standalone launcher on the target operating system."""
import base64
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]


def prepare_logo():
    assets = ROOT / "launcher" / "assets"
    chunks = [assets / f"logo.b64.{i:02d}" for i in range(1, 7)]
    if not all(path.exists() for path in chunks):
        raise RuntimeError("Dati del logo Nuovo Ordine mancanti.")
    encoded = "".join(path.read_text("ascii").strip() for path in chunks)
    raw = base64.b64decode(encoded, validate=True)
    if len(raw) < 10_000 or not raw.startswith(b"\xff\xd8") or not raw.endswith(b"\xff\xd9"):
        raise RuntimeError("Logo Nuovo Ordine non valido.")
    logo = assets / "nuovo-ordine-logo.jpg"
    logo.write_bytes(raw)
    return logo


def prepare_windows_icon(logo_path):
    """Create a multi-resolution .ico from the official launcher artwork."""
    assets = ROOT / "launcher" / "assets"
    icon_path = assets / "nuovo-ordine.ico"
    with Image.open(logo_path) as source:
        source = source.convert("RGBA")
        source.thumbnail((470, 470), Image.Resampling.LANCZOS)
        canvas = Image.new("RGBA", (512, 512), (7, 17, 31, 255))
        x = (canvas.width - source.width) // 2
        y = (canvas.height - source.height) // 2
        canvas.paste(source, (x, y), source)
        canvas.save(
            icon_path,
            format="ICO",
            sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)],
        )
    return icon_path


def main():
    os.chdir(ROOT)
    logo_path = prepare_logo()
    args = [
        sys.executable,
        "-m",
        "PyInstaller",
        "--noconfirm",
        "--clean",
        "--windowed",
        "--onedir",
        "--name",
        "NuovoOrdine",
        "--add-data",
        "launcher-config.json" + os.pathsep + ".",
        "--add-data",
        str(ROOT / "launcher" / "assets") + os.pathsep + "launcher/assets",
        "--collect-all",
        "minecraft_launcher_lib",
        "--collect-all",
        "keyring",
        "--copy-metadata",
        "requests",
        "--copy-metadata",
        "platformdirs",
    ]
    if sys.platform == "win32":
        icon_path = prepare_windows_icon(logo_path)
        args += ["--icon", str(icon_path)]
    elif sys.platform == "darwin":
        args += ["--osx-bundle-identifier", "it.nuovoordine.launcher"]
    subprocess.run(args + ["main.py"], check=True)

    system = {"win32": "windows", "darwin": "macos"}.get(sys.platform, "linux")
    name = "NuovoOrdine-" + system + "-" + platform.machine().lower()

    if sys.platform == "darwin":
        subprocess.run(
            [
                "ditto",
                "-c",
                "-k",
                "--sequesterRsrc",
                "--keepParent",
                "dist/NuovoOrdine.app",
                "dist/" + name + ".zip",
            ],
            check=True,
        )
    else:
        fmt = "zip" if sys.platform == "win32" else "gztar"
        shutil.make_archive(
            str(ROOT / "dist" / name),
            fmt,
            ROOT / "dist",
            "NuovoOrdine",
        )
    print("Build pronta in dist/")


if __name__ == "__main__":
    main()
