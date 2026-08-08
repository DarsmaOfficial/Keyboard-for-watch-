package dev.darsma.wearkey

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.darsma.wearkey.uiwear.CalibrationView

/**
 * Touch calibration for the §7.1 drift model.
 *
 * Runs the tap collection, shows the result, and only writes it when the user accepts. Two
 * deliberate refusals here:
 *
 * - **A fit that does not reduce aiming error is not offered for adoption.** The maths will always
 *   return *some* parameters; adopting them regardless would be worse than the defaults.
 * - **Nothing is saved without confirmation.** Calibration changes how every future tap resolves,
 *   so silently applying a bad session would leave the user with a keyboard that got worse for no
 *   visible reason and no obvious way back.
 *
 * Reset to defaults is always available, for exactly that reason.
 */
class CalibrationActivity : Activity() {

    private lateinit var root: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.BLACK)
            gravity = Gravity.CENTER
        }
        setContentView(root)
        showIntro()
    }

    private fun showIntro() {
        // A result that was computed but never accepted takes priority over the intro — the user
        // already did the work, so offer it rather than asking them to repeat it.
        if (showPendingIfAny()) return

        root.removeAllViews()
        root.addView(label(getString(R.string.calibration_title), 15f, bold = true))
        root.addView(label(getString(R.string.calibration_intro), 12f))
        root.addView(button(getString(R.string.calibration_start)) { startCollection() })
        if (SettingsStore(this).hasTouchCalibration) {
            root.addView(button(getString(R.string.calibration_reset)) {
                SettingsStore(this).clearTouchCalibration()
                showMessage(getString(R.string.calibration_reset_done))
            })
        }
    }

    private fun startCollection() {
        val view = CalibrationView(this)
        view.onCompleteListener = CalibrationView.OnCompleteListener { fit, _ ->
            runOnUiThread {
                // Stash the fit immediately as *pending*, before showing anything.
                //
                // A calibration session is 25 deliberate taps and about a minute of the user's
                // attention. Holding that result only in memory means the watch sleeping, a
                // notification stealing focus, or the process being reclaimed silently throws all
                // of it away — which is exactly what happened on the first real session: the taps
                // were made, no crash occurred, and nothing was saved.
                //
                // Pending is not the same as applied: it changes no typing behaviour until the
                // user accepts it. It only guarantees the work survives long enough to be offered.
                val store = SettingsStore(this)
                if (fit?.isImprovement == true) {
                    store.savePendingCalibration(
                        fit.maxRadialDriftPx,
                        fit.driftExponent,
                        fit.improvementPercent
                    )
                } else {
                    // A non-improving fit is rejected, not merely hidden on this screen. Keeping
                    // it pending would offer an Apply button after reopening calibration and let
                    // the user install the exact correction we just proved makes taps worse.
                    store.clearPendingCalibration()
                }
                showResult(fit)
            }
        }
        setContentView(view)
    }

    /**
     * Offers a fit that was computed but never accepted — see [startCollection] for why one can
     * exist. Shown on entry so a lost result is one tap from being applied, not 25.
     */
    private fun showPendingIfAny(): Boolean {
        val store = SettingsStore(this)
        val drift = store.pendingDriftPx ?: return false
        val exponent = store.pendingDriftExponent ?: return false
        val improvement = store.pendingImprovementPercent
        if (improvement <= 0f) {
            // Defensive cleanup for old builds and interrupted sessions. A pending record is not
            // trustworthy merely because all three keys exist; only a measured gain is adoptable.
            store.clearPendingCalibration()
            return false
        }

        root.removeAllViews()
        root.addView(label(getString(R.string.calibration_pending_title), 14f, bold = true))
        root.addView(label(getString(R.string.calibration_improvement, improvement), 12f))
        root.addView(button(getString(R.string.calibration_apply)) {
            store.saveTouchCalibration(drift, exponent)
            store.clearPendingCalibration()
            showMessage(getString(R.string.calibration_applied))
        })
        root.addView(button(getString(R.string.calibration_start)) {
            store.clearPendingCalibration()
            startCollection()
        })
        root.addView(button(getString(R.string.calibration_close)) { finish() })
        return true
    }

    private fun showResult(fit: dev.darsma.wearkey.imecore.touch.CalibrationFit?) {
        setContentView(root)
        root.removeAllViews()

        if (fit == null) {
            root.addView(label(getString(R.string.calibration_failed), 13f, bold = true))
            root.addView(label(getString(R.string.calibration_failed_detail), 11f))
            root.addView(button(getString(R.string.calibration_retry)) { startCollection() })
            root.addView(button(getString(R.string.calibration_close)) { finish() })
            return
        }

        root.addView(label(getString(R.string.calibration_done), 14f, bold = true))
        root.addView(
            label(
                getString(
                    R.string.calibration_error_summary,
                    fit.meanAbsErrorBefore,
                    fit.meanAbsErrorAfter
                ),
                11f
            )
        )

        if (fit.isImprovement) {
            root.addView(
                label(getString(R.string.calibration_improvement, fit.improvementPercent), 12f)
            )
            root.addView(button(getString(R.string.calibration_apply)) {
                val store = SettingsStore(this)
                store.saveTouchCalibration(fit.maxRadialDriftPx, fit.driftExponent)
                store.clearPendingCalibration()
                showMessage(getString(R.string.calibration_applied))
            })
        } else {
            // Honest outcome: the session produced no usable improvement. Say so rather than
            // dressing it up — the defaults are already reasonable.
            root.addView(label(getString(R.string.calibration_no_gain), 11f))
        }
        root.addView(button(getString(R.string.calibration_retry)) { startCollection() })
        root.addView(button(getString(R.string.calibration_close)) { finish() })
    }

    private fun showMessage(text: String) {
        root.removeAllViews()
        root.addView(label(text, 13f, bold = true))
        root.addView(button(getString(R.string.calibration_close)) { finish() })
    }

    private fun label(text: String, sizeSp: Float, bold: Boolean = false): TextView =
        TextView(this).apply {
            this.text = text
            setTextColor(if (bold) Color.WHITE else Color.parseColor("#B0B0B0"))
            textSize = sizeSp
            gravity = Gravity.CENTER
            val pad = (10 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad / 3, pad, pad / 3)
            if (bold) setTypeface(typeface, android.graphics.Typeface.BOLD)
        }

    private fun button(text: String, onClick: () -> Unit): View =
        Button(this).apply {
            this.text = text
            setOnClickListener { onClick() }
        }
}
