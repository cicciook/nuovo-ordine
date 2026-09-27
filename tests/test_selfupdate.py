from pathlib import Path

from launcher import selfupdate


def test_windows_update_helper_runs_outside_install(monkeypatch, tmp_path):
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
    assert Path(kwargs["cwd"]) == data
    assert kwargs["stdin"] is selfupdate.subprocess.DEVNULL
    script = (data / "apply-launcher-update.ps1").read_text("utf-8")
    assert "Retry-Action" in script
    assert "launcher-update.log" in script
    assert "-WorkingDirectory" in script
