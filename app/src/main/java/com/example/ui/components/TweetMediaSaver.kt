package com.example.ui.components

import android.app.Activity
import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.example.R
import com.example.domain.model.MediaAttachment
import com.example.domain.model.MediaType
import com.example.domain.model.Tweet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream

data class SavedScreenshot(
    val fileName: String,
    val uri: Uri,
)

/**
 * Zero-permission MediaStore utility for saving full Tweet Card screenshots (returning a
 * shareable/editable [Uri] for Android's system image markup editor) and downloading
 * attached photos/videos directly into the user's device gallery.
 */
object TweetMediaSaver {

    suspend fun saveScreenshotAndReturnUri(
        context: Context,
        bitmap: Bitmap,
        filenamePrefix: String,
    ): Result<SavedScreenshot> = withContext(Dispatchers.IO) {
        runCatching {
            val fileName = "${filenamePrefix}_${System.currentTimeMillis()}.png"
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                    put(MediaStore.Images.Media.RELATIVE_PATH, "${Environment.DIRECTORY_PICTURES}/IlayForX")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val resolver = context.contentResolver
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                    ?: error("Could not create MediaStore entry")
                try {
                    resolver.openOutputStream(uri)?.use { out ->
                        if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                            error("Failed to encode PNG screenshot")
                        }
                    } ?: error("Could not open output stream")
                    values.clear()
                    values.put(MediaStore.Images.Media.IS_PENDING, 0)
                    resolver.update(uri, values, null, null)
                } catch (e: Exception) {
                    resolver.delete(uri, null, null)
                    throw e
                }
                SavedScreenshot(fileName = fileName, uri = uri)
            } else {
                val dir = File(
                    context.getExternalFilesDir(Environment.DIRECTORY_PICTURES),
                    "IlayForX"
                ).apply { mkdirs() }
                val file = File(dir, fileName)
                FileOutputStream(file).use { out ->
                    if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)) {
                        error("Failed to encode PNG screenshot")
                    }
                }
                MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf("image/png"), null)
                val uri = FileProvider.getUriForFile(
                    context,
                    "${context.packageName}.fileprovider",
                    file,
                )
                SavedScreenshot(fileName = fileName, uri = uri)
            }
        }
    }

    enum class ScreenshotLaunchOutcome {
        OPENED_MARKUP_EDITOR,
        OPENED_SHARE_SHEET,
        SAVED_ONLY,
    }

    /**
     * Constructs the standard Android image markup/edit [Intent] with scoped read/write
     * URI grants so the device's default markup/gallery editor can annotate and crop the screenshot.
     */
    fun createMarkupEditorIntent(imageUri: Uri, mimeType: String = "image/png"): Intent =
        Intent(Intent.ACTION_EDIT).apply {
            setDataAndType(imageUri, mimeType)
            clipData = ClipData.newRawUri("Ilay Post Screenshot", imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
        }

    /**
     * Constructs the standard Android image share [Intent] (`ACTION_SEND`) with scoped read
     * URI grants so the user can share the rendered post screenshot to any messaging or social app.
     */
    fun createShareImageIntent(imageUri: Uri): Intent =
        Intent(Intent.ACTION_SEND).apply {
            type = "image/png"
            putExtra(Intent.EXTRA_STREAM, imageUri)
            clipData = ClipData.newRawUri("Ilay Post Screenshot", imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }

    /**
     * Opens the Android system Share sheet (`Intent.createChooser` for `ACTION_SEND`) with the
     * saved post PNG attached.
     */
    fun openImageShareSheet(context: Context, imageUri: Uri): Boolean {
        val shareIntent = createShareImageIntent(imageUri)
        val chooser = Intent.createChooser(
            shareIntent,
            context.getString(R.string.screenshot_share_image_chooser),
        ).apply {
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return runCatching {
            context.startActivity(chooser)
            true
        }.getOrDefault(false)
    }

    /**
     * Launches the saved screenshot directly in the device's image markup editor
     * (`Intent.ACTION_EDIT` for PNG and wildcard image MIME types),
     * and automatically falls back to the Android Share menu (`ACTION_SEND`) if the
     * device has no image markup editor installed.
     */
    fun openInSystemMarkupEditorOrShare(context: Context, imageUri: Uri): ScreenshotLaunchOutcome {
        val mimeCandidates = listOf("image/png", "image/*")
        for (mime in mimeCandidates) {
            val editIntent = createMarkupEditorIntent(imageUri, mime).apply {
                if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            val launchedDirect = runCatching {
                context.startActivity(editIntent)
                true
            }.getOrDefault(false)
            if (launchedDirect) return ScreenshotLaunchOutcome.OPENED_MARKUP_EDITOR
        }

        if (openImageShareSheet(context, imageUri)) {
            return ScreenshotLaunchOutcome.OPENED_SHARE_SHEET
        }

        val viewIntent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(imageUri, "image/png")
            clipData = ClipData.newRawUri("Ilay Post Screenshot", imageUri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        val launchedView = runCatching {
            context.startActivity(viewIntent)
            true
        }.getOrDefault(false)
        return if (launchedView) ScreenshotLaunchOutcome.OPENED_MARKUP_EDITOR else ScreenshotLaunchOutcome.SAVED_ONLY
    }

    suspend fun downloadTweetMedia(
        context: Context,
        okHttpClient: OkHttpClient,
        tweet: Tweet,
    ): Result<Int> = withContext(Dispatchers.IO) {
        runCatching {
            if (tweet.media.isEmpty()) error("This post has no media attachments")
            var savedCount = 0
            tweet.media.forEachIndexed { index, item ->
                val ok = downloadSingleMedia(context, okHttpClient, tweet.id, index + 1, item)
                if (ok) savedCount++
            }
            if (savedCount == 0) error("Failed to download media attachments")
            savedCount
        }
    }

    suspend fun downloadSingleMediaAttachment(
        context: Context,
        okHttpClient: OkHttpClient,
        item: MediaAttachment,
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        runCatching {
            val safeId = item.id.filter { it.isLetterOrDigit() || it == '_' || it == '-' }.ifBlank { "media" }
            val ok = downloadSingleMedia(context, okHttpClient, safeId, 1, item)
            if (!ok) error("Could not save media to Gallery")
            true
        }
    }

    private fun downloadSingleMedia(
        context: Context,
        okHttpClient: OkHttpClient,
        tweetId: String,
        index: Int,
        item: MediaAttachment,
    ): Boolean {
        val isVideo = item.type == MediaType.VIDEO || item.type == MediaType.GIF
        val rawUrl = if (isVideo) {
            item.videoUrl.orEmpty().ifBlank { item.url }
        } else {
            FormatUtils.upgradeXPhotoUrl(item.url.ifBlank { item.thumbnailUrl }, "orig")
        }
        if (rawUrl.isBlank()) return false

        val req = Request.Builder().url(rawUrl).get().build()
        return runCatching {
            okHttpClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return false
                val contentType = resp.header("Content-Type").orEmpty().lowercase()
                val mimeType = when {
                    isVideo -> "video/mp4"
                    contentType.contains("png") -> "image/png"
                    contentType.contains("webp") -> "image/webp"
                    else -> "image/jpeg"
                }
                val ext = when (mimeType) {
                    "video/mp4" -> "mp4"
                    "image/png" -> "png"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }
                val fileName = "ilay_${tweetId}_${index}_${System.currentTimeMillis()}.$ext"

                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val collection = if (isVideo) {
                        MediaStore.Video.Media.EXTERNAL_CONTENT_URI
                    } else {
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI
                    }
                    val relDir = if (isVideo) {
                        "${Environment.DIRECTORY_MOVIES}/IlayForX"
                    } else {
                        "${Environment.DIRECTORY_PICTURES}/IlayForX"
                    }
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                        put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                        put(MediaStore.MediaColumns.RELATIVE_PATH, relDir)
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val resolver = context.contentResolver
                    val uri = resolver.insert(collection, values) ?: return false
                    try {
                        val written = resolver.openOutputStream(uri)?.use { out ->
                            resp.body.byteStream().use { input ->
                                input.copyTo(out, bufferSize = 32 * 1024)
                            }
                        } ?: 0L
                        if (written <= 0L) {
                            resolver.delete(uri, null, null)
                            return false
                        }
                        values.clear()
                        values.put(MediaStore.MediaColumns.IS_PENDING, 0)
                        resolver.update(uri, values, null, null)
                    } catch (e: Exception) {
                        resolver.delete(uri, null, null)
                        throw e
                    }
                } else {
                    val baseDir = context.getExternalFilesDir(
                        if (isVideo) Environment.DIRECTORY_MOVIES else Environment.DIRECTORY_PICTURES
                    )
                    val dir = File(baseDir, "IlayForX").apply { mkdirs() }
                    val file = File(dir, fileName)
                    val written = FileOutputStream(file).use { out ->
                        resp.body.byteStream().use { input ->
                            input.copyTo(out, bufferSize = 32 * 1024)
                        }
                    }
                    if (written <= 0L) {
                        file.delete()
                        return false
                    }
                    MediaScannerConnection.scanFile(context, arrayOf(file.absolutePath), arrayOf(mimeType), null)
                }
                true
            }
        }.getOrDefault(false)
    }
}
