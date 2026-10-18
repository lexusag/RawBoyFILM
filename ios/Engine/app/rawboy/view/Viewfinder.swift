import MetalKit
import SwiftUI

/** The live camera preview, developed by a ViewfinderRenderer the caller owns (it also receives the frames). */
public struct Viewfinder: UIViewRepresentable {
    let renderer: ViewfinderRenderer

    public init(renderer: ViewfinderRenderer) { self.renderer = renderer }

    public func makeUIView(context: Context) -> MTKView {
        let view = MTKView()
        renderer.attach(view)
        return view
    }

    public func updateUIView(_ view: MTKView, context: Context) {}
}
