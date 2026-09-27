"""Build a standalone launcher on the target operating system."""
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]


def main():
    os.chdir(ROOT)
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
    if sys.platform == "darwin":
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
