"""Fetch only locked runtime dependencies; verify hashes before extracting.

Unlike the original bootstrap, this never downloads or modifies project source.
It works on Linux/macOS with standard Python and the checked-in lockfile.
"""
import hashlib
import sys
import json
from pathlib import Path
import tempfile
import urllib.request
import zipfile

ROOT = Path(__file__).resolve().parents[1]


def main():
    records = json.loads((ROOT / 'DEPENDENCIES.lock.json').read_text())
    if '--tests' in sys.argv:
        records.append(dict(path='deps/test/json-20240303.jar',url='https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar',bytes=78332,sha256='3cf6cd6892e32e2b4c1c39e0f52f5248a2f5b37646fdfbb79a66b46b618414ed'))
    for record in records:
        if not record['path'].startswith('deps/'):
            continue
        target = ROOT / record['path']
        target.parent.mkdir(parents=True, exist_ok=True)
        if not target.exists():
            with tempfile.TemporaryDirectory(dir=target.parent, prefix='runtime-download-') as directory:
                partial = Path(directory) / 'download'
                with urllib.request.urlopen(record['url'], timeout=45) as response, partial.open('wb') as out:
                    size = 0
                    while chunk := response.read(1024*1024):
                        size += len(chunk)
                        if size > record['bytes']:
                            raise ValueError('Dependency exceeds locked size')
                        out.write(chunk)
                if hashlib.sha256(partial.read_bytes()).hexdigest() != record['sha256']:
                    raise ValueError('Dependency checksum mismatch')
                partial.replace(target)
        if target.stat().st_size != record['bytes'] or hashlib.sha256(target.read_bytes()).hexdigest() != record['sha256']:
            raise ValueError('Dependency does not match lock: '+target.name)
    for name in ['arcore', 'opencv']:
        with zipfile.ZipFile(ROOT / f'deps/{name}.aar') as archive:
            (ROOT / f'deps/{name}.jar').write_bytes(archive.read('classes.jar'))
            for member in archive.namelist():
                if member.startswith('jni/arm64-v8a/') and member.endswith('.so'):
                    target = ROOT / 'deps/lib/arm64-v8a' / Path(member).name
                    target.parent.mkdir(parents=True, exist_ok=True)
                    target.write_bytes(archive.read(member))
    print('Runtime libraries match locked SHA-256 checksums.')


if __name__ == '__main__':
    main()
