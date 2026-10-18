package app.rawboy.view

import android.os.SystemClock

/**
 * Recognises a double tap from the Slider's own callbacks, so no extra touch
 * handling sits on the Slider. A tap is a gesture that finishes within 250 ms
 * (tapping where the thumb already is doesn't change the value at all); two
 * taps within 400 ms make a double tap.
 */
class SliderTapTracker {
    private var gestureStart = 0L
    private var lastTapEnd = 0L

    fun onValueChange() {
        if (gestureStart == 0L) gestureStart = SystemClock.uptimeMillis()
    }

    /** Returns true when this gesture completes a double tap. */
    fun onGestureFinished(): Boolean {
        val now = SystemClock.uptimeMillis()
        val isTap = gestureStart == 0L || now - gestureStart < 250
        gestureStart = 0L
        val isDouble = isTap && now - lastTapEnd < 400
        lastTapEnd = if (isTap && !isDouble) now else 0L
        return isDouble
    }
}
