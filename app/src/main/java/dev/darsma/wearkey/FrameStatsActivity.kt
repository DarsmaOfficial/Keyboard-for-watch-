package dev.darsma.wearkey

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/**
 * Reads back the key grid's draw-time percentiles (spec §14 frame-time gate).
 *
 * ## Why a screen rather than a log line
 *
 * The obvious way to surface these numbers is `Log.d`. The build forbids it: any logging call in
 * keyboard source fails CI, because a keyboard must never log anything near what the user types.
 * That rule is worth more than the convenience, and it holds only if it has no exceptions.
 *
 * So the measurement is displayed instead. It is also strictly better for the purpose — the numbers
 * are read after a *real* typing session, on the device that produced them, with no risk of
 * confusing one process's cumulative counters for another's.
 *
 * ## Why not dumpsys gfxinfo
 *
 * gfxinfo is cumulative over the whole process lifetime and includes cold start, window creation
 * and layout inflation. On this watch it reported a 95th percentile of 26 ms while its own modern
 * jank counter said 0.45% and its legacy counter said 44.2%. Three numbers that cannot all describe
 * steady-state typing. Timing `onDraw` directly answers the question the gate actually asks.
 */
class FrameStatsActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var output: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundColor(Color.BLACK)
            val pad = (12 * resources.displayMetrics.density).toInt()
            setPadding(pad, pad, pad, pad)
        }
        output = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 11f
            gravity = Gravity.CENTER
        }

        root.addView(header(getString(R.string.frame_stats_title)))
        root.addView(output)
        root.addView(Button(this).apply {
            text = getString(R.string.frame_stats_start)
            setOnClickListener {
                WearKeyImeService.startFrameTiming()
                output.text = getString(R.string.frame_stats_recording)
            }
        })
        root.addView(Button(this).apply {
            text = getString(R.string.frame_stats_read)
            setOnClickListener { showStats() }
        })

        setContentView(root)
        showStats()
    }

    private fun showStats() {
        val stats = WearKeyImeService.frameStats()
        output.text = if (stats == null) {
            getString(R.string.frame_stats_none)
        } else {
            buildString {
                append(getString(R.string.frame_stats_samples, stats.sampleCount)).append('\n')
                append("median  ${round(stats.medianMs)} ms\n")
                append("p90     ${round(stats.p90Ms)} ms\n")
                append("p95     ${round(stats.p95Ms)} ms\n")
                append("p99     ${round(stats.p99Ms)} ms\n")
                append("worst   ${round(stats.worstMs)} ms\n")
                append("over 16.6 ms: ${round(stats.overBudgetPercent)}%\n")
                append(if (stats.meetsSpecGate) "GATE: PASS" else "GATE: FAIL")
            }
        }
    }

    private fun round(value: Float): String = (kotlin.math.round(value * 100) / 100f).toString()

    private fun header(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        textSize = 14f
        gravity = Gravity.CENTER
        setTypeface(typeface, android.graphics.Typeface.BOLD)
    }
}
