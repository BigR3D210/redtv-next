# Red TV upgrade progress
Plan: docs/superpowers/plans/2026-09-09-redtv-upgrade.md

## Implemented
- Provider isolation, stable identities, cached catalogs, persisted queues and episode history, encrypted private storage, recoverable hidden channels.
- Resume/completion, optional episode autoplay, direct/local playback, reconnect/retry, language/picture preferences, sleep timer and previous-channel controls.
- Two/four pane multiview and explicit paired room synchronization.
- Paired browser editor, bulk channel customization and sorting, source edits, link handoff, laptop range-aware media server.
- Updated browser, poster cards, season selector, guide timeline, history, direct-link/USB entry points and room join UI.
- Checked-in wrapper, signed-release workflow, update verification, documentation.

## Verification — 2026-09-10
- scripts/build.ps1 testDebugUnitTest lintDebug assembleDebug: BUILD SUCCESSFUL.
- 25 Android/JVM tests passed, zero failures, zero skipped.
- 2 Python companion HTTP/range/traversal tests passed.
- Lint: zero errors. Warnings remain for untranslated text, unused resources, existing icons, dependency versions and deliberately permitted source HTTP.
- Regression fixed from observed failing HTTP test: rejected POST request body was left on a persistent connection; editor now closes responses, preventing the next request from being misparsed.
- Original three parser regressions were observed failing against baseline and passing after fixes.
- No attached Android device reported by adb. Hardware decoding, remote focus, actual playback and multi-TV synchronization have not been exercised on a physical TV.

## Delivery limits
- APK is debug-signed for sideload/device testing; not a published signed release.
- Android API23+/FireOS6+. Four-pane capacity depends on hardware and provider connections.
- Timed live/VOD room sync is best effort; untimed streams follow the same live channel. No frame-perfect guarantee, relay or transcoding.
- Local file picker support depends on the TV; laptop HTTP links work through the included companion.
- No DVR, cloud service or subscription charges added. Preserve the original signing key to update an existing installation without deleting its data.
- Baseline preserved in commit e74d5ba. No push or publication performed.
