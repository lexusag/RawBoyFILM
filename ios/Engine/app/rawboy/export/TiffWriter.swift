import Foundation

/**
 * Writes an LZW-compressed RGB TIFF (8 or 16 bits per sample, little endian, horizontal differencing
 * predictor). Rows are compressed strip by strip as they arrive, after a gap for the header; the header,
 * which needs the compressed strip sizes, is written into that gap by finish(). With [metadata] the header
 * carries the photo's EXIF (ExifWriter's tags, in IFD0 and its Exif and GPS IFDs).
 */
final class TiffWriter {
    private let out: FileHandle
    private let width: Int
    private let height: Int
    private let bitsPerSample: Int
    private let metadata: PhotoMetadata?
    private let bytesPerRow: Int
    private let rowsPerStrip: Int
    private let stripCount: Int
    private var stripSizes: [Int] = []
    private var rowsWritten = 0
    private var strip: [UInt8]
    private var stripFill = 0
    private let lzw = LzwEncoder()
    /** Header size: the IFDs and their values; the strips follow. Doesn't depend on the strip sizes. */
    private var dataOffset = 0

    private static let stripTargetBytes = 256 * 1024

    init(_ out: FileHandle, width: Int, height: Int, bitsPerSample: Int, metadata: PhotoMetadata? = nil) throws {
        precondition(bitsPerSample == 8 || bitsPerSample == 16)
        self.out = out
        self.width = width
        self.height = height
        self.bitsPerSample = bitsPerSample
        self.metadata = metadata
        bytesPerRow = width * 3 * bitsPerSample / 8
        rowsPerStrip = (Self.stripTargetBytes / bytesPerRow).clamped(1, height)
        stripCount = (height + rowsPerStrip - 1) / rowsPerStrip
        strip = [UInt8](repeating: 0, count: rowsPerStrip * bytesPerRow)
        if bytesPerRow * height >= Int(Int32.max) { throw MetalError(message: "Image too large for classic TIFF") }
        dataOffset = layout(sizes: [Int](repeating: 0, count: stripCount)).size
        try out.seek(toOffset: UInt64(dataOffset))
    }

    private func layout(sizes: [Int]) -> (size: Int, write: (inout [UInt8], Int) -> Void) {
        var offsets = [Int]()
        var offset = dataOffset
        for size in sizes { offsets.append(offset); offset += size }
        typealias W = ExifWriter
        let own: [W.Entry] = [
            W.longEntry(256, width),                                         // ImageWidth
            W.longEntry(257, height),                                        // ImageLength
            W.numbers(258, W.short, [bitsPerSample, bitsPerSample, bitsPerSample]), // BitsPerSample
            W.shortEntry(259, 5),                                            // Compression: LZW
            W.shortEntry(262, 2),                                            // Photometric: RGB
            W.numbers(273, W.long, offsets),                                 // StripOffsets
            W.shortEntry(274, 1),                                            // Orientation: top-left
            W.shortEntry(277, 3),                                            // SamplesPerPixel
            W.longEntry(278, rowsPerStrip),                                  // RowsPerStrip
            W.numbers(279, W.long, sizes),                                   // StripByteCounts
            W.rationals(282, [(300, 1)]),                                    // XResolution
            W.rationals(283, [(300, 1)]),                                    // YResolution
            W.shortEntry(284, 1),                                            // PlanarConfiguration: chunky
            W.shortEntry(296, 2),                                            // ResolutionUnit: inch
            W.asciiEntry(305, "RawBoy"),                                     // Software
            W.shortEntry(317, 2),                                            // Predictor: horizontal differencing
        ]
        return W.tiffIfds(metadata, own: own)
    }

    private func header() -> [UInt8] {
        let tiff = layout(sizes: stripSizes)
        precondition(tiff.size == dataOffset)
        var b = [UInt8](repeating: 0, count: dataOffset)
        tiff.write(&b, 0)
        return b
    }

    /**
     * Appends [rows] rows of interleaved RGB 16-bit samples, converted to the file's depth and differenced
     * from the pixel to the left (the predictor: neighbours are similar, so LZW finds more repeats).
     */
    func writeRows(_ band: UnsafePointer<UInt16>, rows: Int) throws {
        let n = width * 3
        let sixteen = bitsPerSample == 16
        for r in 0..<rows {
            let row = band + r * n
            let start = stripFill
            stripFill = strip.withUnsafeMutableBufferPointer { s in
                var p = start
                if sixteen {
                    for i in 0..<n {
                        let d = row[i] &- (i >= 3 ? row[i - 3] : 0)
                        s[p] = UInt8(truncatingIfNeeded: d)
                        s[p + 1] = UInt8(truncatingIfNeeded: d >> 8)
                        p += 2
                    }
                } else {
                    var pr: UInt8 = 0, pg: UInt8 = 0, pb: UInt8 = 0
                    for i in stride(from: 0, to: n, by: 3) {
                        let r8 = Exporter.to8(row[i]), g8 = Exporter.to8(row[i + 1]), b8 = Exporter.to8(row[i + 2])
                        s[p] = r8 &- pr; s[p + 1] = g8 &- pg; s[p + 2] = b8 &- pb
                        p += 3
                        pr = r8; pg = g8; pb = b8
                    }
                }
                return p
            }
            rowsWritten += 1
            if stripFill == strip.count || rowsWritten == height { try flushStrip() }
        }
    }

