"""Red TV local media server. Python 3 standard library only; Ctrl+C stops access."""
import argparse
import mimetypes
import secrets
import socket
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import quote, unquote, urlsplit

MEDIA = {'.mp4', '.mkv', '.webm', '.mov', '.m4v', '.mp3', '.m4a', '.aac', '.flac', '.wav', '.ts'}

def byte_range(header, length):
    if not header:
        return 0, length - 1, False
    if not header.startswith('bytes=') or ',' in header:
        raise ValueError('Unsupported range')
    left, right = header[6:].split('-', 1)
    if not left:
        count = int(right)
        if count <= 0:
            raise ValueError('Invalid suffix')
        start, end = max(0, length - count), length - 1
    else:
        start, end = int(left), int(right) if right else length - 1
    if start < 0 or start >= length or end < start:
        raise ValueError('Unsatisfiable range')
    return start, min(end, length - 1), True

def make_handler(files, token):
    # An explicit fixed ID map, never a path supplied by a client.
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, *_):
            pass  # URLs contain access tokens; do not log them.
        def do_HEAD(self):
            self.send_media(False)
        def do_GET(self):
            self.send_media(True)
        def send_media(self, include_body):
            parts = urlsplit(self.path).path.strip('/').split('/')
            if len(parts) != 3 or not secrets.compare_digest(parts[0], token) or parts[1] not in files:
                self.send_error(404); return
            path, root = files[parts[1]]
            try:
                resolved = path.resolve(strict=True)
                if not resolved.is_relative_to(root) or resolved != path or not resolved.is_file():
                    self.send_error(404); return
                with resolved.open('rb') as source:
                    size = resolved.stat().st_size
                    try:
                        start, end, partial = byte_range(self.headers.get('Range'), size)
                    except (ValueError, TypeError):
                        self.send_response(416)
                        self.send_header('Content-Range', f'bytes */{size}')
                        self.send_header('Content-Length', '0'); self.end_headers(); return
                    self.send_response(206 if partial else 200)
                    self.send_header('Content-Type', mimetypes.guess_type(path.name)[0] or 'application/octet-stream')
                    self.send_header('Content-Length', str(max(0, end - start + 1)))
                    self.send_header('Accept-Ranges', 'bytes')
                    self.send_header('Cache-Control', 'no-store')
                    if partial:
                        self.send_header('Content-Range', f'bytes {start}-{end}/{size}')
                    self.end_headers()
                    if include_body:
                        source.seek(start)
                        remaining = end - start + 1
                        while remaining > 0:
                            data = source.read(min(256 * 1024, remaining))
                            if not data: break
                            self.wfile.write(data); remaining -= len(data)
            except (OSError, ConnectionError):
                return
    return Handler

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('directory', type=Path, help='Folder containing media to share (no subfolders)')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--address', help='Your laptop private LAN IPv4 address')
    args = parser.parse_args()
    root = args.directory.expanduser().resolve(strict=True)
    if not root.is_dir(): parser.error('Choose a directory')
    address = args.address
    if not address:
        candidates = {x[4][0] for x in socket.getaddrinfo(socket.gethostname(), None, socket.AF_INET)}
        private = [x for x in candidates if x.startswith(('192.168.', '10.')) or (x.startswith('172.') and 16 <= int(x.split('.')[1]) <= 31)]
        if len(private) != 1: parser.error('Use --address with your laptop Wi-Fi/Ethernet IPv4 address')
        address = private[0]
    import ipaddress
    ip = ipaddress.ip_address(address)
    if ip.version != 4 or not (address.startswith(('192.168.', '10.')) or (address.startswith('172.') and 16 <= int(address.split('.')[1]) <= 31)):
        parser.error('Use a private home-network IPv4 address')
    files = {str(i): (p.resolve(), root) for i, p in enumerate(sorted(root.iterdir())) if p.is_file() and not p.is_symlink() and p.suffix.lower() in MEDIA}
    if not files: parser.error('No supported media files in this folder')
    token = secrets.token_urlsafe(32)
    server = ThreadingHTTPServer((address, args.port), make_handler(files, token))
    server.daemon_threads = True
    print('Sharing ONLY listed files on your home network. Keep links private. Ctrl+C revokes access.\n')
    for key, (path, _) in files.items():
        print(f'{path.name}\nhttp://{address}:{args.port}/{token}/{key}/{quote(path.name)}\n')
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally: server.server_close()

if __name__ == '__main__': main()
