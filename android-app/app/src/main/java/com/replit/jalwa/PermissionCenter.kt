package com.replit.jalwa

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import androidx.core.app.NotificationManagerCompat
data class PermissionStatus(
    val accessibility: Boolean,
    val overlay: Boolean,
    val privateStorage: Boolean = true,
    val notifications: Boolean = true,
) {
    val allGranted: Boolean get() = accessibility && overlay && privateStorage && notifications
}

object PermissionCenter {
    fun read(context: Context): PermissionStatus = PermissionStatus(
        accessibility = isAccessibilityEnabled(context),
        overlay = OverlayPermissionManager.canDrawOverlays(context),
        privateStorage = context.filesDir.isDirectory &&
            context.filesDir.canRead() &&
            context.filesDir.canWrite(),
        notifications = areNotificationsEnabled(context),
    )

    fun openAccessibility(context: Context) {
        context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }

    fun openOverlay(context: Context) {
        OverlayPermissionManager.openSettings(context)
    }

    fun openNotifications(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_APP_NOTIFICATION_SETTINGS,
            ).putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName),
        )
    }

    private fun areNotificationsEnabled(context: Context): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        return android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun isAccessibilityEnabled(context: Context): Boolean {
        val manager = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as AccessibilityManager
        return manager.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            .any { it.resolveInfo.serviceInfo.packageName == context.packageName }
    }
}