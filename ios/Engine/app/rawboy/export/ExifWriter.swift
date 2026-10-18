import Foundation

/**
 * Writes the main EXIF fields into exported JPEGs and TIFFs from the decoder's metadata: a port of the web build's
 * ExifWriter.ts (Android copies the same tags from the source file with ExifInterface), plus Orientation 1,
 * Software RawBoy and ColorSpace sRGB. A TIFF block: header, IFD0, Exif IFD and GPS IFD, little endian; JPEGs carry
 * it in one APP1 segment, TIFFs (tiffIfds) put the image's own tags in the same IFD0.
 * (ImageIO's lossless metadata copy was tried: it moved most fields to XMP and truncated EXIF rationals.)
 */
enum ExifWriter {
    static let ascii: UInt16 = 2
    static let short: UInt16 = 3
    static let long: UInt16 = 4
    static let rational: UInt16 = 5
    private static let srational: UInt16 = 10
    private static let undefined: UInt16 = 7

    private static func typeSize(_ type: UInt16) -> Int {
        switch type {
        case short: 2
        case long: 4
        case rational, srational: 8
        default: 1
        }
    }

    struct Entry {
        let tag: UInt16
        let type: UInt16
        let count: Int
        /** The value bytes, little endian. */
        var bytes: [UInt8]
    }

    private static func le16(_ v: UInt16) -> [UInt8] { [UInt8(v & 0xff), UInt8(v >> 8)] }
    private static func le32(_ v: UInt32) -> [UInt8] { (0..<4).map { UInt8((v >> (8 * UInt32($0))) & 0xff) } }

    static func asciiEntry(_ tag: UInt16, _ s: String) -> Entry {
        let bytes = Array(s.utf8) + [0]
        return Entry(tag: tag, type: ascii, count: bytes.count, bytes: bytes)
    }
    static func shortEntry(_ tag: UInt16, _ n: Int) -> Entry {
        Entry(tag: tag, type: short, count: 1, bytes: le16(UInt16(n)))
    }
    static func longEntry(_ tag: UInt16, _ n: Int) -> Entry {
        Entry(tag: tag, type: long, count: 1, bytes: le32(UInt32(n)))
    }
    /** SHORT or LONG values ([type]), e.g. BitsPerSample or StripOffsets. */
    static func numbers(_ tag: UInt16, _ type: UInt16, _ values: [Int]) -> Entry {
        Entry(tag: tag, type: type, count: values.count,
              bytes: values.flatMap { type == short ? le16(UInt16($0)) : le32(UInt32($0)) })
    }
    private static func undefinedEntry(_ tag: UInt16, _ bytes: [UInt8]) -> Entry {
        Entry(tag: tag, type: undefined, count: bytes.count, bytes: bytes)
    }
    static func rationals(_ tag: UInt16, _ values: [(UInt32, UInt32)]) -> Entry {
        Entry(tag: tag, type: rational, count: values.count, bytes: values.flatMap { le32($0.0) + le32($0.1) })
    }
    private static func srationalEntry(_ tag: UInt16, _ n: Int32, _ d: Int32) -> Entry {
        Entry(tag: tag, type: srational, count: 1, bytes: le32(UInt32(bitPattern: n)) + le32(UInt32(bitPattern: d)))
    }

    /** A positive number as a rational with [den] as denominator (rounded), clamped to 32 bits. */
    private static func rationalOf(_ x: Double, _ den: UInt32) -> (UInt32, UInt32) {
        (UInt32(min(Double(UInt32.max), (x * Double(den)).rounded())), den)
    }

    /** Exposure time as photographers write it: 1/250 exactly, longer times in tenths of a second. */
    private static func exposureRational(_ t: Double) -> (UInt32, UInt32) {
        if t < 1 {
            let inv = 1 / t
            if abs(inv - inv.rounded()) < 0.01 * inv { return (1, UInt32(inv.rounded())) }
            return rationalOf(t, 1_000_000)
        }
        return rationalOf(t, 10)
    }

    private static func known(_ x: Double) -> Bool { x.isFinite && x > 0 }
    private static func text(_ s: String) -> String { s.trimmingCharacters(in: .whitespaces) }
    private static func matches(_ s: String, _ pattern: String) -> Bool {
        s.range(of: pattern, options: .regularExpression) != nil
    }

