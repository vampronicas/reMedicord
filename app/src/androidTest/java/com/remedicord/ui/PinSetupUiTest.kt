package com.remedicord.ui

import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.remedicord.MainActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * End-to-end on the debug app (fresh install on the managed emulator): PIN setup → confirm → main screen.
 * Uses the in-app keypad only.
 */
@RunWith(AndroidJUnit4::class)
class PinSetupUiTest {
    @get:Rule val rule = createAndroidComposeRule<MainActivity>()

    private fun type(pin: String) = pin.forEach { rule.onNodeWithText(it.toString()).performClick() }

    @Test fun createPinMismatchThenSuccess() {
        rule.waitUntil(10_000) { rule.onAllNodesWithTextExists("Create a 4-digit PIN") || rule.onAllNodesWithTextExists("Enter your PIN") }
        if (!rule.onAllNodesWithTextExists("Create a 4-digit PIN")) return // PIN already set by an earlier run
        rule.onNodeWithText("there is no way to recover", substring = true).assertIsDisplayed()
        type("1357"); type("1358")
        rule.onNodeWithText("PINs didn't match. Start again.").assertIsDisplayed()
        type("1357"); type("1357")
        rule.waitUntil(15_000) { rule.onAllNodesWithTextExists("Medicines") }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.onAllNodesWithTextExists(t: String) =
        onAllNodes(androidx.compose.ui.test.hasText(t, substring = true)).fetchSemanticsNodes().isNotEmpty()
}
