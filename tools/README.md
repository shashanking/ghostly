# Play asset generators

Two small AppKit programs that composite the Play Store artwork, so a listing refresh does not
mean redoing layout by hand. They use the app's own fonts, so the artwork and the app match.

```bash
swiftc -O -o /tmp/shotgen tools/play-screenshot.swift
swiftc -O -o /tmp/featgen tools/play-feature.swift

# <capture> <out> <headline> <subline> <width> <height> <font dir>
/tmp/shotgen capture.png out.png "This box is his" "Feed him, play with him." 1080 2160 fonts/
/tmp/featgen play/graphics/icon-512.png feature.png fonts/
```

The font directory needs `serif.ttf` and `sans.ttf` — copy them from
`app/src/main/res/font/instrument_serif.ttf` and `space_grotesk.ttf`.

Both draw at the display's backing scale, so on a Retina Mac the output is 2× the size asked for.
Bring it back down afterwards, which also keeps the files inside Play's 8 MB limit:

```bash
sips -z 2160 1080 out.png --out out.png
```

Captures themselves come from a device: `adb exec-out screencap -p > shot.png`. Use an emulator
rather than a personal phone — a real home screen puts wallpaper, app folders and a weather
location into a public store listing.
