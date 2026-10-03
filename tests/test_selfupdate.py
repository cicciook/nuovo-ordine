from pathlib import Path

from launcher import selfupdate


def test_prefers_native_windows_installer(monkeypatch):
    monkeypatch.setattr(selfupdate.sys, "platform", "win32")
    monkeypatch.setattr(selfupdate.platform, "machine", lambda: "AMD64")
    release = {"assets": [
        {"name": "NuovoOrdine-windows-amd64.zip"},
        {"name": "NuovoOrdine-Setup-windows-amd64.exe"},
    ]}
    assert selfupdate._asset_for_release(release)["name"].endswith(".exe")


def test_windows_installer_helper_waits_and_relaunches(monkeypatch, tmp_path):
    data = tmp_path / "data"
    installer = tmp_path / "NuovoOrdine-Setup-windows-amd64.exe"
    installer.write_bytes(b"installer")
    local = tmp_path / "local"
    calls = []

    class DummyProcess:
        pass

    def fake_popen(args, **kwargs):
        calls.append((args, kwargs))
        return DummyProcess()

    monkeypatch.setattr(selfupdate, "DATA", data)
    monkeypatch.setattr(selfupdate.sys, "platform", "win32")
    monkeypatch.setattr(selfupdate.subprocess, "Popen", fake_popen)
    monkeypatch.setattr(selfupdate.os, "getpid", lambda: 12345)
    monkeypatch.setenv("LOCALAPPDATA", str(local))

    selfupdate.apply_update({"installer": str(installer), "version": "1.4.0"})

    assert len(calls) == 1
    args, kwargs = calls[0]
    assert args[0] == "powershell.exe"
    assert "-NonInteractive" in args
    assert Path(kwargs["cwd"]) == data
    script = (data / "apply-launcher-installer.ps1").read_text("utf-8-sig")
    assert "/VERYSILENT" in script
    assert "/CLOSEAPPLICATIONS" in script
    assert "Programs" in script and "NuovoOrdine.exe" in script
    assert "robocopy.exe" not in script
