from pathlib import Path

from launcher import updater_v2


def test_windows_updater_uses_in_place_mirror_and_external_backup(monkeypatch, tmp_path):
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

    monkeypatch.setattr(updater_v2, "DATA", data)
    monkeypatch.setattr(updater_v2.sys, "platform", "win32")
    monkeypatch.setattr(updater_v2.subprocess, "Popen", fake_popen)
    monkeypatch.setattr(updater_v2.os, "getpid", lambda: 12345)

    updater_v2.apply_update({"payload": str(payload), "target": str(target)})

    assert len(calls) == 1
    args, kwargs = calls[0]
    assert args[0] == "powershell.exe"
    assert Path(kwargs["cwd"]) == data

    script = (data / "apply-launcher-update.ps1").read_text("utf-8")
    assert "robocopy.exe" in script
    assert "/MIR" in script
    assert str(data / "launcher-backup") in script
    assert "$process.HasExited" in script
    assert "Ripristino automaticamente la versione precedente" in script
    assert "Move-Item -LiteralPath $dst" not in script
