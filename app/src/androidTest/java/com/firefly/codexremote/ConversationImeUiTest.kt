package com.firefly.codexremote

import android.content.Context
import android.view.WindowInsets
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import androidx.activity.ComponentActivity
import androidx.activity.enableEdgeToEdge
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import kotlin.math.abs

class ConversationImeUiTest {
    @get:Rule
    val compose = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun useAppImeWindowPolicy() {
        compose.runOnUiThread {
            compose.activity.enableEdgeToEdge()
            compose.activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
    }

    @Test
    fun composerTracksRealImeAndReturnsToBottomWhenHidden() {
        val conversation = ConversationState(codexId = "C", historyComplete = true)
        val core = CoreState(
            phase = "ready",
            codexes = listOf(CodexSummary("C", "测试会话", "/work", "IDLE")),
            conversation = conversation,
        )
        compose.setContent {
            var draft by remember { mutableStateOf("") }
            MaterialTheme {
                CodexRemoteScreen(
                    state = AppUiState(core = core, openCodexId = "C", draft = draft),
                    onHostAddressChanged = {}, onConnect = {}, onRefresh = {}, onOpenAuth = {},
                    onOpenConversation = {}, onCloseConversation = {}, onDraftChanged = { draft = it },
                    onSend = {}, onStop = {},
                )
            }
        }

        compose.onNodeWithTag("conversation-input").performClick().performTextInput("hello")
        compose.waitUntil(timeoutMillis = 8_000) { imeVisible() && imeBottomInset() > 0 }
        compose.waitForIdle()

        val rootVisible = bounds("conversation-root")
        val historyVisible = bounds("conversation-history")
        val composerVisible = bounds("conversation-composer")
        val inputVisible = bounds("conversation-input")
        val imeBottom = imeBottomInset()
        val decorHeight = compose.activity.window.decorView.height
        val imeTop = decorHeight - imeBottom
        val density = density()
        val visibleMeasurements = "root=$rootVisible composer=$composerVisible input=$inputVisible " +
            "decorHeight=$decorHeight imeBottom=$imeBottom imeTop=$imeTop density=$density"

        assertTrue(
            "composer must end at IME top; $visibleMeasurements",
            abs(imeTop - composerVisible.bottom) <= 4 * density,
        )
        assertTrue(
            "input bottom gap must stay near the 12dp composer padding; $visibleMeasurements",
            imeTop - inputVisible.bottom in (7 * density)..(17 * density),
        )
        assertTrue(
            "history must retain positive remaining height; history=$historyVisible $visibleMeasurements",
            historyVisible.height > inputVisible.height,
        )
        assertTrue(
            "history must stay above composer; history=$historyVisible $visibleMeasurements",
            historyVisible.bottom <= composerVisible.top + density,
        )

        compose.runOnUiThread {
            val decor = compose.activity.window.decorView
            val inputMethodManager = compose.activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager
            inputMethodManager.hideSoftInputFromWindow(decor.windowToken, 0)
        }
        compose.waitUntil(timeoutMillis = 8_000) {
            !imeVisible() && abs(decorHeight() - bounds("conversation-composer").bottom) <= 4 * density()
        }
        compose.waitForIdle()

        val rootHidden = bounds("conversation-root")
        val composerHidden = bounds("conversation-composer")
        val inputHidden = bounds("conversation-input")
        val hiddenWindowBottom = decorHeight()
        val hiddenMeasurements = "root=$rootHidden composer=$composerHidden input=$inputHidden " +
            "decorHeight=$hiddenWindowBottom density=$density"
        assertTrue(
            "composer must return to window bottom; $hiddenMeasurements",
            abs(hiddenWindowBottom - composerHidden.bottom) <= 4 * density,
        )
        assertTrue(
            "hidden IME must leave only the normal composer padding; $hiddenMeasurements",
            hiddenWindowBottom - inputHidden.bottom in (7 * density)..(17 * density),
        )
    }

    private fun bounds(tag: String): Rect = compose.onNodeWithTag(tag).fetchSemanticsNode().boundsInWindow

    private fun imeVisible(): Boolean {
        var visible = false
        compose.runOnUiThread {
            visible = compose.activity.window.decorView.rootWindowInsets
                ?.isVisible(WindowInsets.Type.ime()) == true
        }
        return visible
    }

    private fun imeBottomInset(): Int {
        var bottom = 0
        compose.runOnUiThread {
            bottom = compose.activity.window.decorView.rootWindowInsets
                ?.getInsets(WindowInsets.Type.ime())?.bottom ?: 0
        }
        return bottom
    }

    private fun density(): Float = compose.activity.resources.displayMetrics.density

    private fun decorHeight(): Int {
        var height = 0
        compose.runOnUiThread { height = compose.activity.window.decorView.height }
        return height
    }
}
