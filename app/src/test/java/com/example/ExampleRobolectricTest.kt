package com.example

import android.content.Context
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

    @get:Rule
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Test
    fun `app name matches PhoneBridge`() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        assertEquals("PhoneBridge", context.getString(R.string.app_name))
    }

    @Test
    fun `bottom navigation switches across Bridge Events Pairing and Diagnostics screens`() {
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("overview_pair_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_events").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("events_action_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_pairing").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("pairing_list").performScrollToNode(hasTestTag("initiate_pairing_button"))
        composeRule.onNodeWithTag("initiate_pairing_button").assertIsDisplayed()

        composeRule.onNodeWithTag("nav_system").performClick()
        composeRule.waitForIdle()
        composeRule.onNodeWithTag("refresh_diagnostics_button").assertIsDisplayed()
    }
}
