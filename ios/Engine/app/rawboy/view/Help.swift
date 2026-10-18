import SwiftUI

/** One line of a help sheet: the gesture or control (`how`) and what it does. */
public struct HelpItem: Hashable, Sendable {
    public let how: String
    public let text: String

    public init(_ how: String, _ text: String) {
        self.how = how
        self.text = text
    }
}

/** A titled group of `HelpItem`s. */
public struct HelpSection: Hashable, Sendable {
    public let title: String
    public let items: [HelpItem]

    public init(_ title: String, _ items: [HelpItem]) {
        self.title = title
        self.items = items
    }
}

/**
 * The top bars' "?" button (Android's HelpButton): a sheet with the app's basics and gestures, as RawBoy Web's
 * Ctrl+/ sheet. With `licences` the sheet ends with the open-source licences (Pro and Auto keep them here; Camera
 * has them in Settings). Pass `isPresented` to know when the sheet is open (Camera's hardware shutter pauses).
 */
public struct HelpButton: View {
    private let title: String
    private let sections: [HelpSection]
    private let licences: Bool
    private let external: Binding<Bool>?
    @State private var shown = false

    public init(_ title: String, sections: [HelpSection], licences: Bool = false, isPresented: Binding<Bool>? = nil) {
        self.title = title
        self.sections = sections
        self.licences = licences
        self.external = isPresented
    }

    private var presented: Binding<Bool> { external ?? $shown }

    public var body: some View {
        Button { presented.wrappedValue = true } label: { Image(systemName: "questionmark.circle") }
            .accessibilityLabel("Help")
            .sheet(isPresented: presented) { HelpView(title: title, sections: sections, licences: licences) }
    }
}

private struct HelpView: View {
    let title: String
    let sections: [HelpSection]
    let licences: Bool
    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List {
                ForEach(sections, id: \.self) { section in
                    Section(section.title) {
                        ForEach(section.items, id: \.self) { item in
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.how).font(.subheadline.weight(.semibold))
                                Text(item.text).font(.subheadline).foregroundStyle(.secondary)
                            }
                        }
                    }
                }
                if licences {
                    Section { NavigationLink("Open-source licences") { LicensesView() } }
                }
            }
            .navigationTitle(title)
            .navigationBarTitleDisplayMode(.inline)
            .toolbar { Button("Done") { dismiss() } }
        }
        .presentationDetents([.medium, .large])
    }
}
