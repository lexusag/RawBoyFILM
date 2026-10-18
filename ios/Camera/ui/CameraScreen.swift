import AVKit
#if FILM_EDITION
import RawBoyFilmEngine
#else
import RawBoyEngine
#endif
import SwiftUI

/** Viewfinder with the look controls, shutter and the last photo. */
struct CameraScreen: View {
    @ObservedObject var vm: CameraViewModel
    @State private var showSettings = false
    @State private var showHelp = false
    @State private var blink = 0.0
    @State private var shutterScale = 1.0
    @State private var holding = false

    var body: some View {
        VStack(spacing: 0) {
            topBar
            viewfinder
            // A menu instead of a strip of chips, and one row per slider: the viewfinder keeps the screen.
            VStack(spacing: 0) {
                LookPicker(selectedId: vm.settings.lookId, onSelect: vm.setLook)
                ValueSlider("Strength", value: vm.settings.strength, range: 0...100, defaultValue: LookApplier.defaultStrength,
                            onChange: vm.setStrength)
                ValueSlider("Brightness", value: vm.settings.brightness, range: -100...100, defaultValue: 0, onChange: vm.setBrightness)
                ValueSlider("Warmth", value: vm.settings.warmth, range: -100...100, defaultValue: 0, onChange: vm.setWarmth)
            }
            .padding(.horizontal, 16)
            .padding(.top, 4)
            bottomBar
        }
        .background(Color.black)
        .foregroundStyle(.white)
        .toast($vm.message)
        .sheet(isPresented: $showSettings) { SettingsSheet(vm: vm) }
        .modifier(HardwareShutter(enabled: !showSettings && !showHelp, action: vm.shutter))
        .onChange(of: vm.shutterCount) { _, _ in
            // Shutter feedback: the viewfinder blinks dark, the shutter button dips, and a haptic tick.
            UIImpactFeedbackGenerator(style: .medium).impactOccurred()
            shutterScale = 0.85
            withAnimation(.easeOut(duration: 0.22)) { shutterScale = 1 }
            blink = 0.85
            withAnimation(.easeOut(duration: 0.2)) { blink = 0 }
        }
    }

    private var topBar: some View {
        HStack(spacing: 18) {
            Text(vm.pending > 0 ? "Saving \(vm.pending)…" : "").font(.caption).foregroundStyle(Color.cameraAccent)
            Spacer()
            Button { vm.setKeepDng(!vm.settings.keepDng) } label: {
                Text("RAW").font(.caption.bold()).strikethrough(!vm.settings.keepDng)
                    .foregroundStyle(vm.settings.keepDng ? Color.cameraAccent : .white.opacity(0.6))
            }
            .accessibilityLabel("Keep RAW (DNG)")
            if vm.flashAvailable {
                Button(action: vm.toggleFlash) {
                    Image(systemName: vm.settings.flash == .on ? "bolt.fill" : "bolt.slash")
                        .foregroundStyle(vm.settings.flash == .on ? Color.cameraAccent : .white)
                }
                .accessibilityLabel(vm.settings.flash == .on ? "Flash on" : "Flash off")
            }
            HelpButton(Bundle.main.object(forInfoDictionaryKey: "CFBundleDisplayName") as? String ?? "RawBoy Camera",
                       sections: cameraHelp, isPresented: $showHelp)
            Button { showSettings = true } label: { Image(systemName: "gearshape") }.accessibilityLabel("Settings")
        }
        .font(.title3)
        .padding(.horizontal, 16)
        .frame(height: 44)
    }

