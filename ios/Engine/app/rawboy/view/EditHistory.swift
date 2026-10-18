import Foundation

/**
 * Undo and redo for a photo's edits. [record] is called with the state before each change; a run of quick changes
 * (one slider drag) is one step, a discrete action (`force`) always starts a new one. Same as EditHistory.kt and
 * RawBoy Web's EditHistory.ts.
 */
public struct EditHistory<T> {
    /** Changes closer together than this belong to one step (a slider drag, a crop drag). */
    private static var coalesce: TimeInterval { 0.6 }

    private let limit: Int
    private var past: [T] = []
    private var future: [T] = []
    private var lastAt = -TimeInterval.infinity

    public init(limit: Int = 100) { self.limit = limit }

    public var canUndo: Bool { !past.isEmpty }
    public var canRedo: Bool { !future.isEmpty }

    public mutating func record(_ before: T, force: Bool, now: TimeInterval = ProcessInfo.processInfo.systemUptime) {
        if force || now - lastAt > Self.coalesce {
            past.append(before)
            if past.count > limit { past.removeFirst() }
        }
        future.removeAll()
        // A slider moved right after a button is a step of its own.
        lastAt = force ? -.infinity : now
    }

    /** The state to go back to from [current], or nil when there is none. */
    public mutating func undo(_ current: T) -> T? {
        guard let prev = past.popLast() else { return nil }
        future.append(current)
        lastAt = -.infinity
        return prev
    }

    public mutating func redo(_ current: T) -> T? {
        guard let next = future.popLast() else { return nil }
        past.append(current)
        lastAt = -.infinity
        return next
    }

    /** Forgets the last step without redo (a cancelled brush stroke). */
    public mutating func drop() -> T? {
        lastAt = -.infinity
        return past.popLast()
    }

    public mutating func clear() {
        past.removeAll()
        future.removeAll()
        lastAt = -.infinity
    }
}