    // Tags (TIFF 6 / EXIF 2.32 / GPS).
    private enum T {
        static let make: UInt16 = 0x010f, model: UInt16 = 0x0110, orientation: UInt16 = 0x0112
        static let software: UInt16 = 0x0131, artist: UInt16 = 0x013b, copyright: UInt16 = 0x8298
        static let exifIfd: UInt16 = 0x8769, gpsIfd: UInt16 = 0x8825
        static let exposureTime: UInt16 = 0x829a, fNumber: UInt16 = 0x829d, iso: UInt16 = 0x8827
        static let exifVersion: UInt16 = 0x9000, dateTimeOriginal: UInt16 = 0x9003, offsetTimeOriginal: UInt16 = 0x9011
        static let exposureBias: UInt16 = 0x9204, focalLength: UInt16 = 0x920a, subSecTimeOriginal: UInt16 = 0x9291
        static let colorSpace: UInt16 = 0xa001, focalLength35mm: UInt16 = 0xa405
        static let lensMake: UInt16 = 0xa433, lensModel: UInt16 = 0xa434
        static let gpsVersion: UInt16 = 0, gpsLatRef: UInt16 = 1, gpsLat: UInt16 = 2, gpsLonRef: UInt16 = 3
        static let gpsLon: UInt16 = 4, gpsAltRef: UInt16 = 5, gpsAlt: UInt16 = 6
    }

    private static func dms(_ v: [Double]) -> [(UInt32, UInt32)] {
        [rationalOf(v[0], 1), rationalOf(v[1], 1), rationalOf(v[2], 10000)]
    }