    // Viewfinder, at the raw's aspect ratio, in whatever height the controls leave.
    private var viewfinder: some View {
        ZStack {
            Color.black
            ZStack {
                Color(white: 0.04)
                Viewfinder(renderer: vm.renderer)
                GeometryReader { geo in
                    Color.clear.contentShape(Rectangle())
                        // Tap: focus lock at that point (a new tap moves it; the AF LOCK badge releases it).
                        .onTapGesture { p in vm.focusAt(p.x / geo.size.width, p.y / geo.size.height) }
                        // Press and hold: the auto develop without the look.
                        .onLongPressGesture(minimumDuration: 0.25, perform: {}, onPressingChanged: { pressing in
                            if pressing {
                                holding = true
                                Task {
                                    try? await Task.sleep(nanoseconds: 250_000_000)
                                    if holding { vm.setCompare(true) }
                                }
                            } else {
                                holding = false
                                if vm.compare { vm.setCompare(false) }
                            }
                        })
                    if let p = vm.focusPoint {
                        FocusRing(scanning: vm.controller.frames.focusing)
                            .position(x: p.x * geo.size.width, y: p.y * geo.size.height)
                    }
                }
                switch vm.status {
                case .needsPermission:
                    Button("Allow camera") { vm.openSettingsApp() }.buttonStyle(.borderedProminent)
                case .opening:
                    ProgressView()
                case .failed:
                    Text("Camera unavailable").foregroundStyle(.red)
                case .ready:
                    EmptyView()
                }
                if vm.lenses.count > 1 {
                    LensSelector(labels: vm.lenses.map(\.label), selected: vm.settings.lens, onSelect: vm.selectLens)
                        .frame(maxHeight: .infinity, alignment: .bottom)
                        .padding(.bottom, 16)
                }
                if vm.focusPoint != nil {
                    Button { vm.releaseFocus() } label: {
                        Text("AF LOCK  ✕").font(.caption.weight(.semibold)).foregroundStyle(.black)
                            .padding(.horizontal, 10).padding(.vertical, 4)
                            .background(Capsule().fill(Color(red: 1, green: 0.84, blue: 0.31)))
                    }
                    .frame(maxHeight: .infinity, alignment: .top)
                    .padding(.top, 12)
                }
                if vm.compare {
                    OverlayLabel("Without look").frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topLeading)
                }
                // Top right: the histogram (Settings), then the raw format when it isn't the chosen one.
                VStack(alignment: .trailing, spacing: 0) {
                    if vm.settings.showHistogram {
                        HistogramView(histogram: vm.histogram)
                            .frame(width: 120, height: 48)
                            .padding([.top, .trailing], 12)
                            .allowsHitTesting(false)
                    }
                    if vm.recording != vm.settings.rawFormat {
                        OverlayLabel(vm.recording == .proRaw ? "ProRAW" : "RAW")
                            .padding(.top, vm.settings.showHistogram ? -6 : 0)
                    }
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .topTrailing)
                Color.black.opacity(blink).allowsHitTesting(false)
            }
            .aspectRatio(vm.aspect, contentMode: .fit)
        }
    }

    private var bottomBar: some View {
        HStack {
            Group {
                if let shot = vm.lastShot {
                    Image(uiImage: shot).resizable().scaledToFill()
                        .frame(width: 56, height: 56).clipShape(RoundedRectangle(cornerRadius: 8))
                        // The photo is in Photos; open the app there.
                        .onTapGesture { if let url = URL(string: "photos-redirect://") { UIApplication.shared.open(url) } }
                } else {
                    Color.clear
                }
            }
            .frame(width: 56, height: 56)
            Spacer()
            Button(action: vm.shutter) {
                Circle().fill(vm.status == .ready ? Color.white : .gray).padding(8)
                    .overlay(Circle().stroke(vm.status == .ready ? Color.white : .gray, lineWidth: 4))
                    .frame(width: 76, height: 76)
            }
            .disabled(vm.status != .ready)
            .scaleEffect(shutterScale)
            .accessibilityLabel("Shutter")
            Spacer()
            Group {
                if vm.frontAvailable {
                    Button(action: vm.switchFacing) {
                        Image(systemName: "arrow.triangle.2.circlepath.camera").font(.title2)
                            .frame(width: 56, height: 56)
                            .background(RoundedRectangle(cornerRadius: 14).fill(Color(white: 0.16)))
                    }
                    .disabled(vm.status == .opening)
                    .accessibilityLabel(vm.settings.front ? "Back camera" : "Selfie camera")
                } else {
                    Color.clear
                }
            }
            .frame(width: 56, height: 56)
        }
        .padding(.horizontal, 24)
        .padding(.vertical, 8)
    }
}

/** UW · W · T: the selected lens in a filled circle. */
private struct LensSelector: View {
    let labels: [String]
    let selected: String
    let onSelect: (String) -> Void

    var body: some View {
        HStack(spacing: 4) {
            ForEach(labels, id: \.self) { label in
                let isSelected = label == selected
                Text(label).font(.subheadline.weight(.semibold))
                    .foregroundStyle(isSelected ? .black : .white)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(isSelected ? Color(white: 0.82) : .clear))
                    .contentShape(Circle())
                    .onTapGesture { onSelect(label) }
            }
        }
        .padding(4)
        .background(Capsule().fill(Color.black.opacity(0.6)))
    }
}

