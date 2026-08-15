package com.replit.jalwa

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.Settings

object OverlayPermissionManager {
    fun canDrawOverlays(context: Context): Boolean =
        Settings.canDrawOverlays(context)

    fun openSettings(context: Context) {
        context.startActivity(
            Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}"),
            ),
        )
    }
}