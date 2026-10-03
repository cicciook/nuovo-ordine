from pathlib import Path

from launcher import selfupdate


def test_windows_update_helper_runs_outside_install_and_uses_in_place_copy(monkeypatch, tmp_path):
    data = tmp_path / "data"
    target = tmp_path / "install" / "NuovoOrdine"
    payload = tmp_path / "stage" / "NuovoOrdine"
    target.mkdir(parents=True)
    payload.mkdir(parents=True)
    (target / "NuovoOrdine.exe").write_bytes(b"old")
    (payload / "NuovoOrdine.exe").write_bytes(b"new")

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

    selfupdate.apply_update({"payload": str(payload), "target": str(target)})

    assert len(calls) == 1
    args, kwargs = calls[0]
    assert args[0] == "powershell.exe"
    assert "-NonInteractive" in args
    assert Path(kwargs["cwd"]) == data
    assert kwargs["stdin"] is selfupdate.subprocess.DEVNULL

    script = (data / "apply-launcher-update.ps1").read_text("utf-8-sig")
    assert "robocopy.exe" in script
    assert "/MIR" in script
    assert "launcher-update-backup" in script
    assert "Move-Item -LiteralPath $dst" not in script
    assert "Start-Process -FilePath $exe -WorkingDirectory $dst -PassThru" in script
    assert "$newProcess.HasExited" in script
    assert "launcher-update.log" in script


def test_windows_release_prefers_installer(monkeypatch):
    monkeypatch.setattr(selfupdate.sys, "platform", "win32")
    monkeypatch.setattr(selfupdate.platform, "machine", lambda: "AMD64")
    release = {
        "assets": [
            {"name": "NuovoOrdine-windows-amd64.zip"},
            {"name": "NuovoOrdine-Setup-windows-amd64.exe"},
        ]
    }
    assert selfupdate._asset_for_release(release)["name"].endswith(".exe")


def test_windows_installer_update_uses_detached_helper(monkeypatch, tmp_path):
    data = tmp_path / "data"
    setup = tmp_path / "NuovoOrdine-Setup-windows-amd64.exe"
    setup.write_bytes(b"MZ")
    calls = []

    class DummyProcess:
        pass

    def fake_popen(args, **kwargs):
        calls.append((args, kwargs))
        return DummyProcess()

    monkeypatch.setattr(selfupdate, "DATA", data)
    monkeypatch.setattr(selfupdate.sys, "platform", "win32")
    monkeypatch.setattr(selfupdate.subprocess, "Popen", fake_popen)
    monkeypatch.setattr(selfupdate.os, "getpid", lambda: 6789)
    monkeypatch.setenv("LOCALAPPDATA", str(tmp_path / "local"))

    selfupdate.apply_update({"installer": str(setup), "target": str(tmp_path)})

    assert len(calls) == 1
    script = (data / "apply-launcher-installer.ps1").read_text("utf-8-sig")
    assert "/VERYSILENT" in script
    assert "NuovoOrdine.exe" in script
    assert "Start-Process -FilePath $setup" in script
