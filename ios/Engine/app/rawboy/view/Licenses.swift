import SwiftUI

/**
 * licenses/THIRD_PARTY_NOTICES.txt (bundled with the engine), including the DNG patent notice. RawBoyFilmEngine
 * (RawBoy Film, GPLv3) bundles licenses/THIRD_PARTY_NOTICES_FILM.txt instead: its GPL notice and source location
 * first, the spektrafilm licences and the GPLv3 text, no DNG SDK.
 */
public struct LicensesView: View {
    @Environment(\.dismiss) private var dismiss

    public init() {}

    public var body: some View {
        NavigationStack {
            ScrollView {
                Text(Self.notices)
                    .font(.system(.caption, design: .monospaced))
                    .textSelection(.enabled)
                    .padding()
            }
            .navigationTitle("Open-source licences")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Done") { dismiss() } }
        }
    }

    static var notices: String {
        #if FILM_EDITION
        let name = "THIRD_PARTY_NOTICES_FILM"
        #else
        let name = "THIRD_PARTY_NOTICES"
        #endif
        guard let url = LutRepository.resourceBundle.url(forResource: name, withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { return "Licences not found." }
        return text
    }
}
