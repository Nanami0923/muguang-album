package com.muguang.album.ui

import android.os.ParcelFileDescriptor
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Starts only the explicitly supplied test/target component through the development shell.
 * Some devices reject ActivityScenario's instrumented app-UID launch as a background launch.
 * This uses the same normal activity launch as `adb shell am start`, without changing settings
 * or requesting any background-launch permission. Lifecycle access stays on the main thread.
 */
class ShellActivityRule<A : ComponentActivity>(
    private val activityClass: Class<A>,
    private val timeoutMillis: Long = 15_000,
) : TestRule {
    private val instrumentation get() = InstrumentationRegistry.getInstrumentation()
    private var launchedActivity: A? = null

    val activity: A
        get() = checkNotNull(launchedActivity) { "The shell activity has not reached RESUMED" }

    override fun apply(base: Statement, description: Description): Statement = object : Statement() {
        override fun evaluate() {
            try {
                launch()
                base.evaluate()
            } finally {
                close()
            }
        }
    }

    fun launch() {
        check(launchedActivity == null) { "This rule already owns an activity" }
        val targetPackage = instrumentation.targetContext.packageName
        val className = activityClass.name
        check(targetPackage.matches(Regex("[A-Za-z0-9_.]+")) && className.matches(Regex("[A-Za-z0-9_.$]+"))) {
            "Unexpected test component name"
        }
        // No force-stop, task clearing or global setting changes: this process hosts instrumentation.
        val command = "am start -W -n $targetPackage/$className -f 0x10000000"
        val output = ParcelFileDescriptor.AutoCloseInputStream(
            instrumentation.uiAutomation.executeShellCommand(command),
        ).bufferedReader().use { it.readText() }
        check(!output.contains("Error:") && !output.contains("SecurityException") && !output.contains("Permission Denial")) {
            "Shell activity launch failed: $output"
        }

        val deadline = SystemClock.elapsedRealtime() + timeoutMillis
        while (launchedActivity == null && SystemClock.elapsedRealtime() < deadline) {
            instrumentation.runOnMainSync {
                val resumed = ActivityLifecycleMonitorRegistry.getInstance()
                    .getActivitiesInStage(Stage.RESUMED)
                    .firstOrNull {
                        it.javaClass == activityClass && it.packageName == targetPackage &&
                            !it.isFinishing && !it.isDestroyed
                    }
                if (resumed != null) launchedActivity = activityClass.cast(resumed)
            }
            if (launchedActivity == null) SystemClock.sleep(25)
        }
        check(launchedActivity != null) {
            "The shell-launched $targetPackage/$className did not reach RESUMED in ${timeoutMillis}ms. $output"
        }
    }

    fun onActivity(action: (A) -> Unit) {
        instrumentation.runOnMainSync { action(activity) }
    }

    fun close() {
        val owned = launchedActivity ?: return
        instrumentation.runOnMainSync {
            // Finish this exact instance only; never close unrelated activities or user apps.
            if (!owned.isFinishing && !owned.isDestroyed) owned.finish()
        }
        launchedActivity = null
    }
}
