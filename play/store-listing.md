# Play Store listing — Ghostly

Copy/paste straight into Play Console. Character limits are noted; everything here is inside them.

## App details

| Field | Value |
| --- | --- |
| App name (max 30) | `Ghostly — Floating Ghost Pet` (28) |
| Default language | English (United States) |
| App or game | **App** |
| Category | **Personalization** |
| Tags | Widgets & shortcuts, Wallpapers & themes, Casual |
| Contact email | cvs.devs01@gmail.com |
| Website | `https://shashanking.github.io/ghostly-privacy/` *(optional)* |
| Privacy policy | `https://shashanking.github.io/ghostly-privacy/privacy-policy.html` — **live** |

## Short description (max 80)

```
A shy little ghost that floats over your apps and drifts away when you tap.
```

## Full description (max 4000)

```
Ghostly is a tiny ghost who lives on top of your screen.

He drifts slowly wherever he likes — over your home screen, over whatever app you're in, over nothing at all — bobbing and swaying, hem rippling, eyes following whatever is going on. Tap anywhere and he glides away in a random direction, bounces gently off the edge of the screen, and settles back into his wander.

He is deliberately small, see-through and quiet. He is not a launcher, an assistant or a widget. He is just there, keeping you company.

WHAT HE DOES

• Floats above every app — he stays on screen no matter what you're doing
• Never quite still — a slow, permanent drift, with idle bobbing and a rippling hem
• Runs away when tapped — a soft, ghostly glide, never a snap
• Watches what you're up to — his eyes track his own drift, glance around the screen, and look down at the keyboard while you type
• Taps pass straight through him — buttons and keyboard keys underneath still work, so he never gets in your way
• Or make him solid, if you'd rather poke him directly, drag him somewhere and long-press to open the app
• Three sizes, an optional buzz when he bolts, and a Stop button in his notification

PERMISSIONS, AND WHY

• Display over other apps — this is the whole app. Without it he cannot leave the app's own screen.
• Notifications — Android requires a permanent notification while he is floating. It carries the Stop button.
• Run at startup — so he comes back after you reboot, if he was floating before.
• Vibrate — the optional little buzz when he bolts.

Ghostly collects nothing, sends nothing, and has no account, no ads and no in-app purchases. It works entirely offline.
```

## Graphics checklist

| Asset | File | Play requirement |
| --- | --- | --- |
| App icon | `play/graphics/icon-512.png` | 512×512 PNG |
| Feature graphic | `play/graphics/feature-1024x500.png` | 1024×500 |
| Phone screenshots (required) | `play/screenshots/` ×4 | 2–8, 1080×2160 here |
| 7-inch tablet | `play/screenshots-tablet7/` ×2 | 1200×1920 here |
| 10-inch tablet | `play/screenshots-tablet10/` ×2 | 1600×2560 here |

Play's rule for every screenshot: 320–3840 px per side, and the long side no more than twice the
short side. Raw 1080×2400 phone captures are 2.22:1 and get rejected — the ones here are cropped to
1080×2160 for that reason.

## Console paperwork

### Foreground service permissions (App content → declarations)

Ghostly declares `FOREGROUND_SERVICE_SPECIAL_USE`. Console will ask what it is for:

```
Ghostly draws a small animated ghost character in a system overlay window that the user
deliberately summons and can stop at any time. The service exists solely to keep that overlay
drawn and animating while the user is in other apps, which is the entire purpose of the app and
is continuously visible to the user.

None of the defined foreground service types apply: there is no camera, microphone, location,
media playback, data sync, connected device or phone call involved. The overlay is user-initiated
from the app's home screen, requires the user to grant "Display over other apps", posts an ongoing
notification with a Stop action for as long as it runs, and stops immediately when the user asks.
```

A short screen recording is expected with this declaration — use `play/demo/ghostly-demo.mp4`
(upload to YouTube as **Unlisted** and paste the link).

### Data safety

- Does your app collect or share any required user data? **No**
- Data collected: **none**. Data shared: **none**.
- Is all user data encrypted in transit? **N/A — no data leaves the device**
- Do you provide a way for users to request data deletion? **N/A — no data is collected**
- The app has no analytics, no crash reporting, no ads SDK, no network access at all.

### Content rating questionnaire

- Category: **Utility, Productivity, Communication, or Other**
- Violence, sexuality, language, controlled substances, gambling, user interaction,
  data sharing, personal info: **No** to all
- Expected result: **Everyone / PEGI 3 / rated for all ages**

### Ads / IAP / target audience

- Contains ads: **No**
- In-app purchases: **No**
- Target audience: **13+** (avoids the extra Families policy requirements; the app has no
  child-directed content or design)
- Government app / financial features / health: **No**
