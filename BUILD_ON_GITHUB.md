# Red TV Next — GitHub updates

Repository: https://github.com/BigR3D210/redtv-next
Download: https://github.com/BigR3D210/redtv-next/releases/latest/download/app-release.apk

This is a separate app: com.redtv.next, launcher label Red TV Next. It can be installed alongside the original Red TV without deleting its data. Sources/settings are separate.

## Updates
Run push.ps1 from this project or push main normally. GitHub checks tests and lint, then builds and publishes a signed APK automatically. On the TV choose Tools → Check for updates.

The new signing key is stored in four encrypted GitHub Actions repository secrets. Never replace that key after installing Next: Android requires it for future updates. The local key is in artifacts/redtv-next-release.jks and its passwords are in the ignored keystore.properties. Back up both privately. Do not upload them as release assets or commit them.

Version codes are 100 + workflow run number; release tags are b<run number>. The updater accepts app-release.apk from this repository and verifies package and signing identity before requesting installation.

## First install
Download app-release.apk from the release page using Downloader or your usual sideload method. Allow installation for that app if required. Open Red TV Next and add your source. Keep the original Red TV installed while trying Next.

Requires Android 6+/Fire OS 6+. Hardware playback, multiview capacity and room sync still need testing on your devices.
