import MetalKit
import SwiftUI

/**
 * The developed photo on screen (Android's DevelopPreview composable): an MTKView drawn by DevelopRenderer,
 * redrawn whenever one of its inputs changes. Sizes are in pixels, like the renderer's.
 */
public struct DevelopPreview: UIViewRepresentable {
    public var image: PreviewImage?
    public var params: ShaderParams?
    public var zoomCenter: (Float, Float)?
    public var detail: DetailTile?
    public var frameWidth: Int = 0
    public var frameColor: FrameColor = .grey
    /** Empty space around the frame in the fit view, pixels (the mask tool's room for handles). */
    public var margin: Int = 0
    /** Marks clipped highlights red and crushed shadows blue (Pro's histogram toggles it). */
    public var clipWarning = false

    public init(image: PreviewImage?, params: ShaderParams?, zoomCenter: (Float, Float)? = nil,
                detail: DetailTile? = nil, frameWidth: Int = 0, frameColor: FrameColor = .grey, margin: Int = 0,
                clipWarning: Bool = false) {
        self.image = image
        self.params = params
        self.zoomCenter = zoomCenter
        self.detail = detail
        self.frameWidth = frameWidth
        self.frameColor = frameColor
        self.margin = margin
        self.clipWarning = clipWarning
    }

    public func makeCoordinator() -> DevelopRenderer { DevelopRenderer() }

    public func makeUIView(context: Context) -> MTKView {
        let view = MTKView()
        context.coordinator.attach(view)
        view.autoResizeDrawable = true
        view.contentMode = .redraw
        return view
    }

    public func updateUIView(_ view: MTKView, context: Context) {
        let r = context.coordinator
        r.image = image
        r.params = params
        r.detail = detail
        r.zoomCenter = zoomCenter
        r.frameWidth = frameWidth
        r.frameGrey = frameColor.level
        r.margin = margin
        r.clipWarning = clipWarning
        view.setNeedsDisplay()
    }
}
