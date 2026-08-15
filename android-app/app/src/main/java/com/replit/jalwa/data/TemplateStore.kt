package com.replit.jalwa.data

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

class TemplateStore(private val context: Context) {
    private val directory: File
        get() = File(context.filesDir, "private_templates").also { it.mkdirs() }

    fun import(uri: Uri, displayName: String): String {
        val filename = "${UUID.randomUUID()}.bin"
        val target = File(directory, filename)
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to read the selected template" }
            target.outputStream().use { output -> input.copyTo(output) }
        }
        return filename
    }

    fun copyAsset(assetName: String): String {
        require(assetName.matches(Regex("^[A-Za-z0-9._-]+$"))) { "Invalid asset name" }
        val filename = "seed_$assetName"
        val target = File(directory, filename)
        if (!target.exists()) {
            context.assets.open("templates/$assetName").use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            }
        }
        return filename
    }

    fun delete(filename: String) {
        File(directory, filename).delete()
    }

    fun open(filename: String): File =
        File(directory, filename).also {
            require(it.parentFile?.canonicalFile == directory.canonicalFile) { "Invalid template path" }
        }
}