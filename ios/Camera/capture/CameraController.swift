@preconcurrency import AVFoundation
import Foundation
import os
import UIKit

/**
 * The camera (AVFoundation): one lens at a time streaming upright sRGB frames for the viewfinder, plus raw stills
 * as DNG. Each lens is a physical camera (Bayer RAW isn't available on the virtual multi-camera devices), so
 * switching lenses reconfigures the session. All session work runs on [queue]. Port of the role of Android's
 * Camera2 CameraController; the Pixel-specific workarounds there (YUV matrix, tone curve, manual flash) don't apply.
 */
final class CameraController: NSObject, @unchecked Sendable {
    /** A lens the app offers. */
    struct Info: Equatable {
        let label: String
        let deviceId: String
        let isFront: Bool
        let hasFlash: Bool
        /** 35 mm equivalent focal length, for ordering UW < W < T. */
        let equivalentFocal: Double

        static func == (a: Info, b: Info) -> Bool { a.deviceId == b.deviceId }
    }

    /** What the camera reports for the current frame. */
    struct FrameState {
        /** As-shot white (CIE xy) from the camera's white balance gains. */
        var whiteXY: [Double]?
        var iso: Float = 0
        /** Faces in upright normalised coordinates (as shown). */
        var faces: [CGRect] = []
        var focusing = false
    }

    struct CaptureResult {
        let dng: Data
        let iso: Float
    }

    struct Failure: LocalizedError {
        let message: String
        var errorDescription: String? { message }
    }

    private static let log = Logger(subsystem: "app.rawboy", category: "RawBoyCamera")

    let session = AVCaptureSession()
    let queue = DispatchQueue(label: "RawBoy-camera")
    private let frameQueue = DispatchQueue(label: "RawBoy-frames", qos: .userInteractive)
    private let videoOutput = AVCaptureVideoDataOutput()
    private let photoOutput = AVCapturePhotoOutput()
    private let metadataOutput = AVCaptureMetadataOutput()
    private var input: AVCaptureDeviceInput?
    private var rotation: AVCaptureDevice.RotationCoordinator?
    private var rawType: OSType?
    private var current: Info?
    private var captures: [Int64: PhotoDelegate] = [:]
    private let stateLock = NSLock()
    private var state = FrameState()
    private var configured = false
    /** Log the next frame's size (after each start), to check its orientation. */
    private var logNextFrame = true

    /** Each viewfinder frame (frame queue). */
    var onFrame: ((CVPixelBuffer) -> Void)?

    /** The camera's latest frame metadata. */
    var frames: FrameState { stateLock.withLock { state } }

    /** The raw stills' full upright size for the current lens (sizes grain like the final), set by [start]. */
    private(set) var rawSize = CGSize(width: 3024, height: 4032)

    /** Lenses with a raw format, per side, in UW, W, T order. */
    static func lenses(front: Bool) -> [Info] {
        let types: [AVCaptureDevice.DeviceType] = front
            ? [.builtInWideAngleCamera]
            : [.builtInUltraWideCamera, .builtInWideAngleCamera, .builtInTelephotoCamera]
        let devices = AVCaptureDevice.DiscoverySession(deviceTypes: types, mediaType: .video,
                                                      position: front ? .front : .back).devices
        let sorted = devices.map { d -> (AVCaptureDevice, Double) in
            // Approximate 35 mm equivalents, only for ordering (iPhone 17 Pro: 13, 24, 100 mm).
            let focal: Double = switch d.deviceType {
            case .builtInUltraWideCamera: 13
            case .builtInTelephotoCamera: 100
            default: 24
            }
            return (d, focal)
        }.sorted { $0.1 < $1.1 }
        return sorted.map { d, focal in
            let label = front ? "W" : d.deviceType == .builtInUltraWideCamera ? "UW" : d.deviceType == .builtInTelephotoCamera ? "T" : "W"
            return Info(label: label, deviceId: d.uniqueID, isFront: front, hasFlash: d.hasFlash, equivalentFocal: focal)
        }
    }

