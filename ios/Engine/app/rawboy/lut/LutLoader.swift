import CoreGraphics
import Foundation
import ImageIO

public struct LutError: LocalizedError {
    public let message: String
    public var errorDescription: String? { message }

    public init(message: String) { self.message = message }
}

/** Parses .cube and HaldCLUT .png files into LutData, resampled to at most 64^3. */
public enum LutLoader {
    /** 64^3 is plenty for smooth film looks and keeps the GPU texture at ~3 MB. */
    private static let maxSize = 64
    private static let lock = NSLock()

    /** Serialized: a 256^3 HaldCLUT parse briefly needs tens of MB, so parallel loads would add up. */
    public static func load(_ id: String) throws -> LutData {
        lock.lock()
        defer { lock.unlock() }
        let data = try LutRepository.open(id)
        // Sniff the format: names don't always carry an extension.
        let isPng = data.count >= 4 && data.prefix(4).elementsEqual([0x89, 0x50, 0x4E, 0x47])
        return isPng ? try parseHald(id, data) : try parseCube(id, data)
    }

    /** Adobe/Resolve .cube: LUT_3D_SIZE N, then N^3 "r g b" lines with red varying fastest. */
    static func parseCube(_ id: String, _ data: Data) throws -> LutData {
        guard let text = String(data: data, encoding: .utf8) ?? String(data: data, encoding: .isoLatin1) else {
            throw LutError(message: "Cannot read .cube file")
        }
        var size = 0
        var values: [Float]?
        var count = 0
        for raw in text.split(whereSeparator: \.isNewline) {
            let line = raw.trimmingCharacters(in: .whitespaces)
            guard let first = line.first, first != "#" else { continue }
            if first.isNumber || first == "-" || first == "." {
                guard values != nil else { throw LutError(message: "LUT data before LUT_3D_SIZE") }
                let parts = line.split(whereSeparator: { $0 == " " || $0 == "\t" })
                guard parts.count >= 3, count + 3 <= values!.count,
                      let r = Float(parts[0]), let g = Float(parts[1]), let b = Float(parts[2])
                else { throw LutError(message: "Malformed .cube data") }
                values![count] = r
                values![count + 1] = g
                values![count + 2] = b
                count += 3
            } else if line.hasPrefix("LUT_3D_SIZE") {
                guard let n = Int(line.dropFirst("LUT_3D_SIZE".count).trimmingCharacters(in: .whitespaces)),
                      (2...256).contains(n) else { throw LutError(message: "Unsupported LUT size") }
                size = n
                values = [Float](repeating: 0, count: n * n * n * 3)
            } else if line.hasPrefix("LUT_1D_SIZE") {
                throw LutError(message: "1D .cube LUTs are not supported")
            }
            // TITLE, DOMAIN_MIN/MAX (assumed 0..1) and other keywords are ignored.
        }
        guard let v = values else { throw LutError(message: "Missing LUT_3D_SIZE") }
        if count != v.count { throw LutError(message: "Expected \(v.count / 3) entries, found \(count / 3)") }
        return finish(id, size) { i, c in v[i * 3 + c] }
    }

    /**
     * HaldCLUT: a square image of level^3 pixels per side holding a level^2 cube.
     * Pixel index = r + g*N + b*N^2, row-major.
     */
    static func parseHald(_ id: String, _ data: Data) throws -> LutData {
        guard let source = CGImageSourceCreateWithData(data as CFData, nil),
              let image = CGImageSourceCreateImageAtIndex(source, 0, nil)
        else { throw LutError(message: "Cannot decode HaldCLUT") }
        let w = image.width
        let level = Int(cbrt(Double(w)).rounded())
        if image.height != w || level * level * level != w {
            throw LutError(message: "Not a HaldCLUT image (\(w)x\(image.height))")
        }
        let n = level * level
        // Draw into plain RGBA8 (no colour management: the values are LUT data, not a picture).
        var pixels = [UInt8](repeating: 0, count: w * w * 4)
        let drawn = pixels.withUnsafeMutableBytes { buffer -> Bool in
            guard let ctx = CGContext(
                data: buffer.baseAddress, width: w, height: w, bitsPerComponent: 8, bytesPerRow: w * 4,
                space: image.colorSpace ?? CGColorSpaceCreateDeviceRGB(),
                bitmapInfo: CGImageAlphaInfo.noneSkipLast.rawValue)
            else { return false }
            ctx.draw(image, in: CGRect(x: 0, y: 0, width: w, height: w))
            return true
        }
        if !drawn { throw LutError(message: "Cannot decode HaldCLUT") }
        return finish(id, n) { i, c in Float(pixels[i * 4 + c]) / 255 }
    }

    /** [sample] (node index, channel) reads the source cube, red fastest. */
    private static func finish(_ id: String, _ size: Int, _ sample: (Int, Int) -> Float) -> LutData {
        let outSize = min(size, maxSize)
        let out = outSize == size
            ? (0..<(size * size * size * 3)).map { sample($0 / 3, $0 % 3) }
            : resample(sample, size, outSize)
        return LutData(id: id, size: outSize, data: out)
    }

    /** Trilinear resampling of a size^3 cube onto an outSize^3 grid spanning the same 0..1 domain. */
    private static func resample(_ sample: (Int, Int) -> Float, _ size: Int, _ outSize: Int) -> [Float] {
        var out = [Float](repeating: 0, count: outSize * outSize * outSize * 3)
        let scale = Float(size - 1) / Float(outSize - 1)
        func idx(_ r: Int, _ g: Int, _ b: Int) -> Int { (b * size + g) * size + r }
        var o = 0
        for b in 0..<outSize {
            let fb = Float(b) * scale
            let b0 = min(Int(fb), size - 2)
            let tb = fb - Float(b0)
            for g in 0..<outSize {
                let fg = Float(g) * scale
                let g0 = min(Int(fg), size - 2)
                let tg = fg - Float(g0)
                for r in 0..<outSize {
                    let fr = Float(r) * scale
                    let r0 = min(Int(fr), size - 2)
                    let tr = fr - Float(r0)
                    for c in 0..<3 {
                        func at(_ dr: Int, _ dg: Int, _ db: Int) -> Float { sample(idx(r0 + dr, g0 + dg, b0 + db), c) }
                        let c00 = at(0, 0, 0) + (at(1, 0, 0) - at(0, 0, 0)) * tr
                        let c10 = at(0, 1, 0) + (at(1, 1, 0) - at(0, 1, 0)) * tr
                        let c01 = at(0, 0, 1) + (at(1, 0, 1) - at(0, 0, 1)) * tr
                        let c11 = at(0, 1, 1) + (at(1, 1, 1) - at(0, 1, 1)) * tr
                        let c0 = c00 + (c10 - c00) * tg
                        let c1 = c01 + (c11 - c01) * tg
                        out[o] = c0 + (c1 - c0) * tb
                        o += 1
                    }
                }
            }
        }
        return out
    }
}
