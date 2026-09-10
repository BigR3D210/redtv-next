import http.client
import tempfile
import threading
import unittest
from pathlib import Path
from http.server import ThreadingHTTPServer
from redtv_media_server import byte_range, make_handler

class MediaTests(unittest.TestCase):
    def test_ranges(self):
        self.assertEqual(byte_range('bytes=2-4', 10), (2, 4, True))
        self.assertEqual(byte_range('bytes=-3', 10), (7, 9, True))
        self.assertEqual(byte_range('bytes=5-', 10), (5, 9, True))
        for header in ['bytes=20-', 'bytes=-0', 'bytes=2-1', 'bytes=0-1,3-4']:
            with self.assertRaises(ValueError): byte_range(header, 10)
    def test_http_auth_and_seek(self):
        with tempfile.TemporaryDirectory() as tmp:
            root = Path(tmp).resolve(); path = root / 'clip.mp4'; path.write_bytes(b'0123456789')
            server = ThreadingHTTPServer(('127.0.0.1', 0), make_handler({'0': (path, root)}, 'test-secret'))
            thread = threading.Thread(target=server.serve_forever, daemon=True); thread.start()
            try:
                def request(url, method='GET', headers=None):
                    c = http.client.HTTPConnection('127.0.0.1', server.server_port)
                    c.request(method, url, headers=headers or {}); r = c.getresponse()
                    result = (r.status, dict(r.getheaders()), r.read()); c.close(); return result
                status, headers, body = request('/test-secret/0/clip.mp4', headers={'Range': 'bytes=3-6'})
                self.assertEqual((status, body), (206, b'3456'))
                self.assertEqual(headers['Content-Range'], 'bytes 3-6/10')
                self.assertEqual(request('/wrong/0/clip.mp4')[0], 404)
                self.assertEqual(request('/test-secret/../clip.mp4')[0], 404)
                self.assertEqual(request('/test-secret/0/clip.mp4', headers={'Range': 'bytes=99-'})[0], 416)
                self.assertEqual(request('/test-secret/0/clip.mp4', 'HEAD')[2], b'')
            finally: server.shutdown(); server.server_close(); thread.join()

if __name__ == '__main__': unittest.main()
