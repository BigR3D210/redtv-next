"""Pair with Red TV Next and play its current source using installed 64-bit VLC.

No extra Python packages. Credentials stay in memory. The TV must keep playing.
Live playback follows the channel, but independent buffers are not frame synced.
"""
import ctypes as C
import ipaddress
import json
import os
from pathlib import Path
import queue
import threading
import time
import tkinter as tk
from tkinter import ttk
import urllib.error
import urllib.parse
import urllib.request


def normalize_address(value):
    value = value.strip().rstrip('/')
    u = urllib.parse.urlsplit(value if '://' in value else 'http://' + value)
    address = ipaddress.IPv4Address(u.hostname or '')
    allowed = any(address in ipaddress.ip_network(n) for n in ('10.0.0.0/8', '172.16.0.0/12', '192.168.0.0/16'))
    if not allowed or u.scheme != 'http' or u.username or u.password or u.path or u.query or u.fragment:
        raise ValueError('Enter the local address shown on your TV, including its port.')
    port = u.port if u.port is not None else 8091
    if not 1 <= port <= 65535:
        raise ValueError('Invalid port')
    return f'http://{address}:{port}'


def vod_target(state, server_time, round_trip_ms):
    advance = max(0, min(15000, server_time - state['capturedAtEpochMs'] + round_trip_ms // 2)) if state['playing'] else 0
    return max(0, state['positionMs'] + advance)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


class Room:
    def __init__(self, address, code):
        self.base = normalize_address(address)
        self.token = None
        self.http = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
        self.token = self.request('/pair', {'code': code})['token']
        if not isinstance(self.token, str) or len(self.token) != 64:
            raise ValueError('Invalid pairing response')

    def request(self, path, body=None):
        headers = {'Content-Type': 'application/json'}
        if self.token:
            headers['Authorization'] = 'Bearer ' + self.token
        req = urllib.request.Request(self.base + path, data=json.dumps(body).encode() if body is not None else None, headers=headers)
        with self.http.open(req, timeout=4) as response:
            return json.loads(response.read(1024 * 1024))


class VLC:
    def __init__(self):
        folder = Path(os.environ.get('REDTV_VLC_DIR', r'C:\Program Files\VideoLAN\VLC'))
        self.dll_dir = os.add_dll_directory(str(folder))
        self.lib = C.CDLL(str(folder / 'libvlc.dll'))
        def bind(name, result, *args):
            fn = getattr(self.lib, 'libvlc_' + name)
            fn.restype, fn.argtypes = result, list(args)
            return fn
        ptr = C.c_void_p
        self.new = bind('new', ptr, C.c_int, C.POINTER(C.c_char_p))
        self.new_player = bind('media_player_new', ptr, ptr)
        self.new_media = bind('media_new_location', ptr, ptr, C.c_char_p)
        self.option = bind('media_add_option', None, ptr, C.c_char_p)
        self.set_media = bind('media_player_set_media', None, ptr, ptr)
        self.release_media = bind('media_release', None, ptr)
        self.play = bind('media_player_play', C.c_int, ptr)
        self.pause = bind('media_player_set_pause', None, ptr, C.c_int)
        self.position = bind('media_player_get_time', C.c_int64, ptr)
        self.seek = bind('media_player_set_time', None, ptr, C.c_int64)
        self.state = bind('media_player_get_state', C.c_int, ptr)
        self.stop = bind('media_player_stop', None, ptr)
        self.release_player = bind('media_player_release', None, ptr)
        self.release = bind('release', None, ptr)
        args = (C.c_char_p * 3)(b'--quiet', b'--no-video-title-show', b'--network-caching=1000')
        self.instance = self.new(3, args)
        if not self.instance:
            raise RuntimeError('VLC could not start')
        self.player = self.new_player(self.instance)
        if not self.player:
            self.release(self.instance)
            raise RuntimeError('VLC player could not start')

    def load(self, channel):
        url = channel['streamUrl']
        if urllib.parse.urlsplit(url).scheme not in ('http', 'https'):
            raise ValueError('This receiver supports web streams only.')
        media = self.new_media(self.instance, url.encode())
        if not media:
            raise RuntimeError('Unable to open this stream')
        try:
            for header, value in channel.get('headers', {}).items():
                option = {'user-agent': 'http-user-agent', 'referer': 'http-referrer'}.get(header.lower())
                if option and '\n' not in value and '\r' not in value:
                    self.option(media, f':{option}={value}'.encode())
            self.set_media(self.player, media)
        finally:
            self.release_media(media)
        if self.play(self.player) < 0:
            raise RuntimeError('Unable to start playback')

    def close(self):
        self.stop(self.player)
        self.release_player(self.player)
        self.release(self.instance)
        self.dll_dir.close()


def follow(address, code, stopped, messages):
    vlc = None
    try:
        room = Room(address, code)
        vlc = VLC()
        current = None
        failures = 0
        while not stopped.is_set():
            started = time.monotonic()
            try:
                envelope = room.request('/state')
            except (OSError, urllib.error.URLError):
                failures += 1
                if failures >= 4:
                    raise RuntimeError('TV disconnected. Start sharing on the TV and reconnect.')
                messages.put('Reconnecting to TV…')
                stopped.wait(2)
                continue
            failures = 0
            state = envelope.get('state')
            if not state:
                messages.put('Paired. Waiting for TV playback…')
                stopped.wait(1)
                continue
            channel = state['channel']
            identity = (channel['id'], channel['streamUrl'])
            if current != identity:
                vlc.load(channel)
                current = identity
            playback_state = vlc.state(vlc.player)
            if playback_state == 7:
                raise RuntimeError('The stream could not play. Your provider may limit simultaneous streams.')
            vod = channel.get('kind') in ('movie', 'episode')
            if playback_state in (3, 4):
                if vod:
                    target = vod_target(state, envelope['serverTimeMs'], int((time.monotonic() - started) * 1000))
                    if abs(vlc.position(vlc.player) - target) > 3000:
                        vlc.seek(vlc.player, target)
                vlc.pause(vlc.player, 0 if state['playing'] else 1)
            label = 'Playing' if playback_state == 3 else 'Paused' if playback_state == 4 else 'Loading'
            messages.put(f"{label}: {channel['name']}\n" + ('Following the TV position.' if vod else 'Following the TV channel. Live timing may differ.'))
            stopped.wait(1.5)
    except Exception as exc:
        # Never display HTTP exception URLs or source credentials.
        message = str(exc) if isinstance(exc, RuntimeError) else 'Could not connect. Check the TV address/code and that 64-bit VLC is installed.'
        messages.put(message)
    finally:
        if vlc:
            vlc.close()
        messages.put(None)


def main():
    root = tk.Tk()
    root.title('Red TV — Watch on this laptop')
    root.geometry('560x330')
    box = ttk.Frame(root, padding=20)
    box.pack(fill='both', expand=True)
    ttk.Label(box, text='On your TV: play a channel → Options → Share to another TV.\nKeep the TV playing and both devices on the same home network.').pack(anchor='w', pady=(0, 12))
    ttk.Label(box, text='TV address (copy the complete address, including the port)').pack(anchor='w')
    address = ttk.Entry(box, width=55)
    address.insert(0, 'http://192.168.1.249:8091')
    address.pack(fill='x')
    ttk.Label(box, text='Six-digit pairing code').pack(anchor='w', pady=(8, 0))
    code = ttk.Entry(box, width=12)
    code.pack(anchor='w')
    messages = queue.Queue()
    stopped = threading.Event()
    worker = None
    status = tk.StringVar(value='Live channels may be several seconds apart. VLC opens a video window.')
    def connect():
        nonlocal worker
        if worker and worker.is_alive():
            stopped.set()
            return
        try:
            host = normalize_address(address.get())
            pin = code.get().strip()
            if len(pin) != 6 or not pin.isdecimal():
                raise ValueError()
        except ValueError:
            status.set('Enter the local TV address and its six-digit code.')
            return
        stopped.clear()
        status.set('Connecting…')
        button.config(text='Disconnect')
        worker = threading.Thread(target=follow, args=(host, pin, stopped, messages), daemon=True)
        worker.start()
    button = ttk.Button(box, text='Watch on laptop', command=connect)
    button.pack(anchor='w', pady=12)
    ttk.Label(box, textvariable=status, wraplength=510).pack(anchor='w')
    def poll():
        while not messages.empty():
            message = messages.get_nowait()
            if message is None:
                button.config(text='Watch on laptop')
            else:
                status.set(message)
        root.after(200, poll)
    def close():
        stopped.set()
        if worker and worker.is_alive():
            status.set('Closing playback…')
            root.after(100, close)
        else:
            root.destroy()
    root.protocol('WM_DELETE_WINDOW', close)
    poll()
    root.mainloop()


if __name__ == '__main__':
    main()