    private func flushStrip() throws {
        let length = strip.withUnsafeBufferPointer { lzw.encode($0.baseAddress!, stripFill) }
        try out.write(contentsOf: UnsafeRawBufferPointer(start: lzw.output, count: length))
        stripSizes.append(length)
        stripFill = 0
    }

    func finish() throws {
        if rowsWritten != height { throw MetalError(message: "TIFF size mismatch: wrote \(rowsWritten) rows") }
        try out.seek(toOffset: 0)
        try out.write(contentsOf: header())
        try out.synchronize()
    }
}

/**
 * TIFF's LZW (MSB-first codes of 9 to 12 bits, "early change" as libtiff writes it), one strip at a time.
 * The string table is a hash of (prefix code, next byte) → code.
 */
final class LzwEncoder {
    private static let clear = 256
    private static let eoi = 257
    private static let firstCode = 258
    /** libtiff resets the table when the next free code would be 4094. */
    private static let tableFull = 4094
    private static let hashBits = 13

    private let keys = UnsafeMutablePointer<Int32>.allocate(capacity: 1 << LzwEncoder.hashBits)
    private let codes = UnsafeMutablePointer<UInt16>.allocate(capacity: 1 << LzwEncoder.hashBits)
    /** The last encode()'s result (its first bytes). */
    private(set) var output = UnsafeMutablePointer<UInt8>.allocate(capacity: 1)
    private var capacity = 1
    private var pos = 0
    private var bits: UInt32 = 0
    private var bitCount = 0

    deinit {
        keys.deallocate()
        codes.deallocate()
        output.deallocate()
    }

    /** Compresses [length] bytes from [src] into output; returns the compressed length. */
    func encode(_ src: UnsafePointer<UInt8>, _ length: Int) -> Int {
        // Worst case: one 12-bit code per input byte, plus clear codes.
        let bound = length + (length >> 1) + (length >> 10) + 16
        if capacity < bound {
            output.deallocate()
            output = UnsafeMutablePointer<UInt8>.allocate(capacity: bound)
            capacity = bound
        }
        let tableSize = 1 << Self.hashBits
        pos = 0
        bits = 0
        bitCount = 0
        var width = 9
        var next = Self.firstCode
        keys.update(repeating: -1, count: tableSize)
        put(Self.clear, width)
        if length == 0 {
            put(Self.eoi, width)
            return flush()
        }
        let mask = tableSize - 1
        var w = Int(src[0])
        for i in 1..<length {
            let k = Int(src[i])
            let key = Int32((w << 8) | k)
            var h = Int((UInt32(bitPattern: key) &* 0x9e37_79b1) >> UInt32(32 - Self.hashBits))
            var found = -1
            while keys[h] != -1 {
                if keys[h] == key { found = Int(codes[h]); break }
                h = (h + 1) & mask
            }
            if found >= 0 {
                w = found
                continue
            }
            put(w, width)
            keys[h] = key
            codes[h] = UInt16(next)
            next += 1
            if next == Self.tableFull {
                put(Self.clear, width)
                keys.update(repeating: -1, count: tableSize)
                next = Self.firstCode
                width = 9
            } else if next > (1 << width) - 1 {
                width += 1
            }
            w = k
        }
        put(w, width)
        // The decoder adds one more entry on reading that code, which may widen the EOI code.
        next += 1
        if next == Self.tableFull {
            put(Self.clear, width)
            width = 9
        } else if next > (1 << width) - 1 {
            width += 1
        }
        put(Self.eoi, width)
        return flush()
    }

    private func put(_ code: Int, _ width: Int) {
        bits = (bits << UInt32(width)) | UInt32(code)
        bitCount += width
        while bitCount >= 8 {
            bitCount -= 8
            output[pos] = UInt8(truncatingIfNeeded: bits >> UInt32(bitCount))
            pos += 1
        }
        bits &= (1 << UInt32(bitCount)) - 1
    }

    private func flush() -> Int {
        if bitCount > 0 {
            output[pos] = UInt8(truncatingIfNeeded: bits << UInt32(8 - bitCount))
            pos += 1
        }
        return pos
    }
}
