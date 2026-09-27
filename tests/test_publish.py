import hashlib
import pytest
from tools.publish_pack import build_pack


def test_publisher_and_client_manifest_agree(tmp_path):
    source=tmp_path/"source"
    (source/"mods").mkdir(parents=True)
    (source/"mods/car.jar").write_bytes(b"vehicle")
    cfg={"minecraft":"1.20.1","forge":"47.4.0","server":"play.example.org"}
    output=tmp_path/"output"
    data=build_pack("owner/nuovo-ordine","1.2.3",source,cfg,output)
    entry=data["files"][0]
    sha=hashlib.sha256(b"vehicle").hexdigest()
    assert entry["sha256"]==sha
    assert entry["url"]==f"https://github.com/owner/nuovo-ordine/releases/download/pack-1.2.3/{sha}.bin"
    assert (output/"assets"/(sha+".bin")).read_bytes()==b"vehicle"


def test_empty_pack_cannot_be_accidentally_published(tmp_path):
    with pytest.raises(ValueError,match="vuoto"):
        build_pack("owner/repo","1.0",tmp_path,{"minecraft":"1.20.1","forge":"47.4.0"},tmp_path/"out")
