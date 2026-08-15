package com.replit.jalwa.accessibility

import android.accessibilityservice.AccessibilityService
import android.view.accessibility.AccessibilityEvent
import com.replit.jalwa.detection.TestAction

/**
 * Minimal, visible, user-enabled accessibility bridge. It does not inspect
 * event contents and only exposes a few explicit system test actions.
 */
class AutomationAccessibilityService : AccessibilityService() {
    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() {
        instance = null
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        super.onDestroy()
    }

    private fun execute(action: TestAction): Boolean =
        when (action) {
            TestAction.BACK -> performGlobalAction(GLOBAL_ACTION_BACK)
            TestAction.HOME -> performGlobalAction(GLOBAL_ACTION_HOME)
            TestAction.NOTIFICATIONS -> performGlobalAction(GLOBAL_ACTION_NOTIFICATIONS)
            TestAction.NONE -> true
        }

    companion object {
        @Volatile
        private var instance: AutomationAccessibilityService? = null

        fun executeAction(action: TestAction): Boolean =
            instance?.execute(action) ?: false
    }
}