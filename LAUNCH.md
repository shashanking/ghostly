# Launching Ghostly on Google Play

Everything that can be prepared from here is prepared. What is left needs your Google account in a
browser, so it is written as steps you can follow straight down the page.

## What's ready

| File | Use |
| --- | --- |
| `app/build/outputs/bundle/release/app-release.aab` | **The upload.** Play requires an App Bundle, not an APK |
| `Ghostly-1.0.1.apk` | Sideload/testing copy — not uploaded to Play |
| `play/store-listing.md` | Title, descriptions, category, and every Console answer |
| `play/graphics/icon-512.png` | App icon (512×512) |
| `play/graphics/feature-1024x500.png` | Feature graphic (1024×500) |
| `play/screenshots/*.png` | Four **phone** screenshots (1080×2160, captioned) |
| `play/screenshots-tablet7/*.png` | Two **7-inch tablet** screenshots (1200×1920) |
| `play/screenshots-tablet10/*.png` | Two **10-inch tablet** screenshots (1600×2560) |
| `play/demo/ghostly-demo.mp4` | 25s screen recording for the foreground-service declaration |
| `docs/privacy-policy.html` | Privacy policy, ready for GitHub Pages |
| `ghostly-release.jks` + `keystore.properties` | Your upload key — **back these up** |

App identity, fixed at first upload and never changeable: **`com.shashank.ghostly`**, version 1.0.1
(versionCode 2), min Android 8.0, targets API 36 (required for new apps since 31 Aug 2026).

## 1. Back up the signing key

`ghostly-release.jks` with the password in `keystore.properties` is your **upload key**. Copy both
somewhere safe (password manager, private backup) before uploading anything. Play App Signing means
a lost upload key can be reset by Google support, but a lost key still costs you days.

## 2. Privacy policy — done

Published and live at:

```
https://shashanking.github.io/ghostly-privacy/privacy-policy.html
```

It is served from the public repo <https://github.com/shashanking/ghostly-privacy>, which contains
only the policy page — your app source is not on GitHub. To edit it later, change the HTML in that
repo (the master copy also lives here at `docs/privacy-policy.html`) and push; Pages rebuilds in
about a minute.

## 3. Create the app in Play Console

<https://play.google.com/console> → **Create app**

- App name: `Ghostly — Floating Ghost Pet`
- Default language: English (US) · Type: **App** · **Free**
- Tick the declarations (Play policies, US export laws)

## 4. Store listing

Main store listing → paste from `play/store-listing.md`, then upload:

- App icon → `play/graphics/icon-512.png`
- Feature graphic → `play/graphics/feature-1024x500.png`
- Phone screenshots → all four from `play/screenshots/`
- 7-inch tablet screenshots → both from `play/screenshots-tablet7/`
- 10-inch tablet screenshots → both from `play/screenshots-tablet10/`

Only the phone screenshots are strictly required to publish. Tablet ones are not a blocker, but
without them Play flags the listing as not optimised for large screens and the app loses visibility
and featuring eligibility on tablets and Chromebooks — so they are worth the two minutes.

## 5. App content (the paperwork)

Every answer is written out in `play/store-listing.md`. In short:

- **Privacy policy** → `https://shashanking.github.io/ghostly-privacy/privacy-policy.html`
- **App access** → all functionality is available without restrictions
- **Ads** → no ads
- **Content rating** → questionnaire, category "Utility"; comes out rated for everyone
- **Target audience** → 13+, not directed at children
- **Data safety** → no data collected, no data shared
- **Advertising ID** → not used
- **Foreground service permissions** → declare `FOREGROUND_SERVICE_SPECIAL_USE`, paste the
  justification from `play/store-listing.md`, and attach the demo video. Upload
  `play/demo/ghostly-demo.mp4` to YouTube as **Unlisted** and paste that link.

This last one is the only part of the review with any real risk: Google reviews `specialUse`
justifications by hand and can come back asking why a defined type does not fit. The written answer
covers exactly that, and the video shows the overlay being summoned, floating over other apps, and
being stopped from its notification.

## 6. Release worldwide

**Production → Create new release**

- Upload `app/build/outputs/bundle/release/app-release.aab`
- Keep **Play App Signing** enabled (the default)
- Release name: `1.0.1 (2)` · Release notes: e.g. *"First release. A little ghost who floats over
  your apps and drifts away when you tap."*
- **Countries/regions → select all** for a worldwide launch
- Save → Review release → **Start rollout to Production** (100%)

First reviews typically take a few days, and longer for a brand-new developer account.

## 7. After it is live

- Installing from Play also gets rid of the "restricted settings" block you hit while sideloading —
  Play installs are exempt, so users grant "Display over other apps" normally.
- To ship an update: raise `versionCode` (and `versionName`) in `app/build.gradle.kts`, run
  `./gradlew bundleRelease`, upload the new `.aab`.

## Versioning

This build is versionCode `2`, versionName `1.0.1`. Play rejects an upload whose versionCode it has
seen before, so raise `versionCode` in `app/build.gradle.kts` for every upload — even a re-upload
of a rejected build.

## Rebuilding

```bash
./gradlew bundleRelease   # app/build/outputs/bundle/release/app-release.aab  (Play)
./gradlew assembleRelease # app/build/outputs/apk/release/app-release.apk     (sideload)
```

To ship a later update: raise `versionCode`, adjust `versionName`, rebuild the bundle, upload.
