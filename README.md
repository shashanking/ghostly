# Ghostly

A little ghost that floats on top of everything on Android — home screen, other apps, no app at
all. He is small, see-through, and never quite still: he drifts slowly around the screen, bobbing
and swaying, hem rippling. Tap him — or just near him — and he glides off in a random direction,
bouncing gently off the edges before settling back into his wander.

## How it works

| Piece | What it does |
| --- | --- |
| `GhostOverlayService` | A foreground service that adds the ghost to a `TYPE_APPLICATION_OVERLAY` window and runs its motion loop off `Choreographer`. |
| `GhostView` | Draws the body, the two eyes (they track where he is going, blink, go wide when spooked), the mouth and the glow. |
| `GhostPlayground` | The same behaviour miniaturised inside the app's home screen, so you can poke him before letting him out. |
| `BootReceiver` | Puts him back after a reboot or an app update if he was floating before. |
| `Watchdog` / `WatchdogReceiver` | A quarter-hourly alarm that revives the overlay if the phone killed it. |
| `Recall` | Restart-or-notify: when Android refuses a background service start, leaves a one-tap notification instead. |
| `Prefs` | Enabled flag, last position, size, haptics, click-through mode. |

### Touch: two modes, because Android forces a choice

Measured on Android 15, an overlay window can have exactly one of these, never both:

| | Blocks what's underneath | Knows *where* you tapped |
| --- | --- | --- |
| Touchable window | yes, wherever he floats | yes |
| `FLAG_NOT_TOUCHABLE` + `FLAG_WATCH_OUTSIDE_TOUCH` | no, ever | no — the platform zeroes the coordinates (`rawX=0, rawY=0`) |

So there is a switch in the app:

- **Taps pass through him** (default) — the intangible mode. He never blocks a button or a keyboard
  key. He is still told that a tap happened, just not where, so he reacts to any tap. To keep that
  from being maddening while you type, he **habituates**: a burst of taps within 4s earns a longer
  cooldown (up to 2.6s) and a much smaller flinch than a single deliberate poke.
- **Solid** — the window is touchable, with a 22dp halo of personal space around him. Now he can be
  poked precisely, dragged, and long-pressed to open the app, at the cost of swallowing taps where
  he sits.

### Eyes

Each eye is a pale sclera with a pupil that travels inside it, so his gaze is readable from across
the room. Gaze targets are chosen in screen coordinates by the service and handed to the view as a
direction:

- gliding: he watches where he is going;
- a flurry of taps: almost always the keyboard, so he watches the bottom of the screen;
- a lone tap: a glance in some direction, as though he heard something;
- otherwise: slow scanning around the screen, holding each look for 1.4–4s.

Shifts are saccadic — the eyes flick to a new target and settle, rather than gliding — plus blinks
and a wider-eyed "notice" whenever something happens.

### Sideloading and the permission block

Android refuses "display over other apps" to apps installed from outside a store, with an unhelpful
"App was denied access" dialog. The app detects it cannot draw overlays and shows the way out —
App info → ⋮ → **Allow restricted settings** — with a button that opens App info directly.

### Staying alive

Two things repeatedly go wrong with a long-running overlay, and both are handled here:

- **The frame loop can die at display-off.** Each `Choreographer` frame is what schedules the next
  one, so if the platform drops the pending callback when the screen turns off, the ghost freezes
  for good — reported on a Galaxy S24, where he stayed frozen even after unlocking. The service now
  stops the loop on `ACTION_SCREEN_OFF`, restarts it on `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT`,
  and a 2-second watchdog restarts it anyway if frames stop arriving while the screen is on.
- **Phones kill long-running services.** Sticky restart covers most of it; a quarter-hourly inexact
  alarm covers the rest; and if Android refuses the background start (it does unless the app is
  exempt from battery optimisation), the user gets a notification to tap rather than silence. The
  app's home screen also points at the battery settings where "put app to sleep" lives.

Cost matters for the same reason — a service that burns CPU is a service that gets killed. The loop
is capped at 30fps (measured at roughly half the CPU of 45fps, and indistinguishable at this drift
speed), does nothing at all while the screen is off, builds its gradients once rather than per
frame, and the glow is a plain radial gradient rather than `setShadowLayer`, which would force the
whole view through software rendering on every frame.

### Motion

Speed always settles back toward a slow drift (~18dp/s) rather than to zero, so he is permanently
adrift. A poke adds a one-off impulse of 320–580dp/s on top, which bleeds off over a few seconds.
His heading wanders on a pair of out-of-phase sines and reflects off the screen edges.

## Gestures

| Gesture | Result |
| --- | --- |
| Tap anywhere (intangible mode) | He drifts away; the tap still reaches the app underneath, even a tap right on top of him |
| Keep tapping (intangible mode) | He gets used to it — smaller flinches, longer cooldown |
| Tap him or within ~22dp (solid mode) | Glides away from your finger, random spread |
| Tap elsewhere (solid mode) | Ignored, and the tap reaches the app underneath |
| Drag | He follows your finger, and keeps the momentum when you let go |
| Long-press (~0.5s) | Opens this app |
| Notification → Stop | Sends him away |

## Build and run

```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open Ghostly and tap **Let him float**. The first tap sends you to
Settings → Display over other apps, where Ghostly needs to be switched on; come back and tap again.

To skip the settings trip on an emulator or a test device:

```bash
adb shell appops set com.ghostly SYSTEM_ALERT_WINDOW allow
adb shell pm grant com.ghostly android.permission.POST_NOTIFICATIONS
```

## Notes

- Sizes are 22 / 32 / 44dp (he used to be 76 / 104 / 136dp; an old stored preference is folded back
  onto the new scale automatically).
- The body is drawn at ~74% opacity with a violet outline — without the outline a see-through white
  ghost vanishes against a white screen.
- minSdk 26, targetSdk 35, no third-party dependencies — plain Kotlin and the platform SDK.
- The foreground service is declared `specialUse`, which is the correct type for a persistent
  overlay. Publishing on Play with this type requires a short written justification in the console.
- Android only. iOS has no equivalent to `SYSTEM_ALERT_WINDOW`; an app cannot draw over other apps
  there, so this concept has no iOS counterpart.
