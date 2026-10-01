"""Install the verified suite entries, preserving unrelated pack contents."""
import argparse
import hashlib
import json
import re
from pathlib import Path

SUITE_MODULES = ('market', 'cosmetics', 'gameplay', 'townnames', 'pvp')
PREFIXES = tuple('mods/nuovo-ordine-' + name + '-' for name in SUITE_MODULES)


def update(root, commit):
    root = Path(root)
    if not re.fullmatch(r'[a-f0-9]{40}', commit):
        raise ValueError('Expected immutable source commit')
    artifacts = json.loads((root / 'projects/nuovo-ordine-suite/artifacts.json').read_text(encoding='utf-8'))
    entries = []
    for artifact in artifacts:
        path = artifact['path']
        if not path.startswith(PREFIXES) or not path.endswith('.jar'):
            raise ValueError('Unexpected suite artifact')
        data = (root / path).read_bytes()
        if len(data) != artifact['size'] or hashlib.sha256(data).hexdigest() != artifact['sha256']:
            raise ValueError('Artifact mismatch: ' + path)
        entries.append({**artifact, 'mode': 'replace', 'url': f'https://raw.githubusercontent.com/cicciook/nuovo-ordine/{commit}/{path}'})
    if len(entries) != len(SUITE_MODULES) or len({e['path'] for e in entries}) != len(SUITE_MODULES):
        raise ValueError(f'Expected {len(SUITE_MODULES)} suite artifacts')
    for name, key in [('pack.json', 'files'), ('pack-settings.json', 'external_files')]:
        path = root / name
        doc = json.loads(path.read_text(encoding='utf-8'))
        doc[key] = [e for e in doc.get(key, []) if not e.get('path', '').startswith(PREFIXES)] + entries
        if name == 'pack.json':
            doc['version'] = '2026.10.01-gameplay-1.0.0'
            doc['news'] = 'Nuovo Ordine — Ferite, reputazione, medico, classifiche e convogli player-driven'
        # Keep all existing settings and unrelated entries unchanged.
        path.write_text(json.dumps(doc, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--root', default='.')
    parser.add_argument('--commit', required=True)
    args = parser.parse_args()
    update(args.root, args.commit)
