package dev.darsma.wearkey

import android.app.Activity
import android.os.Bundle
import dev.darsma.wearkey.uiwear.KeyGridView

/**
 * Second entry point required by spec §4.5. On Wear OS most real input (notification replies,
 * WhatsApp, browser URL bars) is routed through RemoteInputActivity, which launches an activity
 * declaring com.google.android.wearable.action.LAUNCH_KEYBOARD — NOT the InputMethodService.
 * An IME that only implements InputMethodService is invisible in those flows.
 *
 * Shares KeyGridView with WearKeyImeService so both paths render identically (spec requirement:
 * "no forked UI"). RemoteInput result plumbing (returning composed text via setResult) lands in
 * Phase 1 alongside EditorState — Phase 0 only needs this activity to exist, be reachable, and
 * render the same surface for the frame-time measurement.
 */
class LaunchKeyboardActivity : Activity() {

    private var keyGridView: KeyGridView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val view = KeyGridView(this)
        keyGridView = view
        setContentView(view)

        // TODO(Phase 1): read RemoteInput.getResultsFromIntent-style extras from `intent`,
        // wire EditorState, and return composed text via setResult(RESULT_OK, resultIntent)
        // using RemoteInput.addResultsToIntent() before finish().
    }

    override fun onResume() {
        super.onResume()
        keyGridView?.startFrameTiming()
    }

    override fun onPause() {
        keyGridView?.stopFrameTiming()
        super.onPause()
    }
}
