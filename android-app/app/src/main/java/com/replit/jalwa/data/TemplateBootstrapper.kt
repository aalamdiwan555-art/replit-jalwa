package com.replit.jalwa.data

import android.content.Context

class TemplateBootstrapper(
    private val context: Context,
    private val store: TemplateStore,
    private val dao: TemplateDao,
) {
    suspend fun seedIfEmpty() {
        if (dao.count() > 0) return
        context.assets.list("templates")
            .orEmpty()
            .filter { it.endsWith(".png", ignoreCase = true) }
            .sortedWith(compareBy { naturalNumber(it) })
            .forEach { assetName ->
                val filename = store.copyAsset(assetName)
                dao.insert(
                    TemplateEntity(
                        name = assetName.substringBeforeLast('.'),
                        internalFilename = filename,
                        enabled = true,
                        threshold = 0.90f,
                    ),
                )
            }
    }

    private fun naturalNumber(name: String): Int =
        Regex("\\d+").find(name)?.value?.toIntOrNull() ?: Int.MAX_VALUE
}