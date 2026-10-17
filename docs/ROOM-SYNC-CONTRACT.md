# Room sync contract

Host playback starts `RoomSyncServer.startAvailable()`: try port 8091, then an available port if occupied. Display the actual listening port in the LAN URL. Publish `RoomState(channel, positionMs, playing, capturedAtEpochMs, programTimeMs)` about every second. Stop on player exit. Pairing expires after 10 minutes; bearer tokens expire after 8 hours and stop revokes all. `programTimeMs` is absolute programme time at the published position, or null. Never include a live-window-relative position as absolute time.

Follower background worker calls `RoomSyncClient(baseUrl).pair(code): String`, then `state(token): RoomState` every 1–2 seconds. Both methods block: never call on main thread. Channel includes source credentials and headers and is shared only after explicit LAN pairing. UI must explain this. Client has `lastRoundTripMs` and adjusts capturedAtEpochMs to the follower clock from server response time. Fail closed on errors; stop following after repeated failures.

`RoomSyncMath.targetPosition(state, now, followerWindowStartEpochMs)` returns VOD position, absolute-program-time aligned live position when both sides have a timeline, or null for untimed live (best effort: play same channel at default live edge). `needsSeek(current,target)` uses 1500ms threshold. Playback pause/play follows host. Host is authoritative; follower controls should disconnect or clearly be temporary. Decoder startup, network jitter and programme time quality prevent frame-perfect claims.

Root UI: request a host URL and six-digit code with a LAN credential-sharing explanation; pair off main thread and pass URL/token to player integration chosen by player owner. No new manifest components are required by networking classes. Editor existing Activity launches a persisted PlayerActivity queue for direct media handoff.

## Windows laptop receiver

Double-click `Watch Red TV on Laptop.cmd` from the project folder. Requires 64-bit VLC in its normal installation folder and Python 3 with Tk (the launcher also detects this laptop's bundled Python). On the TV, play a channel and choose Options → Share to another TV. Enter the complete displayed address and six-digit code in the laptop window. Keep the TV playing. Close the receiver or select Disconnect to stop.

The receiver pairs directly over the LAN; tokens and provider links remain in memory. VLC opens a separate video window. It follows channel changes and movie/episode pause and position, with a three-second seek tolerance. Live playback follows the same source but does not align program timestamps: independent buffering can produce several seconds of difference. The provider must permit simultaneous playback. HTTP/HTTPS streams are supported; local TV files and arbitrary custom HTTP headers are not. User-Agent and Referer headers are supported.

For multiview, select 2–4 channels and press RIGHT on any channel to focus Watch, then OK. UP from Watch returns to the list. Selections survive search changes.
