package com.replit.jalwa

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
data class PermissionStatus(
    val accessibility: Boolean,
    val overlay: Boolean,
    val privateStorage: Boolean = true,
) {
    val allGranted: Boolean get() = accessibility && overlay && privateStorage
}

object PermissionCenter {
    fun read(context: Context): PermissionStatus = PermissionStatus(
        accessibility = isAccessibilityEnabled(context),
        overlay = OverlayPermissionManager.canDrawOverlays(context),
    )

    fun openAccessibility(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openOverlay(context: Context) {
        OverlayPermissionManager.openSettings(context)
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }
}