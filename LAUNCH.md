# Launching Ghostly on Google Play

Everything that can be prepared from here is prepared. What is left needs your Google account in a
browser, so it is written as steps you can follow straight down the page.

## What's ready

| File | Use |
| --- | --- |
| `app/build/outputs/bundle/release/app-release.aab` | **The upload.** Play requires an App Bundle, not an APK |
| `app/build/outputs/apk/release/app-release.apk` | Sideload/testing copy — not uploaded to Play |
| `play/store-listing.md` | Title, descriptions, category, and every Console answer |
| `play/graphics/icon-512.png` | App icon (512×512) |
| `play/graphics/feature-1024x500.png` | Feature graphic (1024×500) |
| `play/screenshots/*.png` | Four **phone** screenshots (1080×2160, captioned) |
| `play/screenshots-tablet7/*.png` | Two **7-inch tablet** screenshots (1200×1920) |
| `play/screenshots-tablet10/*.png` | Two **10-inch tablet** screenshots (1600×2560) |
| `play/demo/ghostly-demo.mp4` | 25s screen recording for the foreground-service declaration |
| `docs/privacy-policy.html` | Privacy policy, ready for GitHub Pages |
| `ghostly-release.jks` + `keystore.properties` | Your upload key — **back these up** |

App identity, fixed at first upload and never changeable: **`com.shashank.ghostly`**. This build is
version 1.5.0 (versionCode 8), min Android 8.0, targets API 36 (required for new apps since
31 Aug 2026).

### What changed since the 1.0.1 material was written

The app is a different thing now, so most of the listing had to be redone:

- He is a **pet**: needs, moods, a box on the Home tab where feeding, playing and petting happen.
- A **behaviour engine** picks from 852 written reactions by time of day, mood, battery and more,
  and twelve ways of moving — including tumbling, bouncing, pacing, orbiting and peeking.
- **Optional Google sign-in** with a server of our own. This is the important one for the Console:
  the old listing said "collects nothing, no account, works entirely offline", which is no longer
  true, and **Data safety must be filled in accordingly** — see `play/store-listing.md`.
- A complete visual redesign, black and white, so **every screenshot was retaken**.
- Play **in-app updates**, so a future release can offer itself from inside the app.

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
- **Data safety** → **collected, optional, not shared** — email, name, user ID, device ID and his
  stats, only if the user signs in. Full answers in `play/store-listing.md`. This changed
  completely from the 1.0.1 draft; do not reuse the old "collects nothing" answers.
- **Account deletion URL** → `https://shashanking.github.io/ghostly-privacy/delete-account.html`
- **Advertising ID** → not used
- **Foreground service permissions** → declare `FOREGROUND_SERVICE_SPECIAL_USE`, paste the
  justification from `play/store-listing.md`, and attach the demo video. Upload
  `play/demo/ghostly-demo.mp4` to YouTube as **Unlisted** and paste that link.

This last one is the only part of the review with any real risk: Google reviews `specialUse`
justifications by hand and can come back asking why a defined type does not fit. The written answer
covers exactly that, and the video shows the overlay being summoned, floating over other apps, and
being stopped from its notification.

## 5b. In-app products (only needed once)

The **Monetize** section stays locked until Play has seen a build carrying
`com.android.vending.BILLING` — which is versionCode 8 onwards. So this happens *after* the first
upload of that build, in any track, not before.

1. **Setup → Payments profile** → create the Google Payments merchant account. India is supported.
   Expect identity and bank verification; it can take a few days, and nothing below works until it
   is approved.
2. **Monetize → Products → In-app products → Create product**, three times. The product IDs must
   match `TokenPacks.ALL` in `app/src/main/java/com/shashank/ghostly/Billing.kt` **exactly**, and
   can never be changed once published:

   | Product ID | Name | What it grants |
   | --- | --- | --- |
   | `tokens_handful` | A handful | 60 tokens |
   | `tokens_pocketful` | A pocketful | 200 tokens |
   | `tokens_hoard` | A hoard | 600 tokens |

   Set a price for your home country and let Play convert the rest. Set each one **Active** — an
   inactive product is simply missing from `queryProductDetailsAsync`, and the Tokens shelf then
   hides itself with no error anywhere.
3. **Testing → License testing** → add your own Google account, so you can run the whole purchase
   flow without being charged.
4. Test on a build installed **from Play** (internal testing track is enough). Billing does not work
   on a sideloaded APK — `queryProductDetailsAsync` returns nothing, so the shelf will not appear.

Play takes 15% of the first $1M of yearly revenue, 30% above that.

## 6. Release worldwide

> **If this is a personal (individual) developer account created after 13 Nov 2023, Production is
> locked.** Google requires a closed test with **at least 12 testers, opted in continuously for 14
> days**, before production access can even be applied for. Testers who opt out and back in restart
> their 14 days. This is not a review delay you can wait out — the Production section stays disabled
> until it is done, so start the closed test first and treat the 14 days as the real lead time.
> Organisation accounts are exempt.
> <https://support.google.com/googleplay/android-developer/answer/14151465>

**Production → Create new release**

- Upload `app/build/outputs/bundle/release/app-release.aab`
- Keep **Play App Signing** enabled (the default)
- Release name: `1.5.0 (8)` · Release notes: paste the **1.5.0** block from the **Release notes**
  section of `play/store-listing.md` (Play's limit is 500)
- **Countries/regions → select all** for a worldwide launch
- Save → Review release → **Start rollout to Production** (100%)

First reviews typically take a few days, and longer for a brand-new developer account.

## 7. After it is live

- Installing from Play also gets rid of the "restricted settings" block you hit while sideloading —
  Play installs are exempt, so users grant "Display over other apps" normally.
- To ship an update: raise `versionCode` (and `versionName`) in `app/build.gradle.kts`, run
  `./gradlew bundleRelease`, upload the new `.aab`.

## Versioning

This build is versionCode `8`, versionName `1.5.0`. Play rejects an upload whose versionCode it has
seen before, so raise `versionCode` in `app/build.gradle.kts` for every upload — even a re-upload
of a rejected build.

## Rebuilding

```bash
./gradlew bundleRelease   # app/build/outputs/bundle/release/app-release.aab  (Play)
./gradlew assembleRelease # app/build/outputs/apk/release/app-release.apk     (sideload)
```

To ship a later update: raise `versionCode`, adjust `versionName`, rebuild the bundle, upload.
