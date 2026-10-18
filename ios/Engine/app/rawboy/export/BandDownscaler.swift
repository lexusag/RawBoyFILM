import Foundation
import RawBoyJpeg

/**
 * Shrinks a developed image while it streams in, band by band, by exact area averaging (each output
 * pixel is the coverage-weighted mean of the input pixels under it): the right filter for downscaling,
 * no ringing, and the result doesn't depend on where the bands split. Only two output rows of state are
 * kept, so memory stays small at any size. The work is native (`band_ops.h`, via rawboy_jpeg.h).
 *
 * Samples are interleaved RGB, unsigned 16-bit, as Exporter renders them. Feed input rows top
 * to bottom with [push] (at most [maxRowsPerPush] per call), then call [finish]; finished output rows go to
 * [emit] (first output row, row count, samples; the buffer is reused).
 */
final class BandDownscaler {
    let outWidth: Int
    let outHeight: Int
    private let handle: OpaquePointer
    private var out: [UInt16]
    private var outY = 0
    private let emit: (Int, Int, UnsafePointer<UInt16>) throws -> Void

    init(inWidth: Int, inHeight: Int, outWidth: Int, outHeight: Int, maxRowsPerPush: Int,
         emit: @escaping (Int, Int, UnsafePointer<UInt16>) throws -> Void) {
        precondition((1...inWidth).contains(outWidth) && (1...inHeight).contains(outHeight), "only downscaling")
        self.outWidth = outWidth
        self.outHeight = outHeight
        self.emit = emit
        handle = rb_downscaler_create(Int32(inWidth), Int32(inHeight), Int32(outWidth), Int32(outHeight))
        out = [UInt16](repeating: 0,
                       count: Int(rb_downscaler_max_output_rows(handle, Int32(maxRowsPerPush))) * outWidth * 3)
    }

    deinit { rb_downscaler_destroy(handle) }

    /** Adds [rows] input rows from [band] (interleaved RGB, input width pixels per row). */
    func push(_ rows: Int, _ band: UnsafePointer<UInt16>) throws {
        let n = out.withUnsafeMutableBufferPointer { Int(rb_downscaler_push(handle, band, Int32(rows), $0.baseAddress!)) }
        try emitRows(n)
    }

    /** Emits the remaining rows; call once after the last [push]. */
    func finish() throws {
        let n = out.withUnsafeMutableBufferPointer { Int(rb_downscaler_finish(handle, $0.baseAddress!)) }
        try emitRows(n)
    }

    private func emitRows(_ rows: Int) throws {
        if rows == 0 { return }
        try out.withUnsafeBufferPointer { try emit(outY, rows, $0.baseAddress!) }
        outY += rows
    }

    /** Output size for a [width] x [height] image limited to [maxLongSide] (nil or larger = unchanged). */
    static func outputSize(_ width: Int, _ height: Int, maxLongSide: Int?) -> (Int, Int) {
        let long = max(width, height)
        guard let maxLongSide, maxLongSide > 0, long > maxLongSide else { return (width, height) }
        let scale = Double(maxLongSide) / Double(long)
        return (min(max(1, (Double(width) * scale).roundToInt()), width),
                min(max(1, (Double(height) * scale).roundToInt()), height))
    }
}
