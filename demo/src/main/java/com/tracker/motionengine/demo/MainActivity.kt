package com.tracker.motionengine.demo

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import com.tracker.motionengine.MotionEngine
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var status: TextView
    private lateinit var preview: ImageView
    private lateinit var progress: ProgressBar
    private val pickVideoRequest = 401

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(createContent())
    }

    private fun createContent(): ScrollView {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24, 24, 24, 24)
        }
        val title = TextView(this).apply {
            text = "SHDMotion video tracker"
            textSize = 22f
            setTextColor(Color.rgb(16, 24, 32))
            setPadding(0, 0, 0, 12)
        }
        val description = TextView(this).apply {
            text = "Select an MP4. The demo detects light-blue pixels in the first frame, tracks the bounding box, and exports CSV coordinates."
            textSize = 15f
            setPadding(0, 0, 0, 16)
        }
        val choose = Button(this).apply {
            text = "Choose MP4 and track"
            setOnClickListener { chooseVideo() }
        }
        progress = ProgressBar(this).apply {
            isIndeterminate = true
            visibility = ProgressBar.GONE
        }
        status = TextView(this).apply {
            text = "Waiting for a video."
            textSize = 14f
            setPadding(0, 16, 0, 16)
        }
        preview = ImageView(this).apply {
            adjustViewBounds = true
            scaleType = ImageView.ScaleType.FIT_CENTER
            setBackgroundColor(Color.BLACK)
        }
        root.addView(title)
        root.addView(description)
        root.addView(choose, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(progress, LinearLayout.LayoutParams(-1, 64).apply { gravity = Gravity.CENTER })
        root.addView(status)
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        return ScrollView(this).apply { addView(root) }
    }

    private fun chooseVideo() {
        startActivityForResult(
            Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                type = "video/mp4"
                addCategory(Intent.CATEGORY_OPENABLE)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            },
            pickVideoRequest
        )
    }

    @Deprecated("Activity result API is unnecessary for this minimal test app.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickVideoRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
            // Some document providers grant a temporary read permission only.
        }
        runTracking(uri)
    }

    private fun runTracking(uri: Uri) {
        progress.visibility = ProgressBar.VISIBLE
        status.text = "Decoding frames and tracking..."
        executor.execute {
            val result = try {
                VideoTracker(contentResolver, cacheDir).track(uri)
            } catch (error: Exception) {
                TrackResult(null, error.message ?: error.javaClass.simpleName, 0, null)
            }
            runOnUiThread {
                progress.visibility = ProgressBar.GONE
                result.preview?.let { preview.setImageBitmap(it) }
                status.text = result.message
            }
        }
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }
}

private data class TrackResult(
    val preview: Bitmap?,
    val message: String,
    val frames: Int,
    val output: File?
)

private class VideoTracker(
    private val resolver: android.content.ContentResolver,
    private val cacheDir: File
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 255, 80)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    fun track(uri: Uri): TrackResult {
        val retriever = MediaMetadataRetriever()
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: return TrackResult(null, "Could not open the selected video.", 0, null)
        retriever.setDataSource(descriptor.fileDescriptor)
        val durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull()?.times(1000L) ?: 0L
        if (durationUs <= 0L) {
            descriptor.close()
            retriever.release()
            return TrackResult(null, "Could not read video duration.", 0, null)
        }
        val stepUs = 100_000L
        var previous: Bitmap? = null
        var box: MotionEngine.BoundingBox? = null
        var firstPreview: Bitmap? = null
        var lastPreview: Bitmap? = null
        var frameCount = 0
        val csv = File(cacheDir, "motion-tracking-${System.currentTimeMillis()}.csv")
        csv.bufferedWriter().use { writer ->
            writer.appendLine("time_ms,x,y,width,height")
            var timeUs = 0L
            while (timeUs <= durationUs) {
                val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                if (frame != null) {
                    val rgba = frame.copy(Bitmap.Config.ARGB_8888, false)
                    val currentBuffer = rgba.toRgbaBuffer()
                    if (box == null) {
                        box = detectLightBlue(rgba)
                        if (box == null) {
                            descriptor.close()
                            retriever.release()
                            return TrackResult(null, "No light-blue region found in the first frame.", frameCount, null)
                        }
                    } else if (previous != null) {
                        box = MotionEngine.trackBoundingBox(
                            previous!!.toRgbaBuffer(), currentBuffer, rgba.width, rgba.height,
                            rgba.width * 4, box!!, searchRadius = max(24, min(rgba.width / 8, 96)),
                            templateRadius = max(8, min(box!!.width.toInt() / 2, 32))
                        )
                    }
                    val tracked = drawBox(rgba, box!!)
                    if (firstPreview == null) firstPreview = tracked
                    lastPreview = tracked
                    val currentBox = box!!
                    writer.appendLine(String.format(
                        Locale.US, "%.0f,%.2f,%.2f,%.2f,%.2f",
                        timeUs / 1000.0, currentBox.x, currentBox.y, currentBox.width, currentBox.height
                    ))
                    previous?.recycle()
                    previous = rgba
                    frameCount++
                }
                timeUs += stepUs
            }
        }
        previous?.recycle()
        descriptor.close()
        retriever.release()
        val name = queryDisplayName(uri)
        return TrackResult(
            lastPreview,
            "Tracked $frameCount frames from $name. CSV saved at ${csv.absolutePath}",
            frameCount,
            csv
        )
    }

    private fun queryDisplayName(uri: Uri): String {
        resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
            if (it.moveToFirst()) return it.getString(0)
        }
        return uri.lastPathSegment ?: "video"
    }

    private fun detectLightBlue(bitmap: Bitmap): MotionEngine.BoundingBox? {
        val pixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        var left = bitmap.width
        var top = bitmap.height
        var right = -1
        var bottom = -1
        for (y in 0 until bitmap.height) {
            for (x in 0 until bitmap.width) {
                val color = pixels[y * bitmap.width + x]
                val red = Color.red(color)
                val green = Color.green(color)
                val blue = Color.blue(color)
                if (blue >= 135 && blue > red + 25 && green > red + 15 && blue >= green - 45) {
                    left = min(left, x)
                    top = min(top, y)
                    right = max(right, x)
                    bottom = max(bottom, y)
                }
            }
        }
        if (right < left || bottom < top || right - left < 4 || bottom - top < 4) return null
        val padding = 8
        return MotionEngine.BoundingBox(
            max(0, left - padding).toFloat(),
            max(0, top - padding).toFloat(),
            min(bitmap.width - max(0, left - padding), right - left + padding * 2).toFloat(),
            min(bitmap.height - max(0, top - padding), bottom - top + padding * 2).toFloat()
        )
    }

    private fun drawBox(bitmap: Bitmap, box: MotionEngine.BoundingBox): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(result).drawRect(box.x, box.y, box.x + box.width, box.y + box.height, paint)
        return result
    }

    private fun Bitmap.toRgbaBuffer(): ByteBuffer {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        val buffer = ByteBuffer.allocateDirect(width * height * 4)
        for (color in pixels) {
            buffer.put(Color.red(color).toByte())
            buffer.put(Color.green(color).toByte())
            buffer.put(Color.blue(color).toByte())
            buffer.put(Color.alpha(color).toByte())
        }
        buffer.rewind()
        return buffer
    }
}