    /** Starts (or switches to) [lens], recording [format] (falling back to the other raw format, then failing). */
    func start(_ lens: Info, format: RawFormat) async throws -> RawFormat {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<RawFormat, Error>) in
            queue.async {
                do {
                    let used = try self.configure(lens, format)
                    // After the commit: it rebuilds the connections for a new input, dropping their settings
                    // (the selfie camera came out sideways and stretched).
                    self.orientConnection(front: lens.isFront)
                    // Only once the configuration is committed: startRunning inside it throws.
                    if !self.session.isRunning { self.session.startRunning() }
                    c.resume(returning: used)
                } catch {
                    c.resume(throwing: error)
                }
            }
        }
    }

    private func configure(_ lens: Info, _ format: RawFormat) throws -> RawFormat {
        guard let device = AVCaptureDevice(uniqueID: lens.deviceId) else { throw Failure(message: "camera not found") }
        session.beginConfiguration()
        defer { session.commitConfiguration() }
        session.sessionPreset = .photo
        // sRGB frames, so the viewfinder's linearisation is exact up to a gain.
        session.automaticallyConfiguresCaptureDeviceForWideColor = false
        if let input { session.removeInput(input) }
        let newInput = try AVCaptureDeviceInput(device: device)
        guard session.canAddInput(newInput) else { throw Failure(message: "cannot use this camera") }
        session.addInput(newInput)
        input = newInput
        if !configured {
            videoOutput.videoSettings = [kCVPixelBufferPixelFormatTypeKey as String: kCVPixelFormatType_32BGRA]
            videoOutput.alwaysDiscardsLateVideoFrames = true
            videoOutput.setSampleBufferDelegate(self, queue: frameQueue)
            if session.canAddOutput(videoOutput) { session.addOutput(videoOutput) }
            if session.canAddOutput(photoOutput) { session.addOutput(photoOutput) }
            if session.canAddOutput(metadataOutput) {
                session.addOutput(metadataOutput)
                metadataOutput.setMetadataObjectsDelegate(self, queue: frameQueue)
            }
            configured = true
        }
        if metadataOutput.availableMetadataObjectTypes.contains(.face) { metadataOutput.metadataObjectTypes = [.face] }
        try device.lockForConfiguration()
        if device.activeColorSpace != .sRGB, device.activeFormat.supportedColorSpaces.contains(.sRGB) {
            device.activeColorSpace = .sRGB
        }
        if device.activeFormat.isVideoHDRSupported {
            // No local tone mapping in the preview: the viewfinder's linearisation assumes a plain curve.
            device.automaticallyAdjustsVideoHDREnabled = false
            device.isVideoHDREnabled = false
        }
        if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
        device.unlockForConfiguration()

        rotation = AVCaptureDevice.RotationCoordinator(device: device, previewLayer: nil)

        // Raw: Bayer first (or ProRAW when asked), the other as fallback.
        photoOutput.maxPhotoQualityPrioritization = .quality
        if photoOutput.isAppleProRAWSupported { photoOutput.isAppleProRAWEnabled = format == .proRaw }
        let types = photoOutput.availableRawPhotoPixelFormatTypes
        let bayer = types.first { AVCapturePhotoOutput.isBayerRAWPixelFormat($0) }
        let proRaw = types.first { AVCapturePhotoOutput.isAppleProRAWPixelFormat($0) }
        var used = format
        rawType = format == .proRaw ? (proRaw ?? bayer) : (bayer ?? proRaw)
        if rawType == nil, format == .bayer, photoOutput.isAppleProRAWSupported {
            photoOutput.isAppleProRAWEnabled = true
            rawType = photoOutput.availableRawPhotoPixelFormatTypes.first { AVCapturePhotoOutput.isAppleProRAWPixelFormat($0) }
        }
        guard let rawType else { throw Failure(message: "this camera has no RAW") }
        used = AVCapturePhotoOutput.isAppleProRAWPixelFormat(rawType) ? .proRaw : .bayer
        if used == .bayer && photoOutput.isAppleProRAWEnabled { photoOutput.isAppleProRAWEnabled = false }
        // 12 MP stills in both formats (see RawFormat.proRaw); Bayer raws on the 48 MP sensors come binned anyway.
        if let largest = device.activeFormat.supportedMaxPhotoDimensions.max(by: { $0.width * $0.height < $1.width * $1.height }) {
            photoOutput.maxPhotoDimensions = device.activeFormat.supportedMaxPhotoDimensions
                .filter { $0.width * $0.height <= 13_000_000 }.max(by: { $0.width * $0.height < $1.width * $1.height }) ?? largest
            let d = photoOutput.maxPhotoDimensions
            rawSize = CGSize(width: Int(min(d.width, d.height)), height: Int(max(d.width, d.height)))
        }
        current = lens
        logNextFrame = true
        Self.log.notice("camera \(lens.label) (\(device.localizedName)): \(used.rawValue), raw \(Int(self.rawSize.width))x\(Int(self.rawSize.height))")
        return used
    }

    /**
     * Frames in the sensor's own landscape orientation, unmirrored: the viewfinder turns them upright (and mirrors the
     * selfie camera) itself, see [viewfinderRotation]. Asking the connection to do it worked for the back lenses but
     * left the selfie camera's frames sideways after a switch.
     */
    private func orientConnection(front: Bool) {
        guard let c = videoOutput.connection(with: .video) else { return }
        if c.isVideoMirroringSupported {
            c.automaticallyAdjustsVideoMirroring = false
            c.isVideoMirrored = false
        }
        if c.isVideoRotationAngleSupported(0) { c.videoRotationAngle = 0 }
        Self.log.notice("viewfinder connection: rotation \(c.videoRotationAngle), mirrored \(c.isVideoMirrored)")
    }

    /** Clockwise quarter turns from a sensor-oriented buffer to the portrait UI: the 90° AVFoundation uses. */
    static let viewfinderRotation = 1

    func stop() {
        queue.async {
            if self.session.isRunning { self.session.stopRunning() }
        }
    }

    // MARK: - Focus

    /** Focus (and meter) at a point of the viewfinder (upright normalised, as shown) and keep it there. */
    func focusAt(_ x: Double, _ y: Double) {
        queue.async {
            guard let device = self.input?.device, let lens = self.current else { return }
            // Device coordinates are the sensor's landscape frame; the viewfinder shows it turned 90° (mirrored
            // for the selfie camera).
            let point = lens.isFront ? CGPoint(x: y, y: x) : CGPoint(x: y, y: 1 - x)
            do {
                try device.lockForConfiguration()
                if device.isFocusPointOfInterestSupported { device.focusPointOfInterest = point }
                if device.isFocusModeSupported(.autoFocus) { device.focusMode = .autoFocus }
                if device.isExposurePointOfInterestSupported {
                    device.exposurePointOfInterest = point
                    device.exposureMode = .continuousAutoExposure
                }
                device.unlockForConfiguration()
            } catch {
                Self.log.error("focus: \(error.localizedDescription)")
            }
        }
    }

    func releaseFocus() {
        queue.async {
            guard let device = self.input?.device else { return }
            do {
                try device.lockForConfiguration()
                let centre = CGPoint(x: 0.5, y: 0.5)
                if device.isFocusPointOfInterestSupported { device.focusPointOfInterest = centre }
                if device.isExposurePointOfInterestSupported { device.exposurePointOfInterest = centre }
                if device.isFocusModeSupported(.continuousAutoFocus) { device.focusMode = .continuousAutoFocus }
                device.unlockForConfiguration()
            } catch {}
        }
    }

    // MARK: - Capture

    /** Takes a raw still; [onShutter] runs when the camera captures it (shutter feedback). */
    func captureDng(flash: FlashMode, onShutter: @escaping @Sendable () -> Void) async throws -> CaptureResult {
        try await withCheckedThrowingContinuation { (c: CheckedContinuation<CaptureResult, Error>) in
            queue.async {
                guard let rawType = self.rawType else {
                    c.resume(throwing: Failure(message: "camera not ready"))
                    return
                }
                let settings = AVCapturePhotoSettings(rawPixelFormatType: rawType)
                settings.maxPhotoDimensions = self.photoOutput.maxPhotoDimensions
                if self.current?.hasFlash == true, self.photoOutput.supportedFlashModes.contains(.on) {
                    settings.flashMode = flash == .on ? .on : .off
                }
                // The photo's orientation: how the phone is held, level with the horizon.
                if let angle = self.rotation?.videoRotationAngleForHorizonLevelCapture,
                   let connection = self.photoOutput.connection(with: .video), connection.isVideoRotationAngleSupported(angle) {
                    connection.videoRotationAngle = angle
                    if connection.isVideoMirroringSupported {
                        connection.automaticallyAdjustsVideoMirroring = false
                        connection.isVideoMirrored = self.current?.isFront == true
                    }
                }
                let iso = self.input?.device.iso ?? 0
                let delegate = PhotoDelegate(onShutter: onShutter) { [weak self] result in
                    self?.queue.async { self?.captures[settings.uniqueID] = nil }
                    switch result {
                    case let .success(data): c.resume(returning: CaptureResult(dng: data, iso: iso))
                    case let .failure(error): c.resume(throwing: error)
                    }
                }
                self.captures[settings.uniqueID] = delegate
                self.photoOutput.capturePhoto(with: settings, delegate: delegate)
            }
        }
    }

    /** One still: the DNG data, or why it failed. */
    private final class PhotoDelegate: NSObject, AVCapturePhotoCaptureDelegate {
        private let onShutter: @Sendable () -> Void
        private let done: (Result<Data, Error>) -> Void
        private var data: Data?

        init(onShutter: @escaping @Sendable () -> Void, done: @escaping (Result<Data, Error>) -> Void) {
            self.onShutter = onShutter
            self.done = done
        }

        func photoOutput(_ output: AVCapturePhotoOutput, willCapturePhotoFor resolvedSettings: AVCaptureResolvedPhotoSettings) {
            onShutter()
        }

        func photoOutput(_ output: AVCapturePhotoOutput, didFinishProcessingPhoto photo: AVCapturePhoto, error: Error?) {
            if photo.isRawPhoto { data = photo.fileDataRepresentation() }
        }

        func photoOutput(_ output: AVCapturePhotoOutput, didFinishCaptureFor resolvedSettings: AVCaptureResolvedPhotoSettings,
                         error: Error?) {
            if let error {
                done(.failure(error))
            } else if let data {
                done(.success(data))
            } else {
                done(.failure(Failure(message: "no raw data")))
            }
        }
    }
}

