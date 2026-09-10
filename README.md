# Red TV Next — personal IPTV and video player

A free native Android TV / Fire TV / Google TV player. No app account, ads, subscription, cloud backend or feature paywalls. Supply your own playable sources. This app does not include a TV service.

## What's included

- Multiple named M3U and Xtream sources with isolated favorites, ordering, hidden channels and history.
- Stable stream identities, saved catalog startup, background refresh and independent XMLTV guide loading.
- Live TV, movies, series, sports, favorites, Continue Watching, watch history and a recoverable Hidden screen.
- Resume for movies, episodes, direct video links and local files; finished items leave Continue Watching.
- Optional next-episode autoplay (off by default), season selection and watched markers.
- Channel rename, category override, pinning, move earlier/later and name/number/category sorting on TV.
- Paired laptop/phone browser editor: search, bulk favorite/hide/category operations, rename/reorder, source editing and send a media link to TV.
- Direct HTTP(S) media links, local/USB video picker where the device provides one, and a laptop media companion with seeking support.
- Two- or four-pane multiview, one audible pane, replace/remove/retry and full screen.
- Paired room playback: a second TV follows the host's channel, play/pause and timing when the stream supports it.
- Three-hour TV guide timeline with earlier/later navigation, favorites filter and program details.
- Reconnect attempts, manual Retry, previous channel, audio/subtitle selection and language preferences, picture size and sleep timer.
- Encrypted credential/catalog/history files, expiring LAN pairing, CSRF protection and signed-update verification.

## Install and first run

The GitHub release APK is a **signed Red TV Next build**, ready to sideload on Android 6+ (API 23), Fire OS 6+ and Google TV. Older Fire OS 5 devices are not supported by this encrypted-storage build.

Copy the APK to your usual sideloading method, or use:
```
adb connect YOUR_TV_IP:5555
adb install -r app-release.apk
```
On Fire TV enable installation for the app doing the sideload. Open Red TV, select **Sources → Add source**, enter your M3U URL or Xtream login and test the connection. The player can also open a link or local video without adding a source.

Android requires the same signing key for an in-place update. If a previous installation has a different key, do not uninstall casually: that removes app data. Build with that original key to preserve the installation. Red TV Next has a separate app identity (com.redtv.next), so it installs alongside the original Red TV. Add your source again in Next.

## Daily use

- **Guide** opens the timeline. Selecting a program offers playback of its channel live; it does not promise catch-up.
- **Long-press OK** on a tile to favorite, pin, hide/unhide, rename, change category, reorder or manage history.
- **Tools → Sort channels** sets your preferred order. Pinned items remain first.
- **Tools → Playback preferences** controls next-episode autoplay, subtitles and duplicate-name hiding.
- **Continue** lists unfinished movies and episodes. **History** includes previously started playback.
- In playback, use the visible **Options** button or the remote Menu key. Up/down zaps live channels when playback controls are hidden.
- Movie and series poster cards use the artwork your source supplies. Metadata availability depends on the source.
- History retains up to 300 items per source. Legacy favorites with unambiguous IDs migrate once; old positional M3U IDs cannot safely be matched after a playlist changes.

## Organize from a laptop or phone

Open **Tools → Edit from laptop or phone** on the TV. On the same trusted home network, visit its displayed address and enter the six-digit code. No permanent account is needed.

The code expires after ten minutes, attempts are limited, and closing the TV editor signs browsers out. Secret values are never returned in the editor HTML/API; blank source credential fields retain existing values. LAN traffic uses HTTP, so use a trusted home network and never expose these ports to the internet.

For local laptop videos, see [the companion guide](docs/LAN-AND-LAPTOP.md). The companion serves only media files in a folder you choose, with an unpredictable access token and byte-range seeking. Keep it running during playback. Python 3 is required; there is no transcoder.

## Multiview and another room

Choose **Tools → Multiview**, select two to four channels, then Watch. Start with two panes on lower-powered sticks. Extra panes and TVs use additional provider connections and hardware decoders; the app cannot override those limits.

On the host TV: play a stream → **Options → Share playback with another TV**. On the other TV: **Tools → Join another TV**, enter the host address and code. Pairing shares that video's source link with the paired TV. Leaving playback stops hosting.

VOD uses media position; timed live streams use the stream's absolute program clock. Untimed live streams follow the same channel at their own live edge. This is best-effort synchronization, not frame-perfect whole-home audio. Private files selected through a TV's local file picker are not transferable to another TV; use a laptop HTTP link accessible to both.

## Build and checks

JDK 17, Android SDK platform 34 and build tools are required. The checked-in Gradle wrapper downloads Gradle 8.10.2.

```
./gradlew testDebugUnitTest lintDebug assembleDebug
```

Windows:
```powershell
./scripts/build.ps1 -JavaHome "PATH_TO_JDK17" -AndroidSdk "PATH_TO_ANDROID_SDK"
```

Run laptop companion tests with:
```
python -m unittest discover -s companion -p "test_*.py"
```

[Build and signing details](BUILD_ON_GITHUB.md). Pushes to main run checks and automatically publish a signed release when successful. Tools → Check for updates downloads future releases from BigR3D210/redtv-next. Pull requests only run checks.

## Device acceptance checks

Before using this as your everyday player:
1. Add your source and play a live channel; test remote controls, audio and subtitles.
2. Watch a movie/episode, exit, then resume from Continue. Enable autoplay and finish an episode.
3. Rename/reorder/hide/unhide a channel on TV and through the browser; restart the app.
4. Try two panes, then four. If playback fails or stutters, reduce the pane count.
5. Pair another TV and test channel changes, pause/play and drift. Try both live and VOD.
6. Play a laptop file and seek forward/back. Keep the laptop awake.
7. Disconnect networking briefly and confirm retries and cached browsing behave correctly.

Physical TV, codec, stream and synchronization testing is not replaced by automated tests. Recording/DVR, transcoding, DRM-provider integration and cloud remote access are not implemented.


