import AppKit
import CoreText

// Composites a Play Store screenshot: the app's near-black ground, a serif headline, a dim
// subline, and the device capture inset with rounded corners and a hairline border.
let a = CommandLine.arguments
guard a.count >= 7 else { fputs("usage: shot <capture> <out> <head> <sub> <w> <h>\n", stderr); exit(2) }
let srcPath = a[1], outPath = a[2], headText = a[3], subText = a[4]
let W = CGFloat(Int(a[5])!), H = CGFloat(Int(a[6])!)
let fontDir = a.count > 7 ? a[7] : "."

func register(_ path: String) -> String? {
    guard let url = URL(string: "file://" + path) as CFURL?,
          let provider = CGDataProvider(url: url),
          let font = CGFont(provider) else { return nil }
    CTFontManagerRegisterGraphicsFont(font, nil)
    return font.postScriptName as String?
}
let serifName = register(fontDir + "/serif.ttf") ?? "Times New Roman"
let sansName  = register(fontDir + "/sans.ttf")  ?? "Helvetica Neue"

guard let src = NSImage(contentsOfFile: srcPath) else { fputs("no capture\n", stderr); exit(3) }
let srcSize = src.representations.first.map { CGSize(width: $0.pixelsWide, height: $0.pixelsHigh) } ?? src.size

let img = NSImage(size: NSSize(width: W, height: H))
img.lockFocus()
guard let ctx = NSGraphicsContext.current?.cgContext else { exit(4) }

// Ground
ctx.setFillColor(NSColor(calibratedRed: 0.031, green: 0.031, blue: 0.043, alpha: 1).cgColor)
ctx.fill(CGRect(x: 0, y: 0, width: W, height: H))

// A soft glow behind the headline, the same one the app uses behind the ghost
let glowR = W * 0.62
if let grad = CGGradient(colorsSpace: CGColorSpaceCreateDeviceRGB(),
        colors: [NSColor(calibratedRed: 0.47, green: 0.51, blue: 0.71, alpha: 0.17).cgColor,
                 NSColor(calibratedRed: 0.031, green: 0.031, blue: 0.043, alpha: 0).cgColor] as CFArray,
        locations: [0, 1]) {
    ctx.drawRadialGradient(grad, startCenter: CGPoint(x: W/2, y: H * 0.95), startRadius: 0,
                           endCenter: CGPoint(x: W/2, y: H * 0.95), endRadius: glowR, options: [])
}

func draw(_ text: String, font: NSFont, color: NSColor, centerY: CGFloat, maxW: CGFloat) -> CGFloat {
    let para = NSMutableParagraphStyle(); para.alignment = .center; para.lineHeightMultiple = 1.18
    let attrs: [NSAttributedString.Key: Any] = [.font: font, .foregroundColor: color, .paragraphStyle: para]
    let s = NSAttributedString(string: text, attributes: attrs)
    let bounds = s.boundingRect(with: NSSize(width: maxW, height: 10_000),
                                options: [.usesLineFragmentOrigin, .usesFontLeading])
    s.draw(with: NSRect(x: (W - maxW)/2, y: centerY - bounds.height, width: maxW, height: bounds.height),
           options: [.usesLineFragmentOrigin, .usesFontLeading])
    return bounds.height
}

let headFont = NSFont(name: serifName, size: W * 0.068) ?? NSFont.systemFont(ofSize: W * 0.068)
let subFont  = NSFont(name: sansName,  size: W * 0.030) ?? NSFont.systemFont(ofSize: W * 0.030)
let headH = draw(headText, font: headFont, color: NSColor(calibratedWhite: 0.949, alpha: 1),
                 centerY: H - H * 0.045, maxW: W * 0.88)
_ = draw(subText, font: subFont, color: NSColor(calibratedWhite: 0.549, alpha: 1),
         centerY: H - H * 0.045 - headH - H * 0.014, maxW: W * 0.80)

// The capture, inset and rounded
let iw = W * 0.78
let ih = iw * srcSize.height / srcSize.width
let ix = (W - iw) / 2
let iy = H * 0.030
let rect = CGRect(x: ix, y: iy, width: iw, height: min(ih, H * 0.80))
let path = CGPath(roundedRect: rect, cornerWidth: W * 0.022, cornerHeight: W * 0.022, transform: nil)
ctx.saveGState()
ctx.addPath(path); ctx.clip()
src.draw(in: rect)
ctx.restoreGState()
ctx.addPath(path)
ctx.setStrokeColor(NSColor(calibratedRed: 0.137, green: 0.137, blue: 0.18, alpha: 1).cgColor)
ctx.setLineWidth(2)
ctx.strokePath()

img.unlockFocus()
guard let tiff = img.tiffRepresentation, let rep = NSBitmapImageRep(data: tiff),
      let png = rep.representation(using: .png, properties: [:]) else { exit(5) }
try! png.write(to: URL(fileURLWithPath: outPath))
print("wrote \(outPath)")