    private static func ifds(_ m: PhotoMetadata) -> (ifd0: [Entry], exif: [Entry], gps: [Entry]) {
        var ifd0: [Entry] = []
        if !text(m.make).isEmpty { ifd0.append(asciiEntry(T.make, text(m.make))) }
        if !text(m.model).isEmpty { ifd0.append(asciiEntry(T.model, text(m.model))) }
        ifd0.append(shortEntry(T.orientation, 1))  // the exported pixels are already upright
        ifd0.append(asciiEntry(T.software, "RawBoy"))
        if !text(m.artist).isEmpty { ifd0.append(asciiEntry(T.artist, text(m.artist))) }
        if !text(m.copyright).isEmpty { ifd0.append(asciiEntry(T.copyright, text(m.copyright))) }

        var exif: [Entry] = []
        if known(m.exposureTime) { exif.append(rationals(T.exposureTime, [exposureRational(m.exposureTime)])) }
        if known(m.fNumber) { exif.append(rationals(T.fNumber, [rationalOf(m.fNumber, 10)])) }
        if m.iso > 0 { exif.append(shortEntry(T.iso, min(65535, Int(m.iso.rounded())))) }
        exif.append(undefinedEntry(T.exifVersion, [0x30, 0x32, 0x33, 0x32]))  // "0232"
        if matches(m.dateTimeOriginal, #"^\d{4}:\d\d:\d\d \d\d:\d\d:\d\d$"#) {
            exif.append(asciiEntry(T.dateTimeOriginal, m.dateTimeOriginal))
        }
        if matches(m.offsetTimeOriginal, #"^[+-]\d\d:\d\d$"#) {
            exif.append(asciiEntry(T.offsetTimeOriginal, m.offsetTimeOriginal))
        }
        if m.exposureBias.isFinite {
            exif.append(srationalEntry(T.exposureBias, Int32((m.exposureBias * 100).rounded()), 100))
        }
        if known(m.focalLength) { exif.append(rationals(T.focalLength, [rationalOf(m.focalLength, 100)])) }
        if matches(m.subSecTimeOriginal, #"^\d+$"#) { exif.append(asciiEntry(T.subSecTimeOriginal, m.subSecTimeOriginal)) }
        exif.append(shortEntry(T.colorSpace, 1))  // sRGB
        if known(m.focalLength35mm) {
            exif.append(shortEntry(T.focalLength35mm, min(65535, Int(m.focalLength35mm.rounded()))))
        }
        if !text(m.lensMake).isEmpty { exif.append(asciiEntry(T.lensMake, text(m.lensMake))) }
        if !text(m.lensModel).isEmpty { exif.append(asciiEntry(T.lensModel, text(m.lensModel))) }

        var gps: [Entry] = []
        if let g = m.gps {
            gps.append(undefinedEntry(T.gpsVersion, [2, 3, 0, 0]))
            gps.append(asciiEntry(T.gpsLatRef, g.latitudeRef))
            gps.append(rationals(T.gpsLat, dms(g.latitude)))
            gps.append(asciiEntry(T.gpsLonRef, g.longitudeRef))
            gps.append(rationals(T.gpsLon, dms(g.longitude)))
            if g.altitude.isFinite {
                gps.append(undefinedEntry(T.gpsAltRef, [g.belowSeaLevel ? 1 : 0]))
                gps.append(rationals(T.gpsAlt, [rationalOf(abs(g.altitude), 100)]))
            }
        }
        return (ifd0, exif, gps)
    }

    private static func valueSize(_ e: Entry) -> Int { typeSize(e.type) * e.count }

    /** Bytes an IFD takes: count, entries, next-IFD offset, then values that don't fit in 4 bytes (word aligned). */
    private static func ifdSize(_ entries: [Entry]) -> Int {
        2 + entries.count * 12 + 4 + entries.reduce(0) { $0 + (valueSize($1) > 4 ? (valueSize($1) + 1) & ~1 : 0) }
    }

    /** Writes [entries] (sorted by tag) at [offset] of the TIFF block [out]. */
    private static func writeIfd(_ out: inout [UInt8], _ base: Int, _ offset: Int, _ entries: [Entry]) {
        let sorted = entries.sorted { $0.tag < $1.tag }
        func put(_ bytes: [UInt8], _ at: Int) { for (k, b) in bytes.enumerated() { out[at + k] = b } }
        var p = base + offset
        var data = offset + 2 + sorted.count * 12 + 4
        put(le16(UInt16(sorted.count)), p)
        p += 2
        for e in sorted {
            put(le16(e.tag), p)
            put(le16(e.type), p + 2)
            put(le32(UInt32(e.count)), p + 4)
            if valueSize(e) <= 4 {
                put(e.bytes, p + 8)
            } else {
                put(le32(UInt32(data)), p + 8)
                put(e.bytes, base + data)
                data += (valueSize(e) + 1) & ~1
            }
            p += 12
        }
        put(le32(0), p)  // no next IFD
    }

    /**
     * Lays out a TIFF block: header, IFD0 with [own] entries plus [m]'s (own tags win), then the Exif and GPS IFDs.
     * Returns its size (which doesn't depend on the entries' values, only on their types and counts) and a writer
     * that fills the block starting at [base] of its buffer.
     */
    static func tiffIfds(_ m: PhotoMetadata?, own: [Entry]) -> (size: Int, write: (inout [UInt8], Int) -> Void) {
        let parts = m.map { ifds($0) }
        let meta = parts?.ifd0 ?? [], exif = parts?.exif ?? [], gps = parts?.gps ?? []
        var ifd0 = own + meta.filter { e in !own.contains { $0.tag == e.tag } }
        // Pointers to the sub-IFDs are LONG entries in IFD0; their values are set once offsets are known.
        if !exif.isEmpty { ifd0.append(longEntry(T.exifIfd, 0)) }
        if !gps.isEmpty { ifd0.append(longEntry(T.gpsIfd, 0)) }
        let exifOffset = 8 + ifdSize(ifd0)
        let gpsOffset = exifOffset + (exif.isEmpty ? 0 : ifdSize(exif))
        for i in ifd0.indices {
            if ifd0[i].tag == T.exifIfd { ifd0[i].bytes = le32(UInt32(exifOffset)) }
            if ifd0[i].tag == T.gpsIfd { ifd0[i].bytes = le32(UInt32(gpsOffset)) }
        }
        let size = gpsOffset + (gps.isEmpty ? 0 : ifdSize(gps))
        let entries = ifd0
        return (size, { out, base in
            out[base] = 0x49; out[base + 1] = 0x49  // "II": little endian
            for (k, b) in le16(42).enumerated() { out[base + 2 + k] = b }
            for (k, b) in le32(8).enumerated() { out[base + 4 + k] = b }
            writeIfd(&out, base, 8, entries)
            if !exif.isEmpty { writeIfd(&out, base, exifOffset, exif) }
            if !gps.isEmpty { writeIfd(&out, base, gpsOffset, gps) }
        })
    }

    /** The complete APP1 segment (marker, length, "Exif\0\0", TIFF block). */
    static func exifSegment(_ m: PhotoMetadata) -> [UInt8]? {
        let tiff = tiffIfds(m, own: [])
        let header = 10  // FFE1, length, "Exif\0\0"
        if tiff.size + 8 > 0xffff { return nil }
        var out = [UInt8](repeating: 0, count: header + tiff.size)
        out[0] = 0xff; out[1] = 0xe1
        let length = UInt16(tiff.size + 8)  // counts itself and "Exif\0\0"
        out[2] = UInt8(length >> 8); out[3] = UInt8(length & 0xff)
        for (k, b) in [0x45, 0x78, 0x69, 0x66, 0, 0].enumerated() { out[4 + k] = UInt8(b) }
        tiff.write(&out, header)
        return out
    }

    /** Inserts [app1] into a JPEG after SOI and any APP0 (JFIF) segment, as EXIF readers expect. */
    static func insertExif(_ jpeg: Data, _ app1: [UInt8]) -> Data? {
        guard jpeg.count > 6, jpeg[0] == 0xff, jpeg[1] == 0xd8 else { return nil }
        var at = 2
        if jpeg[2] == 0xff && jpeg[3] == 0xe0 { at = 4 + (Int(jpeg[4]) << 8 | Int(jpeg[5])) }
        var out = Data(capacity: jpeg.count + app1.count)
        out.append(jpeg.prefix(at))
        out.append(contentsOf: app1)
        out.append(jpeg.suffix(from: at))
        return out
    }

    /** Adds the EXIF segment to the JPEG at [url]. Best effort: the image is already written. */
    static func addExif(to url: URL, metadata: PhotoMetadata) {
        guard let segment = exifSegment(metadata), let jpeg = try? Data(contentsOf: url),
              let out = insertExif(jpeg, segment) else { return }
        try? out.write(to: url, options: .atomic)
    }
}
