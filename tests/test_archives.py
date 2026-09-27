import hashlib
import zipfile
import shutil
import pytest
from launcher.updater import Updater, digest, validate_manifest
from tools.publish_pack import build_pack
from tools.import_drive import import_zip


def test_archive_downloaded_once_and_extracted_safely(tmp_path):
    bundle=tmp_path/'extras.zip'
    with zipfile.ZipFile(bundle,'w') as z:
        z.writestr('tacz/pack/a.json',b'a')
        z.writestr('config/b.json',b'b')
        z.writestr('../outside',b'unwanted')
    files=[{'path':name,'size':1,'sha256':hashlib.sha256(body).hexdigest(),'archive':'extras'} for name,body in [('tacz/pack/a.json',b'a'),('config/b.json',b'b')]]
    manifest={'schema':1,'version':'1','minecraft':'1.20.1','forge':'47.4.10','files':files,
      'archives':{'extras':{'url':'https://example.org/extras.zip','size':bundle.stat().st_size,'sha256':digest(bundle)}}}
    calls=[]
    def download(entry,target,report):
        calls.append(entry['url'])
        shutil.copyfile(bundle,target)
    root=tmp_path/'game'
    updater=Updater(root,downloader=download)
    assert updater.sync(manifest)==2
    assert len(calls)==1
    assert (root/'tacz/pack/a.json').read_bytes()==b'a'
    assert not (tmp_path/'outside').exists()
    assert updater.sync(manifest)==0
    assert len(calls)==1


def test_publisher_bundles_extra_files(tmp_path):
    source=tmp_path/'source'
    (source/'mods').mkdir(parents=True)
    (source/'tacz').mkdir()
    (source/'mods/a.jar').write_bytes(b'mod')
    (source/'tacz/pack.json').write_bytes(b'{}')
    data=build_pack('owner/repo','1',source,{'minecraft':'1.20.1','forge':'47.4.10'},tmp_path/'out')
    validate_manifest(data)
    assert len(data['archives'])==1
    assert len(list((tmp_path/'out/assets').iterdir()))==2


def test_import_excludes_personal_files_credentials_and_mod_caches(tmp_path):
    archive=tmp_path/'in.zip'
    with zipfile.ZipFile(archive,'w') as z:
        for name in ['mods/a.jar','mods/.connector/cached.jar','logs/latest.log','servers.dat','config/watermedia.toml','config/resourceful-config-web.json','tacz/guns/pack.json','customnpcs/pack.mcmeta']:
            z.writestr('minecraft/'+name,b'content')
    root=tmp_path/'out'
    report=import_zip(archive,root)
    assert report['mods']==1
    assert report['files']==3
    assert not (root/'logs').exists()
    assert not (root/'config/watermedia.toml').exists()
    assert (root/'customnpcs/pack.mcmeta').exists()
