package com.replit.jalwa.actions

import com.replit.jalwa.accessibility.AutomationAccessibilityService
import com.replit.jalwa.detection.DetectionResult
import com.replit.jalwa.detection.TestAction
import kotlinx.coroutines.delay

/**
 * Executes only the single action explicitly configured by the user. The
 * default action is NONE, and no arbitrary gestures, text entry, or hidden
 * automation are supported.
 */
class ActionController(
    private val safetyDelayMs: Long = 150L,
    private val onStatus: (String) -> Unit,
    private val onRecorded: (DetectionResult, TestAction) -> Unit,
) {
    suspend fun execute(result: DetectionResult, action: TestAction) {
        if (!result.matched || action == TestAction.NONE) {
            onStatus("Match recorded; no action configured")
            return
        }
        delay(safetyDelayMs.coerceIn(0L, 5_000L))
        onStatus("Executing one configured test action")
        if (AutomationAccessibilityService.executeAction(action)) {
            onRecorded(result, action)
            onStatus("Action complete")
        } else {
            onStatus("Accessibility action unavailable")
        }
    }
}