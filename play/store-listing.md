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
A little ghost who floats over your apps. Feed him, play with him, name him.
```

(76 characters.)

## Full description (max 4000)

```
Ghostly is a small ghost who lives on your screen.

He drifts over whatever you are doing — your home screen, another app, nothing at all — bobbing and swaying, hem rippling, eyes following what's going on. Taps go straight through him, so he never gets in the way of a button or a keyboard key. He is see-through, quiet, and about the size of a thumbnail.

He is also a pet. He gets hungry, tired and bored, and he notices when you have not been round for a while.

HIS BOX, HIS RULES

Everything you do with him happens in his box on the Home tab — feed him, play fetch, hold him to pet him. He is either in the box or out floating, never both. Tap Feed while he is out and he flies back in, does the thing, and drifts off again on his own.

HE HAS A MIND OF HIS OWN

Ghostly reads the time of day, his own mood, your battery, whether you are plugged in, whether your headphones are in, how long it has been since you last said hello — and picks from hundreds of written reactions. He tumbles head over hem, hops along the floor, paces, loops in circles, ducks off the side of the screen and leans back in. Now and then he says something in a small speech bubble above his head.

Nothing is generated on your phone and nothing is sent anywhere to decide what he does. The writing is authored ahead of time and shipped with the app.

MAKE HIM YOURS

• Twelve kinds — ghost, cat, dog, bunny, fox, bear, mouse, deer, bat, frog, dragon, axolotl
• Three sizes and four shades, from near-solid Bone to see-through Vapour to inverted Ink
• Give him a name, and he goes by it in his notification and on his home-screen widget
• A share card, so you can show him to someone

LOOKING AFTER HIM

• Feed him, play with him, let him nap when he is worn out
• A small daily allowance of tokens for playing and treats
• A streak for coming back, and he says so when you have been away

PERMISSIONS, AND WHY

• Display over other apps — this is the whole app. Without it he cannot leave the app's own screen.
• Notifications — Android requires an ongoing notification while he is floating. It carries his name, how he is doing, and a Stop button.
• Run at startup — so he comes back after a reboot, if he was floating before.
• Vibrate — the optional little buzz when he bolts.

SIGNING IN IS OPTIONAL

Ghostly works completely without an account. Sign in with Google only if you want to keep your ghost when you change phone — then his name, look, stats and token balance are saved to our own server, and to nothing and nobody else. You can delete that copy from inside the app at any time; the ghost on your phone stays either way.

No ads. No in-app purchases. No analytics, no trackers, no third-party advertising SDKs.
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

## Release notes (What's new, max 500)

### 1.2.0 (versionCode 5) — current

```
Twelve kinds to choose from, every one properly drawn — ears, antlers, horns,
wings and gills, not just a recoloured ghost. Each newcomer borrows the
reactions of whoever he takes after, so he arrives with lots to say.

Sending him out and calling him home is one unbroken movement now. He used
to blink as the box handed him over. He doesn't any more, and the flight
home runs at full frame rate.

Also: treats and gifts land anywhere in the box, and Settings can make him
tappable while he floats.
```

### 1.1.0 (versionCode 3/4) — the first pet release

```
Ghostly is a pet now, not just a floating ghost.

He gets hungry, tired and bored, and he has a box on the Home tab where you
feed him, play fetch and pet him. He reacts to the time of day, his mood and
your battery, picking from hundreds of written reactions — tumbling, bouncing,
pacing, peeking in from the side of the screen and saying small things above
his head.

Also: twelve kinds, three sizes, four shades, a name of his own, and an
optional Google sign-in so he survives a change of phone.
```

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

> **Re-record this before uploading.** The existing clip shows the old teal design and the old
> tap-to-flee behaviour. What the reviewer needs to see is unchanged in substance: the overlay
> being summoned from the app, floating over other apps, its ongoing notification, and being
> stopped from that notification.

### Data safety

Sign-in is optional, so declare the data as **optional** (collected only if the user signs in).

- Does your app collect or share any required user data? **Yes — collected, optional, not shared**
- **Personal info → Email address, Name, User IDs** — collected, optional, for *App functionality* and
  *Account management*. Not shared. User can request deletion.
- **App activity → Other user-generated content** (pet name/appearance) and **App interactions**
  (feed / pet / play events) — collected, optional, for *App functionality*. Not shared.
- **Device or other IDs** — collected, optional, for *App functionality* (restore on a second device).
- Encrypted in transit: **Yes** (HTTPS only). Deletion: **Yes** — in-app (Settings → Delete my
  account) and via `https://shashanking.github.io/ghostly-privacy/delete-account.html`.
- No analytics, no crash reporting, no ads SDK. Location, contacts, files, screen contents: **not collected**.
- Account deletion URL for the Console field: `https://shashanking.github.io/ghostly-privacy/delete-account.html`

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
