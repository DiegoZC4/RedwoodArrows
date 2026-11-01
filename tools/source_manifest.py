"""Refresh the public source inventory from Git's explicit, staged allowlist.

No workspace traversal: ignored photos, credentials and dependency/build caches
cannot enter the inventory. Run after staging intended source files.
"""
import hashlib
import json
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def main():
    paths = subprocess.check_output(['git', 'ls-files', '-z'], cwd=ROOT).decode().split('\0')
    entries = []
    for name in sorted(filter(None, paths)):
        if name == 'SOURCE_MANIFEST.json':
            continue
        relative = Path(name)
        if any(part in {'private', 'signing', 'deps', 'build', '.git', '__pycache__'} for part in relative.parts):
            raise ValueError('Private/generated path in source inventory')
        if relative.suffix.lower() in {'.jpg', '.jpeg', '.png', '.p12', '.jks', '.keystore', '.apk', '.db', '.sqlite'}:
            raise ValueError('Non-source artifact in inventory')
        file = ROOT / relative
        if file.is_symlink() or not file.is_file():
            raise ValueError('Expected a regular source file')
        entries.append({'path': name, 'bytes': file.stat().st_size, 'sha256': hashlib.sha256(file.read_bytes()).hexdigest()})
    (ROOT / 'SOURCE_MANIFEST.json').write_text(json.dumps({'files': entries, 'personal_photos': 0,
        'secrets': 'not included; signing/auth created separately', 'publisher_bitmaps': 0}, indent=2)+'\n')
    print(json.dumps({'source_files': len(entries), 'bytes': sum(entry['bytes'] for entry in entries)}))


if __name__ == '__main__':
    main()
