import XCTest
@testable import RawBoyEngine

/** Point curves (Curves in Pro): the same cases as ToneCurveTest.kt and engine.test.ts "curves". */
final class ToneCurveTests: XCTestCase {
    private let s = [CurvePoint(0, 0), CurvePoint(0.25, 0.18), CurvePoint(0.75, 0.82), CurvePoint(1, 1)]

    func testPassesThroughItsPointsAndStaysMonotone() {
        for p in s { XCTAssertEqual(ToneCurves.evaluate(s, p.x), p.y, accuracy: 1e-6) }
        let v = ToneCurves.sample(s, count: 101)
        for i in 1..<v.count { XCTAssertGreaterThanOrEqual(v[i], v[i - 1]) }
        XCTAssertEqual(ToneCurves.evaluate(s, 0.1), 0.06528, accuracy: 1e-5)
        XCTAssertEqual(ToneCurves.evaluate(s, 0.5), 0.5, accuracy: 1e-6)
        XCTAssertEqual(ToneCurves.evaluate(s, 0.9), 0.93472, accuracy: 1e-5)
        let ends = [CurvePoint(0.1, 0.2), CurvePoint(0.9, 0.7)]
        XCTAssertEqual(ToneCurves.evaluate(ends, 0.05), 0.2, accuracy: 1e-6)
        XCTAssertEqual(ToneCurves.evaluate(ends, 0.95), 0.7, accuracy: 1e-6)
    }

    func testTableIsOffAtTheIdentityAndAppliesChannelsAfterRgb() throws {
        XCTAssertNil(ToneCurves.none.table())
        var c = ToneCurves()
        c.rgb = [CurvePoint(0, 0.2), CurvePoint(1, 1)]
        c.red = [CurvePoint(0, 1), CurvePoint(1, 0)]
        let t = try XCTUnwrap(c.table())
        XCTAssertEqual(t.count, ToneCurves.size * 3)
        XCTAssertEqual(t[0], 0.8, accuracy: 1e-6)  // red: inverted after the RGB lift
        XCTAssertEqual(t[1], 0.2, accuracy: 1e-6)
        XCTAssertEqual(t[(ToneCurves.size - 1) * 3], 0, accuracy: 1e-6)
        XCTAssertEqual(t[(ToneCurves.size - 1) * 3 + 2], 1, accuracy: 1e-6)
    }

    func testAddsMovesAndRemovesPointsWithinTheirNeighbours() throws {
        let added = try XCTUnwrap(ToneCurves.add(ToneCurves.identity, 0.5, 0.6))
        XCTAssertEqual(added.index, 1)
        XCTAssertEqual(added.points, [CurvePoint(0, 0), CurvePoint(0.5, 0.6), CurvePoint(1, 1)])
        XCTAssertNil(ToneCurves.add(added.points, 0.51, 0.3))  // too close
        XCTAssertEqual(ToneCurves.move(added.points, 1, 1.5, -1)[1], CurvePoint(1 - ToneCurves.minGap, 0))
        XCTAssertEqual(ToneCurves.move(added.points, 0, 0.7, 0.1)[0], CurvePoint(0.5 - ToneCurves.minGap, 0.1))
        XCTAssertEqual(ToneCurves.remove(added.points, 1), ToneCurves.identity)
        XCTAssertEqual(ToneCurves.remove(added.points, 0), added.points)
    }

    func testRoundTripsThroughPresetsInTheirOwnGroup() throws {
        var curves = ToneCurves()
        curves.rgb = s
        curves.blue = [CurvePoint(0, 0.1), CurvePoint(1, 0.9)]
        var edited = DevelopParams()
        edited.curves = curves
        edited.exposure = 1
        let preset = Presets.capture("Curve", edited, [.curves])
        XCTAssertEqual(preset.values, ["curves": .curves(curves)])
        let decoded = try XCTUnwrap(Presets.decode(Presets.encode([preset])).first)
        XCTAssertEqual(decoded.groups, [.curves])
        var target = DevelopParams()
        target.exposure = 2
        var expected = target
        expected.curves = curves
        XCTAssertEqual(Presets.apply(decoded, target), expected)
        let json = try JSONSerialization.jsonObject(
            with: Data(#"{"rgb":[[0,0],[0.5,2],[1,1]],"red":[[0.5,0],[0.2,1]],"green":"x"}"#.utf8))
        let parsed = try XCTUnwrap(ToneCurves.parse(json))
        XCTAssertEqual(parsed.rgb, [CurvePoint(0, 0), CurvePoint(0.5, 1), CurvePoint(1, 1)])
        XCTAssertEqual(parsed.red, ToneCurves.identity)  // not sorted
        XCTAssertEqual(parsed.green, ToneCurves.identity)
        XCTAssertNil(ToneCurves.parse([1, 2]))
    }
}
