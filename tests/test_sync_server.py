"""Real HTTP security/transfer tests against a disposable private SQLite library.

The process, temporary credentials, photos and database are cleaned in finally.
Only loopback is allowed. No personal data or production service is touched.
"""
import base64
import hashlib
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import tempfile
import time
import urllib.error
import urllib.request

ROOT = Path(__file__).resolve().parents[1]


class Server:
    def __enter__(self):
        self.temp = tempfile.TemporaryDirectory(prefix='redwood-sync-test-')
        self.directory = Path(self.temp.name)
        self.private = self.directory / 'private'
        self.private.mkdir()
        shutil.copyfile(ROOT / 'server/app.php', self.private / 'app.php')
        self.invites = json.loads(subprocess.check_output(['php', str(ROOT / 'server/cli.php'), 'init', str(self.private)]))
        with socket.socket() as s:
            s.bind(('127.0.0.1', 0))
            self.port = s.getsockname()[1]
        env = dict(os.environ, REDWOOD_PRIVATE_DIR=str(self.private), REDWOOD_TEST_HTTP='1')
        self.log = open(self.directory / 'http.log', 'wb')
        self.proc = subprocess.Popen(['php', '-S', f'127.0.0.1:{self.port}', '-t', str(ROOT / 'server/public')], env=env, stdout=self.log, stderr=self.log)
        self.url = f'http://127.0.0.1:{self.port}/api.php'
        for _ in range(40):
            try:
                if self.call('health')[0] == 200:
                    return self
            except OSError:
                time.sleep(.05)
        self.__exit__(None, None, None)
        raise RuntimeError('Test server did not start')

    def call(self, action, body=None, token='', method=None, raw=None):
        headers = {'Accept': 'application/json'}
        data = None
        if body is not None:
            data = json.dumps(body).encode()
            headers['Content-Type'] = 'application/json'
        if raw is not None:
            data = raw
            headers['Content-Type'] = 'image/jpeg'
        if token:
            headers['Authorization'] = 'Bearer ' + token
        request = urllib.request.Request(self.url + '?action=' + action, data=data, headers=headers, method=method)
        try:
            response = urllib.request.urlopen(request, timeout=15)
        except urllib.error.HTTPError as error:
            response = error
        with response:
            payload = response.read()
            if response.headers.get('Content-Type', '').startswith('application/json'):
                payload = json.loads(payload)
            return response.status, payload

    def __exit__(self, *args):
        if hasattr(self, 'proc'):
            self.proc.terminate()
            try:
                self.proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self.proc.kill()
                self.proc.wait(timeout=5)
        if hasattr(self, 'log'):
            self.log.close()
        self.temp.cleanup()


