"""Build standalone launcher bundles plus native installers on the target OS."""
import base64
import os
import platform
import shutil
import subprocess
import sys
from pathlib import Path

from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT))
from launcher import VERSION  # noqa: E402

APP_ID = "{C7A8D7D6-86D6-4DE5-90DD-81B1D0978E2E}"


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
    assets = ROOT / "launcher" / "assets"
    icon_path = assets / "nuovo-ordine.ico"
    with Image.open(logo_path) as source:
        source = source.convert("RGBA")
        source.thumbnail((470, 470), Image.Resampling.LANCZOS)
        canvas = Image.new("RGBA", (512, 512), (7, 17, 31, 255))
        canvas.paste(source, ((canvas.width - source.width) // 2, (canvas.height - source.height) // 2), source)
        canvas.save(icon_path, format="ICO", sizes=[(16, 16), (24, 24), (32, 32), (48, 48), (64, 64), (128, 128), (256, 256)])
    return icon_path


def machine_name():
    return platform.machine().lower()


def deb_arch():
    machine = machine_name()
    return {"x86_64": "amd64", "amd64": "amd64", "aarch64": "arm64", "arm64": "arm64"}.get(machine, machine)


def build_windows_installer(icon_path):
    compiler = shutil.which("ISCC.exe") or shutil.which("iscc")
    if not compiler:
        candidate = Path(os.environ.get("ProgramFiles(x86)", r"C:\Program Files (x86)")) / "Inno Setup 6" / "ISCC.exe"
        if candidate.exists():
            compiler = str(candidate)
    if not compiler:
        raise RuntimeError("Inno Setup 6 non trovato.")

    build_dir = ROOT / "build" / "installer"
    build_dir.mkdir(parents=True, exist_ok=True)
    dist = (ROOT / "dist").resolve()
    source = (dist / "NuovoOrdine").resolve()
    out_name = f"NuovoOrdine-Setup-windows-{machine_name()}"
    script = build_dir / "NuovoOrdine.iss"
    script.write_text(f"""[Setup]
AppId={APP_ID}
AppName=Nuovo Ordine
AppVersion={VERSION}
AppPublisher=Nuovo Ordine
DefaultDirName={{localappdata}}\\Programs\\NuovoOrdine
DefaultGroupName=Nuovo Ordine
PrivilegesRequired=lowest
ArchitecturesAllowed=x64compatible
ArchitecturesInstallIn64BitMode=x64compatible
OutputDir={dist}
OutputBaseFilename={out_name}
SetupIconFile={icon_path.resolve()}
Compression=lzma2
SolidCompression=yes
WizardStyle=modern
CloseApplications=yes
RestartApplications=no
UninstallDisplayIcon={{app}}\\NuovoOrdine.exe

[Files]
Source: "{source}\\*"; DestDir: "{{app}}"; Flags: ignoreversion recursesubdirs createallsubdirs

[Icons]
Name: "{{autoprograms}}\\Nuovo Ordine"; Filename: "{{app}}\\NuovoOrdine.exe"; WorkingDir: "{{app}}"
Name: "{{autodesktop}}\\Nuovo Ordine"; Filename: "{{app}}\\NuovoOrdine.exe"; WorkingDir: "{{app}}"; Tasks: desktopicon

[Tasks]
Name: "desktopicon"; Description: "Crea collegamento sul desktop"; GroupDescription: "Collegamenti:"; Flags: unchecked

[Run]
Filename: "{{app}}\\NuovoOrdine.exe"; Description: "Avvia Nuovo Ordine"; WorkingDir: "{{app}}"; Flags: nowait postinstall skipifsilent
""", encoding="utf-8")
    subprocess.run([compiler, str(script)], check=True)


def build_macos_installer():
    output = ROOT / "dist" / f"NuovoOrdine-Setup-macos-{machine_name()}.pkg"
    subprocess.run([
        "pkgbuild", "--component", str(ROOT / "dist" / "NuovoOrdine.app"),
        "--install-location", "/Applications", "--identifier", "it.nuovoordine.launcher",
        "--version", VERSION, str(output),
    ], check=True)


def build_linux_installer(logo_path):
    package_root = ROOT / "build" / "installer" / "deb-root"
    shutil.rmtree(package_root, ignore_errors=True)
    (package_root / "DEBIAN").mkdir(parents=True)
    app_dir = package_root / "opt" / "nuovo-ordine"
    shutil.copytree(ROOT / "dist" / "NuovoOrdine", app_dir)
    bin_dir = package_root / "usr" / "bin"
    bin_dir.mkdir(parents=True)
    launcher = bin_dir / "nuovo-ordine"
    launcher.write_text("#!/bin/sh\nexec /opt/nuovo-ordine/NuovoOrdine \"$@\"\n", encoding="utf-8")
    launcher.chmod(0o755)

    pixmaps = package_root / "usr" / "share" / "pixmaps"
    pixmaps.mkdir(parents=True)
    shutil.copy2(logo_path, pixmaps / "nuovo-ordine.jpg")
    apps = package_root / "usr" / "share" / "applications"
    apps.mkdir(parents=True)
    (apps / "nuovo-ordine.desktop").write_text(
        "[Desktop Entry]\nType=Application\nName=Nuovo Ordine\nExec=nuovo-ordine\n"
        "Icon=/usr/share/pixmaps/nuovo-ordine.jpg\nCategories=Game;\nTerminal=false\n",
        encoding="utf-8",
    )
    (package_root / "DEBIAN" / "control").write_text(
        f"Package: nuovo-ordine-launcher\nVersion: {VERSION}\nSection: games\nPriority: optional\n"
        f"Architecture: {deb_arch()}\nMaintainer: Nuovo Ordine\nDescription: Launcher ufficiale Nuovo Ordine\n",
        encoding="utf-8",
    )
    output = ROOT / "dist" / f"NuovoOrdine-Setup-linux-{deb_arch()}.deb"
    subprocess.run(["dpkg-deb", "--build", "--root-owner-group", str(package_root), str(output)], check=True)


def main():
    os.chdir(ROOT)
    logo_path = prepare_logo()
    args = [
        sys.executable, "-m", "PyInstaller", "--noconfirm", "--clean", "--windowed", "--onedir",
        "--name", "NuovoOrdine", "--add-data", "launcher-config.json" + os.pathsep + ".",
        "--add-data", str(ROOT / "launcher" / "assets") + os.pathsep + "launcher/assets",
        "--collect-all", "minecraft_launcher_lib", "--collect-all", "keyring",
        "--copy-metadata", "requests", "--copy-metadata", "platformdirs",
    ]
    icon_path = None
    if sys.platform == "win32":
        icon_path = prepare_windows_icon(logo_path)
        args += ["--icon", str(icon_path)]
    elif sys.platform == "darwin":
        args += ["--osx-bundle-identifier", "it.nuovoordine.launcher"]
    subprocess.run(args + ["main.py"], check=True)

    system = {"win32": "windows", "darwin": "macos"}.get(sys.platform, "linux")
    archive_name = "NuovoOrdine-" + system + "-" + machine_name()
    if sys.platform == "darwin":
        subprocess.run(["ditto", "-c", "-k", "--sequesterRsrc", "--keepParent",
                        "dist/NuovoOrdine.app", "dist/" + archive_name + ".zip"], check=True)
        build_macos_installer()
    else:
        shutil.make_archive(str(ROOT / "dist" / archive_name), "zip" if sys.platform == "win32" else "gztar",
                            ROOT / "dist", "NuovoOrdine")
        if sys.platform == "win32":
            build_windows_installer(icon_path)
        else:
            build_linux_installer(logo_path)
    print("Build e installer pronti in dist/")


if __name__ == "__main__":
    main()
