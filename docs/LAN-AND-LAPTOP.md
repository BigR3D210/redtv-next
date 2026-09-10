# Laptop videos and LAN rooms

All features run locally, without accounts, subscription fees or cloud services. Use a trusted home network: HTTP traffic is not encrypted. Do not forward ports on your router.

## Laptop editor
Open Red TV's laptop editor. Enter its displayed address on a laptop/phone, then type the six-digit code. The code accepts ten attempts during ten minutes; reopen the screen for a fresh code. Browser sessions last at most eight hours and closing/leaving the screen revokes them immediately. Cookies are HttpOnly and SameSite Strict; mutations require a session CSRF token. No stored password, source URL or username is returned to the browser. Blank source fields preserve saved values. The channel table includes hidden items for recovery, search, selection, favorites, category, rename and move controls. Press Done after edits to reload the library.

Send a direct HTTP(S) video link in the editor. Opening playback closes the editor session; return to pair again for further edits. Browser-only video pages and DRM sites are not supported by direct links.

## Laptop files (Python 3.9+)
1. Install Python 3 if needed. Put videos to share in one dedicated folder. Only immediate files with recognized media extensions are exposed; subfolders and symlinks are excluded.
2. In PowerShell run `py -3 .\companion\redtv_media_server.py "C:\Users\david\Videos\TV"`. If asked to specify an address, append `--address 192.168.1.20` using the laptop's actual Wi-Fi/Ethernet IPv4 address. `Start-RedTvMedia.ps1 -Directory "..." -Address "..."` is an alternative launcher.
3. Allow Python through Windows Firewall on your private home network if Windows asks. Paste the printed video URL into Red TV's paired browser editor and choose Play on TV.
4. Keep laptop awake and the terminal running. Ctrl+C stops sharing and invalidates every link. Restart creates fresh random links. Anyone holding a link on the LAN can fetch that file; do not share links publicly.

The server binds to your selected private network interface. Byte ranges support seeking. No transcoding occurs: Fire TV / Android TV / Google TV codec support determines whether each file plays. MP4 with H.264 video and AAC audio is a practical common format; test your actual files. Laptop upload/Wi-Fi bandwidth limits simultaneous playback.

## Another TV
Start room sharing explicitly in the host player, then use Join room on another TV and enter host address/code. Pairing shares the current playable link and headers, including any embedded provider credentials, with that TV. Host pause/play and channel changes lead the room. VOD positions and live streams with absolute HLS programme time can be corrected for drift. Live streams without timestamps use the same channel at the live edge, best effort. No frame-perfect sync, relay or provider connection-limit bypass is promised. Each TV opens its own stream. Stop hosting / exit player to revoke access.

Device acceptance checklist: pair from a laptop, test invalid code lockout and reopening, rename/hide/unhide/reorder and restart app, source-save reload, seek laptop video, pause/resume VOD room on two TVs, timed/untimed live room change, host exit/reconnect, and test two/four concurrent decoders on each actual stick/box. These require physical devices; JVM/HTTP tests cannot certify codec or D-pad behavior.

Run companion tests with `py -3 -m unittest discover -s companion -p "test_*.py"`.
