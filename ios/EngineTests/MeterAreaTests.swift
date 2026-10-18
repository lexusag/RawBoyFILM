import XCTest
@testable import RawBoyEngine

/** Camera's metering boxes (the same cases as MeterAreaTest.kt). */
final class MeterAreaTests: XCTestCase {
    private func box(_ a: MeterArea) -> [Int] {
        let b = a.box(512, 384)
        return [b.x0, b.x1, b.y0, b.y1]
    }

    func testCentreIsTheMiddleHalf() {
        XCTAssertEqual(box(.centre(0.5)), [128, 384, 96, 288])
    }

    func testSpotIsASquareOfTheShortSideAroundTheTap() {
        XCTAssertEqual(box(.spot(x: 0.5, y: 0.25)), [226, 287, 66, 127])
    }

    func testSpotNearAnEdgeMovesInsideAtFullSize() {
        XCTAssertEqual(box(.spot(x: 0, y: 1)), [0, 61, 323, 384])
        XCTAssertEqual(box(.spot(x: 1.2, y: -0.1)), [451, 512, 0, 61])
    }

    func testSpotTurnsWithTheDevice() {
        let tap = MeterArea.spot(x: 0.2, y: 0.1)
        func at(_ degrees: Int) -> (Float, Float) {
            guard case let .spot(x, y, _) = tap.turned(degrees) else { return (-1, -1) }
            return (x, y)
        }
        XCTAssertEqual(tap.turned(0), tap)
        XCTAssertEqual(at(90).0, 0.9, accuracy: 1e-6)
        XCTAssertEqual(at(90).1, 0.2, accuracy: 1e-6)
        XCTAssertEqual(at(180).0, 0.8, accuracy: 1e-6)
        XCTAssertEqual(at(180).1, 0.9, accuracy: 1e-6)
        XCTAssertEqual(at(270).0, 0.1, accuracy: 1e-6)
        XCTAssertEqual(at(270).1, 0.8, accuracy: 1e-6)
    }
}
