package dev.darsma.wearkey

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView

/**
 * First-run tutorial (spec §11.5 / §13: "a gesture-driven keyboard is unusable without one").
 *
 * ## Why this exists at all
 *
 * Most of this keyboard's capability is invisible. Nothing on screen suggests that dragging across
 * letters types a word, that the strip above the keys is tappable to move the caret, or that
 * long-pressing gives accents. On a phone a user might discover these by accident; on a watch,
 * where every interaction is deliberate and brief, undiscovered features are simply absent.
 *
 * ## Why static cards rather than an interactive walkthrough
 *
 * An interactive tutorial would have to host a live keyboard, which means either duplicating the
 * IME's input plumbing into an Activity — the exact forked-UI failure §4.5 exists to prevent — or
 * driving a real InputConnection from a fake field. Both add a second code path through the most
 * safety-critical part of the app to teach a lesson that a diagram already conveys. Cards are
 * honest about being an explanation rather than pretending to be practice.
 *
 * Shown once, then never again unless invoked from Settings.
 */
class TutorialActivity : Activity() {

    private data class Page(val titleRes: Int, val bodyRes: Int, val glyph: String)

    private val pages = listOf(
        Page(R.string.tutorial_swipe_title, R.string.tutorial_swipe_body, "⌇"),
        Page(R.string.tutorial_strip_title, R.string.tutorial_strip_body, "⌶"),
        Page(R.string.tutorial_longpress_title, R.string.tutorial_longpress_body, "áö"),
        Page(R.string.tutorial_language_title, R.string.tutorial_language_body, "文A"),
        Page(R.string.tutorial_privacy_title, R.string.tutorial_privacy_body, "⛨")
    )

    private var index = 0
    private lateinit var container: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            // Generous horizontal inset: on a round display the corners of a full-width text block
            // fall outside the glass entirely.
            setPadding(pad(28), pad(24), pad(28), pad(24))
        }
        setContentView(container)
        render()
    }

    private fun render() {
        container.removeAllViews()
        val page = pages[index]

        container.addView(TextView(this).apply {
            text = page.glyph
            setTextColor(Color.parseColor("#00E5FF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 30f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad(10))
        })

        container.addView(TextView(this).apply {
            text = getString(page.titleRes)
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad(8))
        })

        container.addView(TextView(this).apply {
            text = getString(page.bodyRes)
            setTextColor(Color.parseColor("#BDBDBD"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad(16))
        })

        container.addView(TextView(this).apply {
            text = "${index + 1} / ${pages.size}"
            setTextColor(Color.parseColor("#616161"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, pad(12))
        })

        val isLast = index == pages.size - 1
        container.addView(button(getString(if (isLast) R.string.tutorial_done else R.string.tutorial_next)) {
            if (isLast) finishTutorial() else {
                index++
                render()
            }
        })

        if (!isLast) {
            container.addView(textButton(getString(R.string.tutorial_skip)) { finishTutorial() })
        }
    }

    private fun finishTutorial() {
        SettingsStore(this).markTutorialSeen()
        finish()
    }

    private fun button(label: String, onClick: () -> Unit): View = TextView(this).apply {
        text = label
        setTextColor(Color.BLACK)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(pad(20), pad(12), pad(20), pad(12))
        setBackgroundColor(Color.parseColor("#00E5FF"))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        )
    }

    private fun textButton(label: String, onClick: () -> Unit): View = TextView(this).apply {
        text = label
        setTextColor(Color.parseColor("#9E9E9E"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        setPadding(0, pad(12), 0, 0)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun pad(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    companion object {
        /**
         * Launches the tutorial if it has not been seen.
         *
         * Called from [SettingsActivity] rather than from the IME: an InputMethodService must never
         * start an Activity while the user is trying to type into another app — it would steal
         * focus from the very field they are filling in, which is worse than never teaching them.
         */
        fun launchIfFirstRun(activity: Activity) {
            val store = SettingsStore(activity)
            if (store.hasSeenTutorial()) return
            activity.startActivity(Intent(activity, TutorialActivity::class.java))
        }
    }
}
