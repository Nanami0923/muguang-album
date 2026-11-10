package com.muguang.album.integration

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.muguang.album.MainActivity

/**
 * Developer-shell launch avoids OEM restrictions on an app UID launching its own UI from
 * background instrumentation. It grants no system permissions and changes no device settings.
 */
class ShellActivitySession private constructor(private var activity: MainActivity?) : AutoCloseable {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()

    fun onActivity(action: (MainActivity) -> Unit) {
        val resumed = awaitResumedActivity()
        instrumentation.runOnMainSync {
            check(!resumed.isFinishing && !resumed.isDestroyed) { "The test activity has already finished" }
            activity = resumed
            action(resumed)
        }
    }

    override fun close() {
        instrumentation.runOnMainSync {
            activity?.let { ownedActivity ->
                if (!ownedActivity.isDestroyed && !ownedActivity.isFinishing) ownedActivity.finishAndRemoveTask()
            }
            activity = null
        }
        instrumentation.waitForIdleSync()
    }

    companion object {
        fun launch(): ShellActivitySession {
            val output = runShell(
                "am start -W -a android.intent.action.MAIN -c android.intent.category.LAUNCHER " +
                    "-n com.muguang.album/.MainActivity",
            )
            return try {
                ShellActivitySession(awaitResumedActivity())
            } catch (failure: AssertionError) {
                throw AssertionError("MainActivity did not resume after developer-shell launch.\n$output", failure)
            }
        }

        /** Only starts the instrumentation APK's own no-display fixture-grant activity. */
        fun grantSyntheticFolders(revoke: Boolean = false) {
            val testPackage = InstrumentationRegistry.getInstrumentation().context.packageName
            runShell(
                "am start -n $testPackage/${SyntheticGrantActivity::class.java.name} --ez revoke $revoke",
            )
        }

        private fun awaitResumedActivity(timeoutMs: Long = 15_000): MainActivity {
            val instrumentation = InstrumentationRegistry.getInstrumentation()
            val deadline = SystemClock.elapsedRealtime() + timeoutMs
            do {
                var found: MainActivity? = null
                instrumentation.runOnMainSync {
                    found = ActivityLifecycleMonitorRegistry.getInstance()
                        .getActivitiesInStage(Stage.RESUMED)
                        .filterIsInstance<MainActivity>()
                        .firstOrNull { !it.isFinishing && !it.isDestroyed }
                }
                found?.let { return it }
                SystemClock.sleep(40)
            } while (SystemClock.elapsedRealtime() < deadline)
            throw AssertionError("No resumed MainActivity was reported by the test lifecycle monitor")
        }

        private fun runShell(command: String): String {
            val descriptor = InstrumentationRegistry.getInstrumentation().uiAutomation.executeShellCommand(command)
            return ParcelFileDescriptor.AutoCloseInputStream(descriptor).bufferedReader().use { it.readText() }
        }
    }
}
