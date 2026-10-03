import hashlib
import json
from pathlib import Path
import pytest
from tools.install_suite_manifest import update


def setup(tmp_path):
    artifacts=[]
    for name in ['market','cosmetics','townnames','pvp','taczfix']:
        p=tmp_path/f'mods/nuovo-ordine-{name}-1.0.0.jar'
        p.parent.mkdir(exist_ok=True);p.write_bytes(name.encode())
        artifacts.append({'path':p.relative_to(tmp_path).as_posix(),'size':len(name),'sha256':hashlib.sha256(name.encode()).hexdigest()})
    p=tmp_path/'projects/nuovo-ordine-suite/artifacts.json';p.parent.mkdir(parents=True);p.write_text(json.dumps(artifacts))
    original={'path':'mods/unrelated.jar','url':'unchanged','sha256':'unchanged'}
    (tmp_path/'pack.json').write_text(json.dumps({'files':[original],'forge':'47.4.10','archives':['preserve']}))
    (tmp_path/'pack-settings.json').write_text(json.dumps({'external_files':[original],'preserve':['config/custom'],'news':'preserve'}))
    return original


def test_preserves_existing_pack_and_is_idempotent(tmp_path):
    original=setup(tmp_path)
    update(tmp_path,'a'*40);first=(tmp_path/'pack.json').read_bytes();update(tmp_path,'a'*40)
    assert (tmp_path/'pack.json').read_bytes()==first
    pack=json.loads(first);assert pack['files'][0]==original
    assert pack['forge']=='47.4.10' and pack['archives']==['preserve']
    assert len(pack['files'])==6
    assert all('/'+'a'*40+'/' in e['url'] for e in pack['files'][1:])
    settings=json.loads((tmp_path/'pack-settings.json').read_text(encoding='utf-8'))
    assert settings['preserve']==['config/custom'] and settings['news']=='preserve'


def test_bad_artifact_does_not_write_manifest(tmp_path):
    setup(tmp_path);before=(tmp_path/'pack.json').read_bytes()
    (tmp_path/'mods/nuovo-ordine-market-1.0.0.jar').write_bytes(b'bad')
    with pytest.raises(ValueError):update(tmp_path,'a'*40)
    assert (tmp_path/'pack.json').read_bytes()==before