extension CameraController: AVCaptureVideoDataOutputSampleBufferDelegate, AVCaptureMetadataOutputObjectsDelegate {
    func captureOutput(_ output: AVCaptureOutput, didOutput sampleBuffer: CMSampleBuffer, from connection: AVCaptureConnection) {
        guard let buffer = CMSampleBufferGetImageBuffer(sampleBuffer) else { return }
        if let device = input?.device {
            // As-shot white for the live develop, from the camera's current white balance.
            let gains = device.deviceWhiteBalanceGains
            let max = device.maxWhiteBalanceGain
            let clamped = AVCaptureDevice.WhiteBalanceGains(
                redGain: min(Swift.max(gains.redGain, 1), max), greenGain: min(Swift.max(gains.greenGain, 1), max),
                blueGain: min(Swift.max(gains.blueGain, 1), max))
            let xy = device.chromaticityValues(for: clamped)
            let iso = device.iso
            let focusing = device.isAdjustingFocus
            stateLock.withLock {
                state.whiteXY = [Double(xy.x), Double(xy.y)]
                state.iso = iso
                state.focusing = focusing
            }
        }
        if logNextFrame {
            logNextFrame = false
            Self.log.notice("first frame \(CVPixelBufferGetWidth(buffer))x\(CVPixelBufferGetHeight(buffer))")
        }
        onFrame?(buffer)
    }

    func metadataOutput(_ output: AVCaptureMetadataOutput, didOutput objects: [AVMetadataObject],
                        from connection: AVCaptureConnection) {
        let front = current?.isFront == true
        // Face bounds are in the sensor's landscape frame (0..1); turn them upright as shown.
        let faces = objects.compactMap { ($0 as? AVMetadataFaceObject)?.bounds }.map { b -> CGRect in
            let x = 1 - b.maxY
            return CGRect(x: front ? 1 - x - b.height : x, y: b.minX, width: b.height, height: b.width)
        }
        stateLock.withLock { state.faces = faces }
    }
}
