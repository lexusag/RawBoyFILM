#if FILM_EDITION
import RawBoyFilmEngine
#else
import RawBoyEngine
#endif
import SwiftUI

@main
struct RawBoyCameraApp: App {
    @StateObject private var vm = CameraViewModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            CameraScreen(vm: vm)
                .preferredColorScheme(.dark)
                .tint(Color.cameraAccent)
                .statusBarHidden()
                .onAppear { vm.activate() }
                .onChange(of: phase) { _, p in
                    if p == .active { vm.activate() } else if p == .background { vm.deactivate() }
                }
        }
    }
}

extension Color {
    /** RawBoy's orange, as Android's Camera theme (set directly: Color.accentColor only follows the asset catalogue). */
    static let cameraAccent = Color(red: 0xFF / 255, green: 0xB7 / 255, blue: 0x4D / 255)
}
