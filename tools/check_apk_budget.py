"""Fail the build at the user's strict 100 MB app-package budget."""
import argparse
import json
from pathlib import Path
import zipfile

LIMIT=100_000_000  # Decimal MB, deliberately stricter than 100 MiB.


def check(path,limit=LIMIT):
    path=Path(path)
    with zipfile.ZipFile(path) as archive:
        entries=archive.infolist()
        payload=sum(item.file_size for item in entries)
        private=[item.filename for item in entries if any(part in ('private','training','datasets') for part in Path(item.filename).parts)]
        models=[{'name':item.filename,'bytes':item.file_size} for item in entries if item.filename.endswith(('.onnx','.tflite'))]
        if private:
            raise ValueError('Private or training data must not be bundled: '+str(private))
    report={'apk':str(path),'apk_bytes':path.stat().st_size,'uncompressed_payload_bytes':payload,
            'strict_limit_bytes':limit,'models':models,
            'passed':path.stat().st_size<limit and payload<limit,
            'scope':'APK and extracted payload; user photos, runtime caches and Android-generated compilation data are separate.'}
    if not report['passed']:
        raise ValueError('Android size budget exceeded: '+json.dumps(report))
    return report


if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk',type=Path)
    args=parser.parse_args()
    print(json.dumps(check(args.apk),indent=2))
