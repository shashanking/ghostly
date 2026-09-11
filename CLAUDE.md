# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

Ghostly is a single-module Android app (Kotlin, no third-party dependencies) that draws an animated
ghost in a `TYPE_APPLICATION_OVERLAY` window above every other app.

## Build

```bash
./gradlew assembleDebug     # app/build/outputs/apk/debug/app-debug.apk
./gradlew assembleRelease   # signed sideload APK
./gradlew bundleRelease     # app/build/outputs/bundle/release/app-release.aab — the Play upload
./gradlew ktlintCheck       # must stay green; ./gradlew ktlintFormat fixes most of it
```

- `ANDROID_HOME` is not set on this machine; the SDK path comes from `local.properties` (gitignored).
  A fresh clone needs `sdk.dir=$HOME/Library/Android/sdk` written there before Gradle will run.
- Only JDK 23 is installed. Gradle 8.14.1 wrapper, AGP 8.10.1, Kotlin 2.2.0, `jvmTarget` 17.
- Release builds are signed from `keystore.properties` + `ghostly-release.jks`. Both are gitignored
  and must never be committed — that key is the only one Play will accept for updates.

## Testing overlay behaviour on the emulator

Changes to drift, touch handling, or the service lifecycle are only done once they have been run on
a device. A clean compile proves nothing about an overlay.

```bash
adb uninstall com.shashank.ghostly          # required when switching debug <-> release
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell appops set com.shashank.ghostly SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.shashank.ghostly android.permission.POST_NOTIFICATIONS

# live position, size and window flags of the ghost — the main way to assert behaviour
adb shell dumpsys window windows | grep -A5 "Window{.*u0 com.shashank.ghostly}"
adb shell dumpsys activity services com.shashank.ghostly | grep isForeground
```

- Debug and release APKs have different signatures: installing one over the other fails with
  `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall first.
- `uiautomator dump` always fails here with "could not get idle state" — the overlay animates
  continuously, so the device is never idle. Use screenshots plus `dumpsys` instead.
- A cold start after install takes several seconds; scripted `adb shell input tap` lands on nothing
  if it fires too early. Screenshot to confirm the screen before tapping.

## Platform constraints — do not "fix" these

Each of these was measured on device and the obvious-looking change re-breaks it:

- **`ACTION_OUTSIDE` carries no coordinates.** On Android 12+ they arrive as `rawX=0, rawY=0`
  (verified on API 35). Never build proximity logic on them.
- **Click-through and knowing where a tap landed are mutually exclusive.** `FLAG_NOT_TOUCHABLE`
  (the default, `Prefs.clickThrough`) means nothing underneath is ever blocked but taps cannot be
  located; the touchable window locates them but swallows them. Both modes must keep working.
- **The frame loop must stay restartable and screen-aware.** A bare `Choreographer` chain — where
  each frame schedules the next — freezes permanently if the platform drops the pending callback at
  display-off (reported on a Galaxy S24). Keep `startLoop`/`stopLoop`, the screen on/off receiver,
  and the watchdog.
- **A foreground service start from a broadcast can be refused** (`MY_PACKAGE_REPLACED` is not an
  exemption) and an unhandled refusal crashes the process. Always start through
  `GhostOverlayService.start()`, which returns a Boolean, or `Recall.bringBack()`.
- **No `setShadowLayer` or `LAYER_TYPE_SOFTWARE` in `GhostView`.** It forces the whole view through
  software rendering on every frame; the glow is a cached `RadialGradient` instead.
- **A species' ears must fit inside the body's own padding.** `GhostView` draws him in a square
  with a tenth of his width as padding, and that margin — plus whatever headroom the view has —
  is all the room ears, horns and antlers get. A square preview (widget, share card, picker cards)
  has no headroom at all, so anything taller than about a quarter of his radius comes out sliced
  off flat there while looking perfect on screen. The in-app previews are sized taller than wide
  for exactly this reason; keep them that way.
- **The 30fps cap (`MIN_FRAME_SECONDS`) is deliberate** — measured at roughly half the CPU of 45fps,
  and every frame moves a window, which is not free. Build gradients and shaders once, not per frame.

## Backend (`server/`)

Node/Fastify API + its own Postgres, in Docker on the Bluehost VPS behind the existing Traefik.
Public base URL: `https://skf.npf.mybluehost.me/ghostly/v1` (`GhostlyApi.BASE_URL`).

- **The VPS is read-only unless Shashank explicitly approves a specific change.** It also runs a
  live Discord agent ("Raman", `openclaw-gateway.service`, native) and other people's services.
- Deploy: `rsync server/ bluehost-vps:/opt/hostedapps/ghostly/ && ssh bluehost-vps /opt/hostedapps/ghostly/deploy.sh`
  (idempotent: builds, migrates, re-applies grants). Secrets live in `/opt/hostedapps/ghostly/.env`, never in git.
- **Never use the `closdex-pg` container** — different project. Ghostly's DB is `ghostly-pg` (host-local `127.0.0.1:5433`).
- Two schemas, two roles: `app.*` (users/pets/state — API only) and `content.*` (reactions/species/
  daily/settings — the editor role `raman`, who can read only anonymised `app.aggregates`).
- Content flow: editor edits `content.reactions` → `ghostly-publish` validates and snapshots a new
  `content.packs` version → phones download it daily; the bundled `assets/behaviour-pack.json` is the floor.
  Editor tools on the box: `ghostly-sql`, `ghostly-seed <pack.json>`, `ghostly-publish [--dry-run]`.
- Talk to Raman non-interactively: `openclaw agent --message-file f.md --channel discord --deliver`
  with `PATH=/opt/openclaw/npm-global/bin:/opt/node-24/bin` and `OPENCLAW_GATEWAY_TOKEN` from the systemd unit.
  Long generations time out the CLI at 420s — run them under `nohup` with `--timeout`.
- Google Sign-In only works on builds whose signing SHA-1 is registered in the GCP project; our
  debug key is `38:98:38:26:33:14:29:D8:E5:00:E6:35:F0:F2:6F:05:0F:4B:7A:CA`.

## Releasing

Full Play Console process, listing copy and asset locations: @LAUNCH.md

- Raise `versionCode` in `app/build.gradle.kts` for **every** upload; Play permanently rejects a
  versionCode it has already seen, including re-uploads of a rejected build.
- New apps must target the current required API level (36 as of 31 Aug 2026).
- Store assets live in `play/`; the privacy policy page is `docs/privacy-policy.html`, served from
  the separate public repo `shashanking/ghostly-privacy`.

## Git

Solo project: commit and push straight to `main` when asked. Never commit `keystore.properties`,
`*.jks`, `local.properties`, or build outputs.
