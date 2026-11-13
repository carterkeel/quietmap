package app.quietmap

import android.Manifest
import android.os.Build
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test of the first-run path: enter a name, create a circle, and keep running with the map,
 * the people sheet and the location service up. Any crash on the way fails the test, and CI saves
 * the stack trace from logcat to the ci-logs branch.
 */
@RunWith(AndroidJUnit4::class)
class CreateCircleTest {
    @get:Rule(order = 0)
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    @get:Rule(order = 1)
    val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun createCircleShowsTheMapAndKeepsRunning() {
        compose.onNodeWithText("Your name").performTextInput("Tester")
        compose.onNodeWithText("Create a circle").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Circle name").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Create").performClick()

        // The home screen with the people sheet appears.
        compose.waitUntil(20_000) { compose.onAllNodesWithText("Invite").fetchSemanticsNodes().isNotEmpty() }

        // Let the map load, the sharing service start and a first update publish.
        Thread.sleep(20_000)
        compose.onNodeWithText("Invite").assertExists()

        // Open and close the main screens.
        compose.onNodeWithText("Invite").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Create invite").fetchSemanticsNodes().isNotEmpty() }
        compose.onNodeWithText("Create invite").performClick()
        compose.waitUntil(10_000) { compose.onAllNodesWithText("Share link").fetchSemanticsNodes().isNotEmpty() }
        Thread.sleep(3_000)
    }
}
