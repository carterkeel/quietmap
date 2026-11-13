package app.quietmap

import android.Manifest
import android.content.Intent
import android.os.Build
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import androidx.test.uiautomator.Until
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Smoke test of the first-run path, driven through the real screen like a person would (UI
 * Automator), so the app runs exactly as on a phone: enter a name, create a circle, keep running
 * with the map, people sheet and location service up, then open the invite screen.
 *
 * A crash anywhere kills the app process and fails the test; CI saves the stack trace from logcat
 * to the ci-logs branch.
 */
@RunWith(AndroidJUnit4::class)
class CreateCircleTest {
    @get:Rule
    val permissions: GrantPermissionRule = GrantPermissionRule.grant(
        *buildList {
            add(Manifest.permission.ACCESS_FINE_LOCATION)
            add(Manifest.permission.ACCESS_COARSE_LOCATION)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(Manifest.permission.POST_NOTIFICATIONS)
        }.toTypedArray(),
    )

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun waitFor(text: String, timeoutMs: Long = 20_000): UiObject2 =
        device.wait(Until.findObject(By.text(text)), timeoutMs) ?: error("\"$text\" didn't appear within ${timeoutMs / 1000} s")

    @Test
    fun setupCreateCircleAndUseTheMainScreens() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK),
        )

        // Setup, step 1: name.
        waitFor("What should people call you?")
        val nameField = device.wait(Until.findObject(By.clazz("android.widget.EditText")), 10_000) ?: error("no name field")
        nameField.click()
        nameField.text = "Tester"
        device.pressBack() // close the keyboard
        waitFor("Continue").click()

        // Step 2: photo (skip).
        waitFor("Add a profile photo")
        waitFor("Skip").click()

        // Step 3: create a circle.
        waitFor("Create a circle").click()
        waitFor("Create").click()

        // Step 4: location (already granted by the test rule).
        waitFor("Open the map").click()

        // The home screen with the map and people sheet appears and keeps running.
        waitFor("Invite", 30_000)
        Thread.sleep(25_000)
        assertNotNull("Home screen is gone after 25 s (did the app crash?)", device.findObject(By.text("Invite")))

        // Sharing toggle on the map.
        waitFor("Sharing").click()
        waitFor("Paused")
        waitFor("Paused").click()
        waitFor("Sharing")

        // Invite screen with a QR code.
        device.findObject(By.text("Invite")).click()
        waitFor("Create invite").click()
        waitFor("Share link")
        device.pressBack()

        // Settings, then back to the map.
        waitFor("Invite")
        (device.wait(Until.findObject(By.desc("Settings")), 10_000) ?: error("no Settings button")).click()
        waitFor("Share my location")
        device.pressBack()
        waitFor("Invite")
        Thread.sleep(3_000)
    }
}
