# Build and update Red TV

Requires JDK 17 and Android SDK platform 34. The checked-in Gradle wrapper uses Gradle 8.10.2 and verifies its distribution SHA-256. Android 6.0 / API 23 or newer is required (including Fire OS 6+); older Fire OS 5 devices are unsupported by this upgrade.

## Local Windows build

Set `JAVA_HOME` to your JDK 17 directory and `ANDROID_HOME` to your Android SDK. Then run:

```powershell
./scripts/build.ps1
```

Alternatively pass `-JavaHome 'C:/path/to/jdk17' -AndroidSdk 'C:/path/to/android-sdk'`. This runs JVM tests, Android lint and a debug APK build. The APK is `app/build/outputs/apk/debug/app-debug.apk`. A debug APK is for personal testing; it can only replace an installed APK signed with that same debug certificate. Do not uninstall an existing installation merely to bypass a signature mismatch without first preserving its settings.

On Linux/macOS use `chmod +x gradlew` then `./gradlew testDebugUnitTest lintDebug assembleDebug` with the same environment variables. Tests include JUnit4 and Robolectric; first builds require dependency downloads. Hardware playback, DRM, D-pad and network behavior still require device checks.

## GitHub build artifacts

Pushes, pull requests and manual workflow runs build a debug APK after tests and lint. Download **RedTV-debug-apk** from the successful workflow's Artifacts section. These runs do not publish an app release by default.

## Stable release signing

Keep the original release keystore and passwords. Never generate a new key for an installed release identity: Android cannot update between different signing certificates. Earlier GitHub debug builds may have used ephemeral runner debug keys, and those signatures cannot be reconstructed from the APK. Preserve any known original key; installation migration may need manual backup/export if its signing key is unavailable.

For a local release, copy `keystore.properties.example` to `keystore.properties` and fill in the original signing information. `storeFile` is relative to the project root. These files and `*.jks` / `*.keystore` are ignored by git. Run `./gradlew assembleRelease -PversionCode=N` with N higher than the installed version code. An unsigned release is produced if no keystore configuration exists; never distribute it as a signed update.

For GitHub releases, configure these repository/environment secrets with the original key:

- `REDTV_KEYSTORE_BASE64`: base64 of the original keystore
- `REDTV_STORE_PASSWORD`
- `REDTV_KEY_ALIAS`
- `REDTV_KEY_PASSWORD`

Configure the GitHub **release** environment with required reviewers if desired. Only a manual **Run workflow** with **publish_release** selected can publish. Tests and lint must pass first. Missing signing secrets stop the release job. Signing material is removed at job end and is never uploaded as an artifact.

CI version codes are `100 + github.run_number` (local default 100). Keep this workflow's run-number history. If moving repositories or recreating the workflow, increase the offset beyond all existing version codes before publishing. The updater build number remains the workflow run number. A rerun of the same run intentionally retains its version code; use a new manual run for a new update. The workflow never publishes debug APKs as releases.

No release was published as part of this local upgrade.
