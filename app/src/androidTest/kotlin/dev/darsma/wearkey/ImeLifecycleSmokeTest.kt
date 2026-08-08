package dev.darsma.wearkey

import android.content.res.Configuration
import android.view.inputmethod.EditorInfo
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Instrumented smoke test for the IME lifecycle: show / hide / config change (spec §9).
 *
 * ## Why these particular assertions
 *
 * The unit tests already cover `EditorState` thoroughly on the JVM. What they cannot cover is the
 * part that only exists on a device: whether the service survives the framework calling its
 * lifecycle methods in the orders that really occur, and whether state that must not leak between
 * fields actually does not. Every assertion here is about a transition, not about text editing
 * logic that is tested elsewhere.
 *
 * The field-to-field reset case is the one with real history: spec §11.5 requires that text never
 * carries between fields, and that is exactly the kind of bug that unit tests miss because it only
 * appears when the framework reuses a service instance.
 */
@RunWith(AndroidJUnit4::class)
class ImeLifecycleSmokeTest {

    private fun service(): WearKeyImeService {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // The service is constructed directly rather than bound: binding an IME requires it to be
        // the *selected* keyboard, which a test cannot arrange without changing a system setting
        // and leaving the device altered afterwards.
        return WearKeyImeService().apply {
            attachBaseContextForTest(context)
        }
    }

    private fun editorInfo(inputType: Int = EditorInfo.TYPE_CLASS_TEXT): EditorInfo =
        EditorInfo().apply {
            this.inputType = inputType
            imeOptions = EditorInfo.IME_ACTION_DONE
        }

    /**
     * InputMethodService owns a Dialog/Window and therefore must be created on the main Looper.
     * AndroidJUnitRunner invokes ordinary @Test methods on its instrumentation thread; running the
     * service there fails in framework setup before any WearKey assertion executes. Keep each
     * complete lifecycle scenario on the real UI thread, matching production callback affinity.
     */
    private fun onMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    @Test
    fun createsAnInputViewWithBothRequiredSurfaces() = onMain {
        val service = service()
        service.onCreate()
        val view = service.onCreateInputView()

        assertNotNull("onCreateInputView must return a view", view)
        val surface = view as dev.darsma.wearkey.uiwear.KeyboardSurfaceView
        // §4.5: one shared surface, used by both entry points. If either is missing the IME and the
        // launch activity have diverged.
        assertNotNull(surface.keyGrid)
        assertNotNull(surface.compositionStrip)
    }

    @Test
    fun showAndHideCyclesLeaveTheViewUsable() = onMain {
        val service = service()
        service.onCreate()
        service.onCreateInputView()

        // Three cycles, because a leak usually shows on the second or later show rather than the
        // first — the first is indistinguishable from a cold start.
        repeat(3) {
            service.onStartInputView(editorInfo(), false)
            service.onFinishInputView(false)
        }
        service.onStartInputView(editorInfo(), false)

        assertTrue("editor should be empty after clean cycles", service.editorTextForTest().isEmpty())
    }

    @Test
    fun textDoesNotCarryBetweenFields() = onMain {
        val service = service()
        service.onCreate()
        service.onCreateInputView()

        service.onStartInputView(editorInfo(), false)
        service.seedEditorForTest("hello")
        assertEquals("hello", service.editorTextForTest())

        // A different field opens — §11.5 requires a full reset, not a carry-over.
        service.onFinishInput()
        service.onStartInputView(editorInfo(), false)

        assertEquals("", service.editorTextForTest())
    }

    @Test
    fun maskedFieldNeverHoldsPlaintext() = onMain {
        val service = service()
        service.onCreate()
        service.onCreateInputView()

        val password = editorInfo(
            EditorInfo.TYPE_CLASS_TEXT or EditorInfo.TYPE_TEXT_VARIATION_PASSWORD
        )
        service.onStartInputView(password, false)
        service.seedEditorForTest("secret")

        val shown = service.editorTextForTest()
        assertFalse("masked field must not expose plaintext", shown.contains("secret"))
        assertEquals("bullet count should match typed length", 6, shown.length)
    }

    @Test
    fun survivesConfigurationChange() = onMain {
        val service = service()
        service.onCreate()
        service.onCreateInputView()
        service.onStartInputView(editorInfo(), false)
        service.seedEditorForTest("abc")

        // Wear devices do not rotate, but a configuration change still arrives on font-scale and
        // locale changes, and the framework path is the same one rotation would take.
        val config = Configuration(
            InstrumentationRegistry.getInstrumentation().targetContext.resources.configuration
        ).apply { fontScale *= 1.3f }
        service.onConfigurationChanged(config)

        // Restarting=true is what the framework passes after a config change, and it must not wipe
        // text the user has already entered.
        service.onStartInputView(editorInfo(), true)
        assertEquals("abc", service.editorTextForTest())
    }

    @Test
    fun repeatedInputViewCreationDoesNotAccumulateState() = onMain {
        val service = service()
        service.onCreate()

        repeat(3) {
            service.onCreateInputView()
            service.onStartInputView(editorInfo(), false)
            service.seedEditorForTest("x")
            service.onFinishInput()
        }

        service.onCreateInputView()
        service.onStartInputView(editorInfo(), false)
        assertEquals("", service.editorTextForTest())
    }
}
