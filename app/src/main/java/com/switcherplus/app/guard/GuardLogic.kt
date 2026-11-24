package com.switcherplus.app.guard

sealed class GuardAction {
    object None : GuardAction() { override fun toString() = "None" }
    object TurnOff : GuardAction() { override fun toString() = "TurnOff" }
    data class SetTimer(val minutes: Int) : GuardAction()
}

/**
 * Decides what to do so that the device never stays on longer than [limitMinutes].
 *
 * The Switcher firmware won't accept an auto-shutdown shorter than one hour, but it
 * does accept "turn on with a timer" of any length. So when the device is on and its
 * own countdown is missing (or longer than we want), we re-send ON with a timer equal
 * to the time that's left. From then on the device turns itself off, even if the phone
 * leaves the home network.
 */
object GuardLogic {
    /** Below this many seconds left we simply turn it off. */
    private const val OFF_THRESHOLD_SEC = 30L
    /** Tolerance before we "correct" an existing device countdown. */
    private const val SLACK_SEC = 60L

    fun decide(on: Boolean, timeOnSec: Long, timeLeftSec: Long, limitMinutes: Int): GuardAction {
        if (!on || limitMinutes <= 0) return GuardAction.None
        val wantLeft = limitMinutes * 60L - timeOnSec
        if (wantLeft <= OFF_THRESHOLD_SEC) return GuardAction.TurnOff
        if (timeLeftSec <= 0L || timeLeftSec > wantLeft + SLACK_SEC) {
            val minutes = ((wantLeft + 59) / 60).toInt().coerceAtLeast(1)
            return GuardAction.SetTimer(minutes)
        }
        return GuardAction.None
    }
}
