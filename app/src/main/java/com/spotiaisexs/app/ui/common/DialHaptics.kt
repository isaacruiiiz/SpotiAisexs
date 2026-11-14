package com.spotiaisexs.app.ui.common

import android.os.Build
import android.view.HapticFeedbackConstants
import android.view.View

/**
 * The few haptic "words" of the app, so the same gesture always feels the same:
 *  - [tick]: a notch while dragging (the dial moving along its scale);
 *  - [step]: a firmer notch when something snaps into a new position or zone;
 *  - [click]: a button or tab was pressed;
 *  - [confirm] / [reject]: something was turned on / taken away.
 * They go through the view, so the system's own "touch feedback" setting is respected.
 */
object DialHaptics {
    fun tick(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_FREQUENT_TICK
            else HapticFeedbackConstants.CLOCK_TICK,
        )
    }

    fun step(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 34) HapticFeedbackConstants.SEGMENT_TICK
            else HapticFeedbackConstants.CONTEXT_CLICK,
        )
    }

    fun click(view: View) {
        view.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)
    }

    fun confirm(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.CONFIRM
            else HapticFeedbackConstants.VIRTUAL_KEY,
        )
    }

    fun reject(view: View) {
        view.performHapticFeedback(
            if (Build.VERSION.SDK_INT >= 30) HapticFeedbackConstants.REJECT
            else HapticFeedbackConstants.LONG_PRESS,
        )
    }
}
