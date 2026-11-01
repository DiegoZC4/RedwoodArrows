"""No credentials or photos: a loopback stub behaves like the old server."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
import os
from pathlib import Path
import subprocess
import threading
import unittest
from urllib.parse import parse_qs, urlsplit

ROOT = Path(__file__).resolve().parents[1]


class ReviewSyncGuardTest(unittest.TestCase):
    def test_old_server_receives_only_capability_probes(self):
        requests = []

        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append(('GET', parse_qs(urlsplit(self.path).query)))
                payload = json.dumps({'service': 'Redwood Arrows', 'protocol': 1, 'private': True}).encode()
                self.send_response(200)
                self.send_header('Content-Type', 'application/json')
                self.send_header('Content-Length', str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_POST(self):
                requests.append(('POST', {}))
                self.send_error(500)

            do_PUT = do_POST

            def log_message(self, *args):
                pass

        with ThreadingHTTPServer(('127.0.0.1', 0), Handler) as server:
            thread = threading.Thread(target=server.serve_forever, daemon=True)
            thread.start()
            try:
                java = Path(os.environ['JAVA_HOME']) / 'bin/java'
                cp = os.pathsep.join(['build/review/tests', 'build/review/classes', 'deps/test/json-20240303.jar'])
                subprocess.run([str(java), '-cp', cp, 'org.redwoodarrows.SyncCollectionGuardTest',
                                f'http://127.0.0.1:{server.server_port}/api.php'], cwd=ROOT, check=True, timeout=20)
            finally:
                server.shutdown()
                thread.join(timeout=5)
        self.assertEqual(len(requests), 4)
        self.assertTrue(all(method == 'GET' and query == {'collection': ['review'], 'action': ['health']}
                            for method, query in requests))


if __name__ == '__main__':
    unittest.main()
