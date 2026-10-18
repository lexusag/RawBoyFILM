package app.rawboy.view

import android.os.SystemClock

/**
 * Undo and redo for a photo's edits. [record] is called with the state before each change; a run of quick changes
 * (one slider drag) is one step, a discrete action (`force`) always starts a new one. Same as RawBoy Web's
 * EditHistory.ts and the iOS EditHistory.swift.
 */
class EditHistory<T : Any>(private val limit: Int = 100) {
    private val past = ArrayDeque<T>()
    private val future = ArrayDeque<T>()
    private var lastAt = Long.MIN_VALUE / 2

    val canUndo get() = past.isNotEmpty()
    val canRedo get() = future.isNotEmpty()

    fun record(before: T, force: Boolean, now: Long = SystemClock.uptimeMillis()) {
        if (force || now - lastAt > COALESCE_MS) {
            past.addLast(before)
            if (past.size > limit) past.removeFirst()
        }
        future.clear()
        // A slider moved right after a button is a step of its own.
        lastAt = if (force) Long.MIN_VALUE / 2 else now
    }

    /** The state to go back to from [current], or null when there is none. */
    fun undo(current: T): T? {
        val prev = past.removeLastOrNull() ?: return null
        future.addLast(current)
        lastAt = Long.MIN_VALUE / 2
        return prev
    }

    fun redo(current: T): T? {
        val next = future.removeLastOrNull() ?: return null
        past.addLast(current)
        lastAt = Long.MIN_VALUE / 2
        return next
    }

    /** Forgets the last step without redo (a cancelled brush stroke). */
    fun drop(): T? {
        lastAt = Long.MIN_VALUE / 2
        return past.removeLastOrNull()
    }

    fun clear() {
        past.clear()
        future.clear()
        lastAt = Long.MIN_VALUE / 2
    }

    private companion object {
        /** Changes closer together than this belong to one step (a slider drag, a crop drag). */
        const val COALESCE_MS = 600L
    }
}
