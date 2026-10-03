import hashlib
import json
import os
from pathlib import Path
import pytest
from launcher.updater import Updater, safe_path, validate_manifest
from launcher.config import atomic_json


def item(name="mods/test.jar",body=b"mod-v1",mode="replace"):
    return {"path":name,"url":"https://example.org/mod.jar","sha256":hashlib.sha256(body).hexdigest(),"size":len(body),"mode":mode}


def pack(*files):
    return {"schema":1,"minecraft":"1.20.1","forge":"47.4.0","version":"1.0","files":list(files)}


def downloader(body):
    return lambda entry,target,report: target.write_bytes(body)


def test_installs_and_does_not_redownload_unchanged_files(tmp_path):
    u=Updater(tmp_path,downloader=downloader(b"mod-v1"))
    assert u.sync(pack(item())) == 1
    u.downloader=lambda *args: pytest.fail("Unexpected download")
    assert u.sync(pack(item())) == 0
    assert (tmp_path/"mods/test.jar").read_bytes()==b"mod-v1"


def test_corrupt_download_keeps_old_pack_intact(tmp_path):
    u=Updater(tmp_path,downloader=downloader(b"mod-v1"))
    u.sync(pack(item()))
    old=u.state.read_bytes()
    u.downloader=downloader(b"corrupt")
    with pytest.raises(ValueError):
        u.sync(pack(item(body=b"mod-v2")))
    assert (tmp_path/"mods/test.jar").read_bytes()==b"mod-v1"
    assert u.state.read_bytes()==old
    assert not u.tx.exists()


def test_removes_only_previously_managed_files(tmp_path):
    u=Updater(tmp_path,downloader=downloader(b"mod-v1"))
    u.sync(pack(item()))
    (tmp_path/"mods/personal.jar").write_bytes(b"personal")
    (tmp_path/"saves").mkdir()
    (tmp_path/"saves/world.dat").write_bytes(b"world")
    u.sync(pack(item("mods/new.jar")))
    assert not (tmp_path/"mods/test.jar").exists()
    assert (tmp_path/"mods/new.jar").exists()
    assert (tmp_path/"mods/personal.jar").read_bytes()==b"personal"
    assert (tmp_path/"saves/world.dat").read_bytes()==b"world"


def test_preserves_player_config(tmp_path):
    (tmp_path/"config").mkdir()
    (tmp_path/"config/prefs.json").write_bytes(b"my settings")
    u=Updater(tmp_path,downloader=lambda *a:pytest.fail("Should preserve config"))
    u.sync(pack(item("config/prefs.json",mode="preserve")))
    u.sync(pack())
    assert (tmp_path/"config/prefs.json").read_bytes()==b"my settings"


@pytest.mark.parametrize("name",["../secret","mods/../../secret","/mods/file.jar","mods\\file.jar",
    "mods//file.jar","mods/./file.jar","mods/C:evil.jar","saves/world.dat","mods/CON.jar","mods/foo. /file.jar"])
def test_rejects_unsafe_paths(tmp_path,name):
    with pytest.raises(ValueError):safe_path(tmp_path,name)


def test_rejects_symlink(tmp_path):
    outside=tmp_path/"outside"
    outside.mkdir()
    root=tmp_path/"game"
    root.mkdir()
    try:(root/"mods").symlink_to(outside,target_is_directory=True)
    except OSError:pytest.skip("Symlink permission unavailable")
    with pytest.raises(ValueError):safe_path(root,"mods/escape.jar")


def test_duplicate_case_paths_rejected():
    with pytest.raises(ValueError):validate_manifest(pack(item("mods/a.jar"),item("mods/A.jar")))


def test_file_directory_collision_rejected():
    with pytest.raises(ValueError):validate_manifest(pack(item("config/a"),item("config/a/b")))


def test_rollback_on_apply_failure(tmp_path,monkeypatch):
    u=Updater(tmp_path,downloader=downloader(b"mod-v1"))
    u.sync(pack(item(),item("mods/second.jar")))
    old=u.state.read_bytes()
    u.downloader=downloader(b"mod-v2")
    original=os.replace
    def fail_second(src,dst):
        if Path(src).parent.name=="stage" and Path(src).name=="1":
            raise OSError("simulated locked file")
        return original(src,dst)
    monkeypatch.setattr(os,"replace",fail_second)
    with pytest.raises(OSError):u.sync(pack(item(body=b"mod-v2"),item("mods/second.jar",body=b"mod-v2")))
    assert (tmp_path/"mods/test.jar").read_bytes()==b"mod-v1"
    assert (tmp_path/"mods/second.jar").read_bytes()==b"mod-v1"
    assert u.state.read_bytes()==old


def test_recovers_transaction_left_by_crash(tmp_path):
    u=Updater(tmp_path)
    (tmp_path/"mods").mkdir()
    (tmp_path/"mods/test.jar").write_bytes(b"partially updated")
    (u.tx/"backup").mkdir(parents=True)
    (u.tx/"backup/0").write_bytes(b"old content")
    atomic_json(u.tx/"journal.json",{"old_state":None,"operations":[{"path":"mods/test.jar","existed":True,"index":0}]})
    u.recover()
    assert (tmp_path/"mods/test.jar").read_bytes()==b"old content"
    assert not u.tx.exists()


def test_all_downloads_finish_before_mutation(tmp_path):
    u=Updater(tmp_path,downloader=downloader(b"mod-v1"))
    u.sync(pack(item()))
    count=0
    def fetch(entry,target,report):
        nonlocal count
        count+=1
        assert (tmp_path/"mods/test.jar").read_bytes()==b"mod-v1"
        if count==2:raise OSError("Network interruption")
        target.write_bytes(b"mod-v2")
    u.downloader=fetch
    with pytest.raises(OSError):u.sync(pack(item(body=b"mod-v2"),item("mods/second.jar",body=b"mod-v2")))
    assert (tmp_path/"mods/test.jar").read_bytes()==b"mod-v1"
    assert not (tmp_path/"mods/second.jar").exists()


def test_removes_untracked_older_managed_mod_versions(tmp_path):
    current = item("mods/nuovo-ordine-quests-1.2.5.jar")
    (tmp_path / "mods").mkdir()
    (tmp_path / "mods/nuovo-ordine-quests-1.2.2.jar").write_bytes(b"old quest")
    (tmp_path / "mods/personal.jar").write_bytes(b"personal")
    u = Updater(tmp_path, downloader=downloader(b"mod-v1"))
    u.sync(pack(current))
    assert not (tmp_path / "mods/nuovo-ordine-quests-1.2.2.jar").exists()
    assert (tmp_path / "mods/nuovo-ordine-quests-1.2.5.jar").read_bytes() == b"mod-v1"
    assert (tmp_path / "mods/personal.jar").read_bytes() == b"personal"


def test_removes_untracked_newer_duplicate_of_managed_family(tmp_path):
    current = item("mods/nuovo-ordine-core-0.2.2.jar")
    (tmp_path / "mods").mkdir()
    (tmp_path / "mods/nuovo-ordine-core-0.2.5.jar").write_bytes(b"manual duplicate")
    u = Updater(tmp_path, downloader=downloader(b"mod-v1"))
    u.sync(pack(current))
    assert not (tmp_path / "mods/nuovo-ordine-core-0.2.5.jar").exists()
    assert (tmp_path / "mods/nuovo-ordine-core-0.2.2.jar").read_bytes() == b"mod-v1"
