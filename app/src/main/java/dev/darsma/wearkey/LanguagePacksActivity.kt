package dev.darsma.wearkey

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

/**
 * Lists add-on dictionaries and imports new ones from a file (spec §4.3).
 *
 * ## Why import is a document-picker intent
 *
 * The keyboard holds no storage permission and asks for none. `ACTION_OPEN_DOCUMENT` grants access
 * to exactly the one file the user chose, for as long as we read it — which is both less intrusive
 * than a storage permission and, on Wear, the only route that works without a companion app.
 *
 * The chosen file is copied into private storage rather than mapped where it lies; see
 * [LanguagePackManager.import] for why that matters.
 */
class LanguagePacksActivity : Activity() {

    private lateinit var manager: LanguagePackManager
    private lateinit var column: LinearLayout

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        manager = LanguagePackManager(this)

        val scroll = ScrollView(this).apply {
            setBackgroundColor(Color.BLACK)
            isVerticalScrollBarEnabled = false
            // Round display: the top and bottom of a rectangular list are clipped by the bezel.
            setPadding(pad(16), pad(40), pad(16), pad(40))
        }

        column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        scroll.addView(column)
        setContentView(scroll)

        render()
    }

    private fun render() {
        column.removeAllViews()
        column.addView(title(getString(R.string.packs_title)))

        column.addView(
            caption(getString(R.string.packs_bundled_note))
        )

        val packs = manager.available()
        if (packs.isEmpty()) {
            column.addView(caption(getString(R.string.packs_none)))
        } else {
            for (pack in packs) {
                val subtitle = when (pack.source) {
                    LanguagePack.Source.PACK -> getString(R.string.packs_source_installed)
                    LanguagePack.Source.IMPORTED -> getString(R.string.packs_source_imported)
                }
                column.addView(
                    row("${pack.displayName} · $subtitle") {
                        if (pack.source == LanguagePack.Source.IMPORTED) {
                            confirmRemove(pack)
                        } else {
                            toast(getString(R.string.packs_uninstall_hint))
                        }
                    }
                )
            }
        }

        column.addView(row(getString(R.string.packs_import)) { startImport() })
    }

    private fun startImport() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            // The index is a bespoke binary; no registered MIME type applies, so accept anything
            // and validate the bytes rather than trusting a filename or a picker-supplied type.
            type = "*/*"
        }
        runCatching { startActivityForResult(intent, REQUEST_IMPORT) }
            .onFailure { toast(getString(R.string.packs_no_picker)) }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != REQUEST_IMPORT || resultCode != RESULT_OK) return

        val uri = data?.data ?: return
        val bytes = runCatching {
            contentResolver.openInputStream(uri)?.use { it.readBytes() }
        }.getOrNull()

        if (bytes == null) {
            toast(getString(R.string.packs_import_unreadable))
            return
        }
        if (bytes.size > MAX_IMPORT_BYTES) {
            // A dictionary far larger than the bundled ones is either not a dictionary or would
            // blow the memory budget once mapped. Refuse before writing it to storage.
            toast(getString(R.string.packs_import_too_large))
            return
        }

        val tag = suggestedTag(uri)
        if (tag == null) {
            toast(getString(R.string.packs_import_bad_name))
            return
        }

        val stored = manager.import(tag, bytes)
        if (stored == null) {
            toast(getString(R.string.packs_import_invalid))
        } else {
            toast(getString(R.string.packs_import_ok, tag))
            render()
        }
    }

    /**
     * Derives a language tag from the chosen file's name, e.g. `nl.bin` → `nl`.
     *
     * Returns null when the name is not a plain tag. Guessing harder here would be worse than
     * refusing: a wrong tag silently associates a dictionary with the wrong language, which is far
     * more confusing than being asked to rename the file.
     */
    private fun suggestedTag(uri: android.net.Uri): String? {
        val name = queryDisplayName(uri) ?: uri.lastPathSegment ?: return null
        val base = name.substringAfterLast('/').removeSuffix(".bin")
        return if (base.matches(TAG_PATTERN)) base else null
    }

    private fun queryDisplayName(uri: android.net.Uri): String? = runCatching {
        contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            val index = cursor.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (index >= 0 && cursor.moveToFirst()) cursor.getString(index) else null
        }
    }.getOrNull()

    private fun confirmRemove(pack: LanguagePack) {
        // Two taps rather than a dialog: on a 466 px round display an AlertDialog covers the whole
        // screen and its buttons land under the bezel. Tapping again within the window confirms.
        if (pendingRemoval == pack.languageTag) {
            pendingRemoval = null
            if (manager.removeImported(pack.languageTag)) {
                toast(getString(R.string.packs_removed, pack.displayName))
                render()
            }
        } else {
            pendingRemoval = pack.languageTag
            toast(getString(R.string.packs_confirm_remove))
        }
    }

    private var pendingRemoval: String? = null

    // ---- small view helpers, matching SettingsActivity's visual language ----

    private fun title(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 16f)
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, pad(12))
    }

    private fun caption(text: String): TextView = TextView(this).apply {
        this.text = text
        setTextColor(Color.parseColor("#9E9E9E"))
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        gravity = Gravity.CENTER
        setPadding(0, 0, 0, pad(12))
    }

    private fun row(label: String, onClick: () -> Unit): View = TextView(this).apply {
        text = label
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
        gravity = Gravity.CENTER
        setPadding(pad(12), pad(14), pad(12), pad(14))
        setBackgroundColor(Color.parseColor("#1C1C1E"))
        isClickable = true
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = pad(8) }
    }

    private fun pad(dp: Int): Int = (dp * resources.displayMetrics.density).toInt()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }

    companion object {
        private const val REQUEST_IMPORT = 1001

        /**
         * Upper bound on an imported index. The bundled 10k-word indexes are ~1.5 MB, so 24 MB is
         * far beyond any legitimate pack while still refusing a file that would exhaust memory when
         * read into a byte array for validation.
         */
        private const val MAX_IMPORT_BYTES = 24 * 1024 * 1024

        private val TAG_PATTERN = Regex("^[A-Za-z]{2,3}(-[A-Za-z0-9]{2,8})*$")
    }
}