/** Focus lock ring: pulsing while focusing. */
private struct FocusRing: View {
    let scanning: Bool
    @State private var pulse = false

    var body: some View {
        ZStack {
            Circle().stroke(Color.white, lineWidth: 2).frame(width: 72, height: 72)
            Circle().fill(Color.white).frame(width: 6, height: 6)
        }
        .opacity(scanning && pulse ? 0.4 : 1)
        .onAppear { withAnimation(.easeInOut(duration: 0.35).repeatForever()) { pulse = true } }
        .allowsHitTesting(false)
    }
}

/** The look, picked from a menu (Pro's stock picker). */
private struct LookPicker: View {
    let selectedId: String
    let onSelect: (String) -> Void

    var body: some View {
        HStack(spacing: 8) {
            Text("Look").font(.subheadline).frame(width: 88, alignment: .leading)
            Picker("Look", selection: Binding(get: { selectedId }, set: { onSelect($0) })) {
                ForEach(Edition.looks) { Text($0.name).tag($0.id) }
            }
            .pickerStyle(.menu)
            .tint(Color.cameraAccent)
            Spacer()
        }
        .frame(height: ValueSlider.rowHeight)
    }
}

private struct SettingsSheet: View {
    @ObservedObject var vm: CameraViewModel

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading) {
                        HStack {
                            Text("JPEG quality")
                            Spacer()
                            Text("\(vm.settings.jpegQuality)").foregroundStyle(Color.cameraAccent)
                        }
                        Slider(value: Binding(get: { Double(vm.settings.jpegQuality) }, set: { vm.setJpegQuality(Int($0.rounded())) }),
                               in: Double(CameraSettings.minQuality)...100, step: 1)
                        Text("Encoded with jpegli, full-resolution colour (4:4:4). Default 95.")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
                Section {
                    Toggle(isOn: Binding(get: { vm.settings.keepDng }, set: vm.setKeepDng)) {
                        VStack(alignment: .leading) {
                            Text("Keep raw (DNG)")
                            Text("Saved to Photos › RawBoy Camera RAW").font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Picker("Raw format", selection: Binding(get: { vm.settings.rawFormat }, set: vm.setRawFormat)) {
                        Text("ProRAW: cleaner in low light").tag(RawFormat.proRaw)
                        Text("RAW: single exposure").tag(RawFormat.bayer)
                    }
                    Toggle(isOn: Binding(get: { vm.settings.smallExport }, set: vm.setSmallExport)) {
                        VStack(alignment: .leading) {
                            Text("Smaller photos")
                            Text("\(CameraSettings.smallLongSide) px on the long side; off saves full resolution")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Toggle(isOn: Binding(get: { vm.settings.showHistogram }, set: vm.setShowHistogram)) {
                        VStack(alignment: .leading) {
                            Text("Histogram")
                            Text("A live RGB histogram in a corner of the viewfinder")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                    Toggle(isOn: Binding(get: { vm.settings.rememberLast }, set: vm.setRememberLast)) {
                        VStack(alignment: .leading) {
                            Text("Remember camera & settings")
                            Text("Otherwise each start uses W, Yellow Portrait 400 and default sliders")
                                .font(.caption).foregroundStyle(.secondary)
                        }
                    }
                }
                Section {
                    ForEach(Edition.credits, id: \.self) { line in
                        Text(line).font(.caption).foregroundStyle(.secondary)
                    }
                    NavigationLink("Open-source licences") { LicensesView() }
                }
            }
            .navigationTitle("Settings")
            .navigationBarTitleDisplayMode(.inline)
        }
        .presentationDetents([.medium, .large])
    }
}

/**
 * Volume up / down and the Camera Control button take a photo (one per press, on release), as Android's volume keys;
 * no volume change while the camera runs. Not while the settings or the help are open. iOS 18+ (the SwiftUI capture event).
 */
private struct HardwareShutter: ViewModifier {
    let enabled: Bool
    let action: () -> Void

    func body(content: Content) -> some View {
        if #available(iOS 18.0, *) {
            content.onCameraCaptureEvent(isEnabled: enabled) { event in
                if event.phase == .ended { action() }
            }
        } else {
            content
        }
    }
}
