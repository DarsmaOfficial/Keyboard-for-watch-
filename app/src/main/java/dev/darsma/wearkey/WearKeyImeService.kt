package dev.darsma.wearkey

import android.inputmethodservice.InputMethodService
import android.view.View
import dev.darsma.wearkey.uiwear.KeyGridView

/**
 * Phase 0 scaffold IME. Handles the classic InputMethodService path (plain EditText fields).
 *
 * Wear OS also routes input through LAUNCH_KEYBOARD-launched activities for notification
 * replies / WhatsApp / browser fields (spec §4.5) — see LaunchKeyboardActivity, which shares
 * this same KeyGridView so both paths render identically.
 *
 * No EditorState / InputConnection wiring yet — that's Phase 1. Phase 0 only needs to prove
 * the surface renders and measure frame time.
 */
class WearKeyImeService : InputMethodService() {

    private var keyGridView: KeyGridView? = null

    override fun onCreateInputView(): View {
        val view = KeyGridView(this)
        keyGridView = view
        return view
    }

    override fun onStartInputView(info: android.view.inputmethod.EditorInfo?, restarting: Boolean) {
        super.onStartInputView(info, restarting)
        // Frame-time instrumentation left ON only for Phase 0 measurement; remove/gate behind
        // a debug flag once Phase 0's exit criterion is confirmed and recorded.
        keyGridView?.startFrameTiming()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        keyGridView?.stopFrameTiming()
        super.onFinishInputView(finishingInput)
    }
}
