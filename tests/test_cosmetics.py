import io
import pytest
from PIL import Image
from launcher.cosmetics import create_cape, save_texture, validate_png
from launcher.community import normalize_content, format_changelog


def png(size=(64, 64)):
    out=io.BytesIO();Image.new('RGBA',size,'red').save(out,format='PNG');return out.getvalue()


@pytest.mark.parametrize('pattern',['stripe','cross','border'])
def test_cape_roundtrip(pattern,tmp_path):
    data=create_cape(pattern=pattern)
    assert Image.open(io.BytesIO(data)).size==(64,32)
    path=save_texture(tmp_path,'cape',data)
    assert path.read_bytes()==validate_png(data,'cape')


@pytest.mark.parametrize('size',[(32,32),(128,128),(64,32),(1,1)])
def test_reject_skin_dimensions(size):
    with pytest.raises(ValueError):validate_png(png(size),'skin')


def test_skin_roundtrip(tmp_path):
    path=save_texture(tmp_path,'skin',png())
    assert path.is_file()
    assert not path.with_suffix('.tmp').exists()


def test_reject_invalid_and_oversized():
    for data in [b'not a PNG',b'x'*32769]:
        with pytest.raises((ValueError,OSError)):validate_png(data,'skin')


def test_failed_import_preserves_existing(tmp_path):
    path=save_texture(tmp_path,'skin',png());before=path.read_bytes()
    with pytest.raises(ValueError):save_texture(tmp_path,'skin',png((2,2)))
    assert path.read_bytes()==before


def test_latest_changelog_numeric():
    entries=[{'version':'1.9.0','title':'Old','body':'OLD'},{'version':'1.10.0','title':'New','body':'NEW'},{'version':'1.8.0','title':'Ancient','body':'ANCIENT'}]
    assert normalize_content({'changelog':entries})['changelog']==[entries[1]]
    text=format_changelog(entries)
    assert 'NEW' in text and 'OLD' not in text and 'ANCIENT' not in text


def test_empty_changelog_fallback():
    assert len(normalize_content({})['changelog'])==1
