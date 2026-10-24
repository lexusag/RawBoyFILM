import XCTest
@testable import RawBoyEngine

/** Auto's batch order: the same cases as PhotoOrderTest.kt and photoorder.test.ts. */
final class PhotoOrderTests: XCTestCase {
    private let photos: [(date: String?, name: String)] = [
        ("2026:10:01 12:00:00", "IMG_10.CR3"),
        ("2026:10:01 12:00:05", "img_9.CR3"),
        (nil, "DSCF0001.RAF"),
        ("2026:09:30 08:00:00", "IMG_2.CR3"),
        (nil, "A.dng"),
    ]

    func testByDateThenUndatedByName() {
        let order = photos.sorted { PhotoOrder.inOrder(.date, $0, $1) }.map(\.name)
        XCTAssertEqual(order, ["IMG_2.CR3", "IMG_10.CR3", "img_9.CR3", "A.dng", "DSCF0001.RAF"])
    }

    func testByNameWithNumbersByValue() {
        let order = photos.sorted { PhotoOrder.inOrder(.name, $0, $1) }.map(\.name)
        XCTAssertEqual(order, ["A.dng", "DSCF0001.RAF", "IMG_2.CR3", "img_9.CR3", "IMG_10.CR3"])
    }

    func testValidDates() {
        XCTAssertEqual(PhotoOrder.validDate("2026:10:01 12:00:00\0"), "2026:10:01 12:00:00")
        XCTAssertNil(PhotoOrder.validDate("0000:00:00 00:00:00"))
        XCTAssertNil(PhotoOrder.validDate("    :  :     :  :  "))
        XCTAssertEqual(PhotoOrder.fileDate(Date(timeIntervalSince1970: 1792599059), timeZone: TimeZone(identifier: "UTC")!),
                       "2026:10:21 16:10:59")
        XCTAssertNil(PhotoOrder.fileDate(nil))
    }
}