def run():
    checks = 0

    def check(condition, message):
        nonlocal checks
        assert condition, message
        checks += 1

    with Server() as server:
        check(server.call('health')[1]['private'], 'Private health response')
        for action in ['changes', 'group&id=example', 'photo&hash=' + 'a'*64, 'me', 'release', 'apk']:
            check(server.call(action)[0] == 401, 'No anonymous data: ' + action)
        for action in ['save', 'missing', 'invite', 'logout']:
            check(server.call(action, {})[0] == 401, 'No anonymous mutation: ' + action)
        check(server.call('signup', {'username': 'outsider', 'password': 'a long password', 'invite': 'a'*48})[0] == 403, 'Invite required')
        check(server.call('signup', {'username': 'alex', 'password': 'tiny', 'invite': server.invites['diego_invite']})[0] == 400, 'Strong password minimum')
        users = []
        for name, key in [('alex', 'diego_invite'), ('sam', 'iris_invite')]:
            status, result = server.call('signup', {'username': name, 'password': 'test-only long passphrase', 'invite': server.invites[key]})
            check(status == 200 and len(result['token']) == 64, 'Independent account')
            users.append(result)
        owner, member = [u['token'] for u in users]
        check(users[0]['library'] == users[1]['library'], 'Both share one private library')
        check(server.call('apk', token=member)[0] == 503, 'Missing release fails closed')
        release = server.private / 'release'
        release.mkdir()
        fake_apk = b'PK\x03\x04 bounded test fixture, not an Android application'
        release_metadata = {'version': 'test-only', 'package': 'org.redwoodarrows', 'bytes': len(fake_apk), 'sha256': hashlib.sha256(fake_apk).hexdigest(), 'certificate_sha256': 'a'*64}
        (release / 'RedwoodArrows.apk').write_bytes(fake_apk)
        (release / 'manifest.json').write_text(json.dumps(release_metadata))
        check(server.call('release', token=member)[1]['sha256'] == release_metadata['sha256'], 'Member can verify release metadata')
        check(server.call('apk', token=member)[1] == fake_apk, 'Authenticated APK bytes preserved')
        check(server.call('apk', {}, token=owner)[0] == 405, 'Download is read-only')
        (release / 'RedwoodArrows.apk').write_bytes(b'corrupted release')
        check(server.call('apk', token=member)[0] == 503, 'Corrupt APK is never served')
        (release / 'RedwoodArrows.apk').write_bytes(fake_apk)
        check(server.call('signup', {'username': 'third', 'password': 'test-only long passphrase', 'invite': server.invites['iris_invite']})[0] == 403, 'One-use invite')
        check(server.call('invite', {}, member)[0] == 403, 'Member cannot invite')
        check(len(server.call('invite', {}, owner)[1]['invite']) == 48, 'Owner can invite')
        check(server.call('login', {'username': 'sam', 'password': 'wrong'})[0] == 401, 'Bad password refused')
        check(server.call('login', {'username': 'nobody', 'password': 'wrong'})[0] == 401, 'Unknown user refused')
        check(server.call('group&id=../library.sqlite', token=owner)[0] == 400, 'Traversal refused')
        # Tiny generated JPEG, not a source or user image.
        from PIL import Image
        import io
        image = io.BytesIO()
        Image.new('RGB', (16, 16), 'white').save(image, 'JPEG')
        photo = image.getvalue()
        h = hashlib.sha256(photo).hexdigest()
        check(server.call('missing', {'hashes': [h]}, owner)[1]['missing'] == [h], 'Missing-photo handshake')
        check(server.call('photo&hash='+'a'*64, token=owner, method='PUT', raw=photo)[0] == 400, 'Checksum rejects corruption')
        bad = b'not a jpeg'
        check(server.call('photo&hash='+hashlib.sha256(bad).hexdigest(), token=owner, method='PUT', raw=bad)[0] == 400, 'Nonimage rejected')
        check(server.call('photo&hash='+h, token=owner, method='PUT', raw=photo)[0] == 200, 'Upload photo')
        check(server.call('photo&hash='+h, token=owner, method='PUT', raw=photo)[0] == 200, 'Idempotent photo retry')
        check(server.call('missing', {'hashes': [h]}, member)[1]['missing'] == [], 'Other member sees uploaded content')
        check(server.call('photo&hash='+h, token=member)[1] == photo, 'Private photo round trip')
        group = {'schema': 4, 'id': 'fixture', 'name': 'Invented end', 'target_preset': {'id': 'fixture'}, 'views': [{'file': 'fixture.jpg'}]}
        photos = [{'file': 'fixture.jpg', 'sha256': h, 'bytes': len(photo)}]

        def wire(group, revision, photos=photos):
            body = json.dumps(group, separators=(',', ':'), sort_keys=True)
            fingerprint = hashlib.sha256((body+'\n'+'\n'.join(f"{p['file']}:{p['sha256']}:{p['bytes']}" for p in photos)).encode()).hexdigest()
            return dict(id=group['id'], body=body, photos=photos, fingerprint=fingerprint, base=revision)

        first = wire(group, 0)
        status, saved = server.call('save', first, owner)
        check(status == 200, 'Save grouping')
        rev = saved['revision']
        check(server.call('save', first, owner)[1]['revision'] == rev, 'Lost-ack replay does not duplicate an end')
        check(server.call('changes', token=member)[1]['changes'] == [{'id': 'fixture', 'revision': rev}], 'Change cursor')
        check(server.call('group&id=fixture', token=member)[1]['body'] == first['body'], 'Exact 2D document bytes preserved')
        group['name'] = 'Member edit'
        second = wire(group, rev)
        status, updated = server.call('save', second, member)
        check(status == 200 and updated['revision'] > rev, 'Shared edit increments revision')
        group['name'] = 'Offline owner edit'
        check(server.call('save', wire(group, rev), owner)[0] == 409, 'Concurrent offline changes conflict')
        check(server.call('group&id=fixture', token=owner)[1]['body'] == second['body'], 'Conflict leaves acknowledged version unchanged')
        check(server.call('changes&after='+str(updated['revision']), token=member)[1]['changes'] == [], 'No repeated changes')
        group['views'] = [{'file': '../escape.jpg'}]
        check(server.call('save', wire(group, updated['revision']), owner)[0] == 400, 'Unsafe image references rejected')
        group['views'] = []
        check(server.call('save', wire(group, updated['revision'], []), owner)[0] == 200, 'Coordinate-only update')
        check(server.call('photo&hash='+h, token=member)[1] == photo, 'Removing local image references never deletes shared historical photos')
        # Exercise the Android sync engine against this real server with two
        # independent filesystem stores, credentials supplied only through stdin.
        sdk = os.environ.get('ANDROID_HOME', '/Users/diego/Library/Android/sdk')
        jdk = os.environ.get('JAVA_HOME', '/Applications/Android Studio.app/Contents/jbr/Contents/Home')
        cp = f'build/shooter-tests:deps/test/json-20240303.jar:{sdk}/platforms/android-34/android.jar:deps/commons-math3.jar'
        java = str(Path(jdk) / 'bin/java')
        if not (ROOT / 'build/shooter-tests/org/redwoodarrows/SyncEngineTest.class').is_file():
            raise RuntimeError('Compile SyncEngineTest before running the server suite')
        if True:
            with Server() as two_phones:
                credentials = []
                for username, key in [('alex', 'diego_invite'), ('sam', 'iris_invite')]:
                    status, account = two_phones.call('signup', {'username': username, 'password': 'test-only long passphrase', 'invite': two_phones.invites[key]})
                    assert status == 200
                    credentials.append(account['token'])
                subprocess.run([java, '-cp', cp, 'org.redwoodarrows.SyncEngineTest'], cwd=ROOT, input=json.dumps({'endpoint': two_phones.url, 'owner': credentials[0], 'member': credentials[1], 'photo': base64.b64encode(photo).decode()}).encode(), check=True, timeout=120)
        check(server.call('password', {'old_password': 'wrong', 'password': 'next test passphrase'}, owner)[0] == 403, 'Password change requires old password')
        status, replacement = server.call('password', {'old_password': 'test-only long passphrase', 'password': 'next test passphrase'}, owner)
        check(status == 200, 'Password changed')
        check(server.call('me', token=owner)[0] == 401, 'Password change revokes old device token')
        check(server.call('me', token=replacement['token'])[0] == 200, 'New device token works')
        check(server.call('logout', {}, member)[0] == 200, 'Logout')
        check(server.call('me', token=member)[0] == 401, 'Logout revokes token')
        for _ in range(16):
            last = server.call('login', {'username': 'locked', 'password': 'wrong'})[0]
        check(last == 429, 'Brute-force throttling')
        import sqlite3
        db = sqlite3.connect(server.private / 'library.sqlite')
        check(db.execute('SELECT COUNT(*) FROM versions WHERE group_id=?', ('fixture',)).fetchone()[0] == 3, 'Every accepted version retained for recovery')
        check(not any(owner in str(row) for row in db.execute('SELECT * FROM tokens')), 'Server stores token hashes only')
        check(all(row[0].startswith('$argon2id$') for row in db.execute('SELECT password FROM users')), 'Passwords hashed with Argon2id')
        db.close()
        check(not list((server.private / 'blobs').glob('.upload-*')), 'Upload temporary files cleaned')
    check(not server.directory.exists(), 'All disposable fixtures and credentials removed')
    print(f'Sync server checks passed: {checks}')


if __name__ == '__main__':
    run()
