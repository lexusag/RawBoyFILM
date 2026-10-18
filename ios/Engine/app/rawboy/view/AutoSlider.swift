import SwiftUI

/**
 * A labelled slider with its value; double-tap the slider or tap the value to reset to [defaultValue]
 * (Android's AutoSlider / ParamSlider with SliderTapTracker). `stacked` puts the label and value above the slider,
 * so two can share a row and still have a usable track (`stackedRowHeight`).
 */
public struct ValueSlider: View {
    let label: String
    let value: Float
    let range: ClosedRange<Float>
    let defaultValue: Float
    var format: (Float) -> String
    let stacked: Bool
    let onChange: (Float) -> Void

    public init(_ label: String, value: Float, range: ClosedRange<Float>, defaultValue: Float,
                format: ((Float) -> String)? = nil, stacked: Bool = false, onChange: @escaping (Float) -> Void) {
        self.label = label
        self.value = value
        self.range = range
        self.defaultValue = defaultValue
        self.format = format ?? { v in
            let shown = v.rounded()
            return range.lowerBound < 0 && shown > 0 ? "+\(Int(shown))" : "\(Int(shown))"
        }
        self.stacked = stacked
        self.onChange = onChange
    }

    public var body: some View {
        if stacked {
            VStack(spacing: 0) {
                HStack(spacing: 8) {
                    Text(label).font(.subheadline).lineLimit(1)
                    Spacer(minLength: 0)
                    valueText
                }
                slider
            }
            .frame(height: ValueSlider.stackedRowHeight)
        } else {
            // One row per control (label | slider | value), so the photo or viewfinder keeps most of the screen.
            HStack(spacing: 8) {
                Text(label).font(.subheadline).lineLimit(1).frame(width: 88, alignment: .leading)
                slider
                valueText.frame(width: 40, alignment: .trailing)
            }
            .frame(height: ValueSlider.rowHeight)
        }
    }

    private var valueText: some View {
        Text(format(value))
            .font(.caption.monospacedDigit())
            .foregroundStyle(value == defaultValue ? Color.secondary : Color.accentColor)
            .contentShape(Rectangle())
            .onTapGesture { onChange(defaultValue) }
    }

    @ViewBuilder private var slider: some View {
        let native = Slider(value: Binding(get: { Double(value.clamped(range)) }, set: { onChange(Float($0)) }),
                            in: Double(range.lowerBound)...Double(range.upperBound))
            .simultaneousGesture(TapGesture(count: 2).onEnded { onChange(defaultValue) })
        if range.lowerBound < 0 && range.upperBound > 0 {
            // The system slider still takes the touches; it's just not drawn.
            native.opacity(0.02).background { SignedSliderTrack(value: value, range: range) }
        } else {
            native
        }
    }

    /** Height of one slider row, for panels sized to a number of sliders. */
    public static let rowHeight: CGFloat = 36
    /** Height of a stacked slider (label and value above the slider). */
    public static let stackedRowHeight: CGFloat = 52
}

/**
 * Track and thumb of a signed slider (−100…100 and the like): filled from 0 to the value, with a mark at 0. The
 * system Slider always fills from the left end, so every slider at 0 looked half applied; draw this behind a
 * nearly transparent system Slider, which keeps the touches and VoiceOver.
 */
public struct SignedSliderTrack: View {
    let value: Float
    let range: ClosedRange<Float>

    public init(value: Float, range: ClosedRange<Float>) {
        self.value = value
        self.range = range
    }

    public var body: some View {
        GeometryReader { g in
            let inset: CGFloat = 12 // roughly where the system thumb's centre stops
            let x = { (v: Float) -> CGFloat in
                inset + CGFloat((v.clamped(range) - range.lowerBound) / (range.upperBound - range.lowerBound)) * (g.size.width - 2 * inset)
            }
            let zero = x(0)
            let at = x(value)
            ZStack(alignment: .leading) {
                Capsule().fill(Color.secondary.opacity(0.35)).frame(height: 4)
                Rectangle().fill(Color.accentColor).frame(width: abs(at - zero), height: 4).offset(x: min(zero, at))
                Rectangle().fill(Color.secondary).frame(width: 2, height: 10).offset(x: zero - 1)
                Circle().fill(Color.white).shadow(color: .black.opacity(0.25), radius: 2, y: 1)
                    .frame(width: 24, height: 24).offset(x: at - 12)
            }
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

private extension Float {
    func clamped(_ r: ClosedRange<Float>) -> Float { Swift.min(Swift.max(self, r.lowerBound), r.upperBound) }
}

/** A small dark label over the photo ("100%", "Without look"). */
public struct OverlayLabel: View {
    let text: String

    public init(_ text: String) { self.text = text }

    public var body: some View {
        Text(text)
            .font(.caption.weight(.medium))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .background(Color.black.opacity(0.6), in: RoundedRectangle(cornerRadius: 4))
            .padding(12)
    }
}

/** A message shown briefly at the bottom (Android's Snackbar). */
public struct Toast: ViewModifier {
    @Binding var message: String?

    public func body(content: Content) -> some View {
        content.overlay(alignment: .bottom) {
            if let message {
                Text(message)
                    .font(.footnote)
                    .padding(.horizontal, 16)
                    .padding(.vertical, 10)
                    .background(Color(white: 0.2), in: RoundedRectangle(cornerRadius: 8))
                    .padding(.bottom, 24)
                    .padding(.horizontal, 16)
                    .transition(.opacity)
                    .task(id: message) {
                        try? await Task.sleep(nanoseconds: 3_500_000_000)
                        withAnimation { self.message = nil }
                    }
            }
        }
    }
}

public extension View {
    func toast(_ message: Binding<String?>) -> some View { modifier(Toast(message: message)) }
}
