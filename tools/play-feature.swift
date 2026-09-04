import AppKit
import CoreText

// The 1024x500 feature graphic: the app's ground, its serif wordmark, and the ghost himself.
let a = CommandLine.arguments
let iconPath = a[1], outPath = a[2], fontDir = a[3]
let W: CGFloat = 1024, H: CGFloat = 500

func register(_ path: String) -> String? {
    guard let url = URL(string: "file://" + path) as CFURL?,
          let p = CGDataProvider(url: url), let f = CGFont(p) else { return nil }
    CTFontManagerRegisterGraphicsFont(f, nil)
    return f.postScriptName as String?
}
let serifName = register(fontDir + "/serif.ttf") ?? "Times New Roman"
let sansName  = register(fontDir + "/sans.ttf")  ?? "Helvetica Neue"

let img = NSImage(size: NSSize(width: W, height: H))
img.lockFocus()
let ctx = NSGraphicsContext.current!.cgContext

ctx.setFillColor(NSColor(calibratedRed: 0.031, green: 0.031, blue: 0.043, alpha: 1).cgColor)
ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))

// The same wash he carries behind him in the app, sitting behind the ghost
if let grad = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
        colors: [NSColor(calibratedRed: 0.55, green: 0.59, blue: 0.80, alpha: 0.22).cgColor,
                 NSColor(calibratedRed: 0.031, green: 0.031, blue: 0.043, alpha: 0).cgColor] as CFArray,
        locations: [0, 1]) {
    ctx.drawRadialGradient(grad, startCenter: CGPoint(x: W * 0.775, y: H * 0.52), startRadius: 0,
                           endCenter: CGPoint(x: W * 0.775, y: H * 0.52), endRadius: 300, options: [])
}

func text(_ s: String, _ font: NSFont, _ color: NSColor, x: CGFloat, y: CGFloat, maxW: CGFloat) -> CGFloat {
    let para = NSMutableParagraphStyle(); para.alignment = .left; para.lineHeightMultiple = 1.25
    let at = NSAttributedString(string: s, attributes: [.font: font, .foregroundColor: color, .paragraphStyle: para])
    let b = at.boundingRect(with: NSSize(width: maxW, height: 999), options: [.usesLineFragmentOrigin, .usesFontLeading])
    at.draw(with: NSRect(x: x, y: y - b.height, width: maxW, height: b.height),
            options: [.usesLineFragmentOrigin, .usesFontLeading])
    return b.height
}

let left: CGFloat = 74
_ = text("Ghostly", NSFont(name: serifName, size: 104)!, NSColor(calibratedWhite: 0.949, alpha: 1),
         x: left, y: H * 0.72, maxW: 560)
_ = text("A little ghost who lives on your screen.\nFeed him, play with him, and let him drift over everything else.",
         NSFont(name: sansName, size: 24)!, NSColor(calibratedWhite: 0.549, alpha: 1),
         x: left, y: H * 0.40, maxW: 520)

if let icon = NSImage(contentsOfFile: iconPath) {
    let size: CGFloat = 330
    let r = CGRect(x: W - size - 84, y: (H - size) / 2, width: size, height: size)
    // The icon art sits on its own dark square. Clipped to a disc and screen-blended, that square
    // disappears into our ground and what is left is the ghost with his own glow around him.
    ctx.saveGState()
    ctx.addEllipse(in: r.insetBy(dx: 6, dy: 6))
    ctx.clip()
    ctx.setBlendMode(.screen)
    icon.draw(in: r)
    ctx.restoreGState()
}

img.unlockFocus()
let tiff = img.tiffRepresentation!
let rep = NSBitmapImageRep(data: tiff)!
try! rep.representation(using: .png, properties: [:])!.write(to: URL(fileURLWithPath: outPath))
print("wrote \(outPath)")
