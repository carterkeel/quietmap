package app.quietmap

import android.Manifest
import android.content.Intent
import android.os.Build
import android.util.Log
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
import java.io.ByteArrayOutputStream

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

    private companion object {
        const val TAG = "QuietmapTest"
    }

    private val device = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private fun waitFor(text: String, timeoutMs: Long = 20_000): UiObject2 =
        device.wait(Until.findObject(By.text(text)), timeoutMs) ?: run {
            logScreen()
            error("\"$text\" didn't appear within ${timeoutMs / 1000} s")
        }

    /**
     * Taps [label] until [expected] shows up, up to three times. A slow CI emulator sometimes puts a
     * system dialog (such as "isn't responding") over the app, which swallows the first tap.
     */
    private fun tapUntil(label: String, expected: String) {
        repeat(3) {
            dismissSystemDialogs()
            device.wait(Until.findObject(By.text(label)), 10_000)?.click()
            if (device.wait(Until.hasObject(By.text(expected)), 10_000)) return
        }
        waitFor(expected, 1_000)
    }

    private fun dismissSystemDialogs() {
        listOf("Wait", "OK").forEach { button ->
            device.findObject(By.pkg("android").text(button))?.let {
                Log.e(TAG, "dismissing a system dialog with \"$button\"")
                it.click()
                device.waitForIdle()
            }
        }
    }

    /** Logs the texts and descriptions on screen, so a failed step in CI shows what was there instead. */
    private fun logScreen() {
        val out = ByteArrayOutputStream()
        runCatching { device.dumpWindowHierarchy(out) }
        fun attr(node: String, name: String) = Regex(""" $name="([^"]*)"""").find(node)?.groupValues?.get(1).orEmpty()
        out.toString().split("<node").asSequence()
            .map { node -> attr(node, "package") to listOf(attr(node, "text"), attr(node, "content-desc")).filter { it.isNotBlank() } }
            .filter { it.second.isNotEmpty() }
            .map { (pkg, labels) -> "$pkg: ${labels.joinToString(" / ")}" }
            .distinct()
            .take(40)
            .forEach { Log.e(TAG, "on screen: $it") }
    }

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
        tapUntil("Sharing", expected = "Paused")
        tapUntil("Paused", expected = "Sharing")

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
