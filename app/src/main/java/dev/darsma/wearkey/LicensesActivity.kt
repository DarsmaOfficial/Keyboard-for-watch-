package dev.darsma.wearkey

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * "Open source licenses" screen (spec §3.2). Displays the full third-party licence texts,
 * bundled as assets and read entirely offline — the BSD terms among our (future) dependencies
 * require the notice to travel with the binary, which a web link would not satisfy, and the app
 * has no network permission regardless.
 */
class LicensesActivity : Activity() {

    private val files = listOf(
        "THIRD_PARTY_LICENSES.md" to "Third-party licences",
        "Apache-2.0.txt" to "Apache License 2.0",
        "NOTICE.txt" to "NOTICE"
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(56), dp(16), dp(56))
        }
        scroll.addView(column)

        column.addView(TextView(this).apply {
            text = getString(R.string.settings_licenses)
            setTextColor(Color.WHITE)
            textSize = 18f
            setTypeface(typeface, Typeface.BOLD)
            gravity = Gravity.CENTER
            setPadding(0, 0, 0, dp(16))
        })

        for ((asset, heading) in files) {
            column.addView(sectionButton(heading, asset, ::showAsset))
        }

        setContentView(scroll)
    }

    private var contentView: TextView? = null

    private fun sectionButton(heading: String, asset: String, onClick: (String, String) -> Unit): View {
        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()
        return Button(this).apply {
            text = heading
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1C1C1E"))
            isAllCaps = false
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = dp(8) }
            layoutParams = lp
            setOnClickListener { onClick(heading, asset) }
        }
    }

    private fun showAsset(heading: String, asset: String) {
        val text = runCatching {
            assets.open("licenses/$asset").bufferedReader().use { it.readText() }
        }.getOrElse { "Unable to load $asset" }

        val density = resources.displayMetrics.density
        fun dp(v: Int) = (v * density).toInt()

        val scroll = ScrollView(this).apply { setBackgroundColor(Color.BLACK) }
        val tv = TextView(this).apply {
            this.text = "$heading\n\n$text"
            setTextColor(Color.parseColor("#D0D0D0"))
            textSize = 11f
            typeface = Typeface.MONOSPACE
            setTextIsSelectable(true)
            setPadding(dp(16), dp(48), dp(16), dp(56))
        }
        scroll.addView(tv)
        contentView = tv
        setContentView(scroll)
    }

    override fun onBackPressed() {
        // If we're viewing a specific licence, go back to the list rather than leaving.
        if (contentView != null) {
            contentView = null
            recreate()
        } else {
            super.onBackPressed()
        }
    }
}
