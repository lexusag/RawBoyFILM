import XCTest
@testable import RawBoyEngine

/** Lens presets: the same cases as PresetsTest.kt / LookApplierTest.kt and engine.test.ts "lens presets". */
final class LensPresetTests: XCTestCase {
    private func params(_ change: (inout DevelopParams) -> Void) -> DevelopParams {
        var p = DevelopParams()
        change(&p)
        return p
    }

    func testRoundTripWithTheirLensAndMatchIt() throws {
        let lens = Presets.captureLens(params { $0.lensDistortion = -12; $0.vignetteAmount = 40 }, lens: "XF23mmF2 R WR")
        XCTAssertTrue(lens.isLensPreset)
        XCTAssertEqual(lens.name, "XF23mmF2 R WR")
        let decoded = try XCTUnwrap(Presets.decode(Presets.encode([lens])).first)
        XCTAssertEqual(decoded.lens, "XF23mmF2 R WR")
        XCTAssertEqual(Set(decoded.values.keys), ["lensProfile", "lensDistortion", "vignetteAmount", "vignetteMidpoint"])
        let look = Presets.capture("Look", DevelopParams(), [.light, .lens], lens: "XF23mmF2 R WR")
        XCTAssertFalse(look.isLensPreset)
        XCTAssertEqual(Presets.lensPresetFor([look, decoded], lens: " xf23mmf2 r wr"), decoded)
        XCTAssertNil(Presets.lensPresetFor([look, decoded], lens: "XF35mmF2 R WR"))
        // A lens-only preset naming no lens (a manual lens, or saved before the lens was recorded) is only picked by hand.
        let manual = try XCTUnwrap(Presets.decode(
            #"{"format":"rawboy-preset","version":1,"name":"Helios 44-2","groups":["lens"],"values":{"lensDistortion":5}}"#).first)
        XCTAssertNil(manual.lens)
        XCTAssertNil(Presets.lensPresetFor([manual], lens: "XF35mmF2 R WR"))
        XCTAssertNil(Presets.lensPresetFor([manual], lens: ""))
        XCTAssertEqual(Presets.lensPresetFor([manual, decoded], lens: "XF23mmF2 R WR"), decoded)
    }

    func testPickedByLensByIdOrNotAtAll() {
        let auto = Presets.captureLens(params { $0.lensDistortion = 3 }, lens: "XF23mmF2 R WR")
        let manual = Presets.captureLens(params { $0.lensDistortion = -8 }, lens: "", name: "Helios 44-2")
        XCTAssertEqual(manual.name, "Helios 44-2")
        XCTAssertNil(manual.lens)
        let list = [auto, manual]
        XCTAssertEqual(Presets.chosenLensPreset(list, choice: nil, lens: "XF23mmF2 R WR"), auto)
        XCTAssertNil(Presets.chosenLensPreset(list, choice: nil, lens: ""))
        XCTAssertEqual(Presets.chosenLensPreset(list, choice: manual.id, lens: "XF23mmF2 R WR"), manual)
        XCTAssertNil(Presets.chosenLensPreset(list, choice: Presets.noLensPreset, lens: "XF23mmF2 R WR"))
        XCTAssertNil(Presets.chosenLensPreset([auto], choice: manual.id, lens: ""))
    }

    func testNamedManualLensPresetsLiveSideBySide() {
        let helios = Presets.captureLens(params { $0.lensDistortion = 5 }, lens: "", name: "Helios 44-2")
        let jupiter = Presets.captureLens(params { $0.lensDistortion = 2 }, lens: "", name: "Jupiter-9")
        let list = [helios, jupiter].reduce([Preset](), Presets.putLensPreset)
        XCTAssertEqual(list.count, 2)
        let updated = Presets.putLensPreset(list, Presets.captureLens(params { $0.lensDistortion = 7 }, lens: "", name: "helios 44-2 "))
        XCTAssertEqual(updated.map(\.id), [helios.id, jupiter.id])
        XCTAssertEqual(updated[0].values["lensDistortion"], .number(7))
        // One photo, one recorded lens, two names: two presets.
        let a = Presets.captureLens(params { $0.lensDistortion = 1 }, lens: "----", name: "Helios 44-2")
        let b = Presets.captureLens(params { $0.lensDistortion = 2 }, lens: "----", name: "Jupiter-9")
        XCTAssertEqual(Presets.putLensPreset([a], b).count, 2)
    }

    func testSavingAgainReplacesTheOneForThatLens() {
        let first = Presets.captureLens(params { $0.lensDistortion = 5 }, lens: "50mm")
        let other = Presets.captureLens(params { $0.lensDistortion = 9 }, lens: "85mm")
        let list = Presets.putLensPreset([first, other], Presets.captureLens(params { $0.lensDistortion = 7 }, lens: "50mm"))
        XCTAssertEqual(list.map(\.id), [first.id, other.id])
        XCTAssertEqual(list[0].values["lensDistortion"], .number(7))
    }

    func testChangesOnlyTheLensSettingsOnTopOfTheLook() {
        let lens = Presets.captureLens(params {
            $0.lensProfile = false; $0.lensDistortion = -15; $0.vignetteAmount = 20; $0.vignetteMidpoint = 30
        }, lens: "24mm")
        let base = params { $0.exposure = 1.2; $0.temperature = 5600 }
        let plain = LookApplier.develop(base, look: Looks.natural, strength: 100, adjust: .none, hasFace: false, preset: nil, lensPreset: nil)
        let p = LookApplier.develop(base, look: Looks.natural, strength: 100, adjust: .none, hasFace: false, preset: nil, lensPreset: lens)
        var expected = plain
        expected.lensProfile = false
        expected.lensDistortion = -15
        expected.vignetteAmount = p.vignetteAmount
        expected.vignetteMidpoint = 30
        XCTAssertEqual(p, expected)
        // The look's own vignette stays on top of the lens preset's.
        XCTAssertEqual(p.vignetteAmount, (20 + plain.vignetteAmount - base.vignetteAmount).clamped(-100, 100), accuracy: 1e-5)
        // A RawBoy preset's Lens group gives way to the lens preset; its other groups stay.
        let street = Presets.capture("Street", params { $0.exposure = 0.3; $0.lensDistortion = 40; $0.vignetteAmount = -50 }, [.light, .lens])
        let q = LookApplier.develop(base, look: Looks.natural, strength: 100, adjust: .none, hasFace: false, preset: street, lensPreset: lens)
        XCTAssertEqual(q.exposure, 0.3, accuracy: 1e-6)
        XCTAssertEqual(q.lensDistortion, -15)
        XCTAssertEqual(q.vignetteAmount, 20)
    }
}
