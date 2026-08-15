package com.replit.jalwa.data

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID

class TemplateStore(private val context: Context) {
    companion object {
        private const val MAX_TEMPLATE_BYTES = 10L * 1024L * 1024L
        private const val MAX_TEMPLATE_DIMENSION = 4096
    }

    private val directory: File
        get() = File(context.filesDir, "private_templates").also { it.mkdirs() }

    fun import(uri: Uri, displayName: String): String {
        val filename = "${UUID.randomUUID()}.bin"
        val target = open(filename)
        val temporary = File(directory, ".import_${UUID.randomUUID()}.tmp")
        try {
            copyToTemporary(uri, temporary)
            validateImage(temporary)
            check(temporary.renameTo(target)) { "Unable to finalize the selected template" }
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            temporary.delete()
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
        open(filename).delete()
    }

    fun replace(uri: Uri, filename: String) {
        val target = open(filename)
        val temporary = File(directory, ".replace_${UUID.randomUUID()}.tmp")
        try {
            copyToTemporary(uri, temporary)
            validateImage(temporary)
            try {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    temporary.toPath(),
                    target.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } finally {
            temporary.delete()
        }
    }

    fun open(filename: String): File =
        File(directory, filename).canonicalFile.also {
            val root = directory.canonicalFile
            require(
                filename.matches(Regex("^[A-Za-z0-9][A-Za-z0-9._-]{0,127}$")) &&
                    it.parentFile == root,
            ) { "Invalid template path" }
        }

    private fun copyToTemporary(uri: Uri, temporary: File) {
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Unable to read the selected template" }
            temporary.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    total += read
                    require(total <= MAX_TEMPLATE_BYTES) { "Template exceeds the 10 MB limit" }
                    output.write(buffer, 0, read)
                }
            }
        }
    }

    private fun validateImage(file: File) {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, bounds)
        require(
            bounds.outWidth > 0 &&
                bounds.outHeight > 0 &&
                bounds.outWidth <= MAX_TEMPLATE_DIMENSION &&
                bounds.outHeight <= MAX_TEMPLATE_DIMENSION,
        ) { "The selected file is not a supported image" }

        val sample = maxOf(
            1,
            maxOf(bounds.outWidth, bounds.outHeight) / MAX_TEMPLATE_DIMENSION,
        )
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = sample
            inPreferredConfig = android.graphics.Bitmap.Config.ARGB_8888
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions)
        try {
            requireNotNull(bitmap) { "The selected file is not a valid image" }
        } finally {
            bitmap?.recycle()
        }
    }
}