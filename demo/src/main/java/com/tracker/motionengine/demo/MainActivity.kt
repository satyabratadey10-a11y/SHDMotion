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
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.tracker.motionengine.MotionEngine
import java.io.File
import java.nio.ByteBuffer
import java.util.Locale
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min

class MainActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private val cancelled = AtomicBoolean(false)
    private lateinit var chooseButton: Button
    private lateinit var trackButton: Button
    private lateinit var progress: ProgressBar
    private lateinit var status: TextView
    private lateinit var preview: FrameSelectionView
    private var selectedUri: Uri? = null
    private var firstFrame: Bitmap? = null
    private val pickVideoRequest = 401

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(buildUi())
    }

    private fun buildUi(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }
        val title = TextView(this).apply {
            text = "SHDMotion tracker"
            textSize = 22f
            setTextColor(Color.rgb(16, 24, 32))
        }
        val instructions = TextView(this).apply {
            text = "1. Choose an MP4.  2. Drag over the light-blue object in the preview.  3. Start tracking."
            textSize = 15f
            setPadding(0, 10, 0, 10)
        }
        chooseButton = Button(this).apply {
            text = "Choose MP4"
            setOnClickListener { chooseVideo() }
        }
        trackButton = Button(this).apply {
            text = "Start tracking"
            isEnabled = false
            setOnClickListener { startTracking() }
        }
        val cancelButton = Button(this).apply {
            text = "Cancel"
            setOnClickListener {
                cancelled.set(true)
                status.text = "Cancelling..."
            }
        }
        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progress = 0
            visibility = View.GONE
        }
        status = TextView(this).apply {
            text = "No video selected."
            setPadding(0, 8, 0, 8)
        }
        preview = FrameSelectionView(this)
        root.addView(title)
        root.addView(instructions)
        root.addView(chooseButton, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(trackButton, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(cancelButton, LinearLayout.LayoutParams(-1, ViewGroup.LayoutParams.WRAP_CONTENT))
        root.addView(progress, LinearLayout.LayoutParams(-1, 32))
        root.addView(status)
        root.addView(preview, LinearLayout.LayoutParams(-1, 0, 1f))
        return root
    }

    private fun chooseVideo() {
        startActivityForResult(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            type = "video/*"
            addCategory(Intent.CATEGORY_OPENABLE)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }, pickVideoRequest)
    }

    @Deprecated("Uses the platform picker to keep this demo dependency-free.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != pickVideoRequest || resultCode != RESULT_OK) return
        val uri = data?.data ?: return
        try {
            contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        } catch (_: SecurityException) {
        }
        loadFirstFrame(uri)
    }

    private fun loadFirstFrame(uri: Uri) {
        selectedUri = uri
        chooseButton.isEnabled = false
        trackButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = true
        status.text = "Loading the first video frame..."
        executor.execute {
            val result = try {
                val retriever = MediaMetadataRetriever()
                val descriptor = contentResolver.openFileDescriptor(uri, "r")
                if (descriptor == null) {
                    retriever.release()
                    throw IllegalStateException("The selected video cannot be opened.")
                }
                try {
                    retriever.setDataSource(descriptor.fileDescriptor)
                    retriever.getFrameAtTime(0L, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?.copy(Bitmap.Config.ARGB_8888, false)
                        ?: throw IllegalStateException("The video has no decodable first frame.")
                } finally {
                    descriptor.close()
                    retriever.release()
                }
            } catch (error: Exception) {
                error
            }
            runOnUiThread {
                chooseButton.isEnabled = true
                progress.visibility = View.GONE
                if (result is Bitmap) {
                    firstFrame?.recycle()
                    firstFrame = result
                    preview.setFrame(result)
                    trackButton.isEnabled = true
                    status.text = "Frame loaded. Drag a box around the light-blue object."
                } else {
                    status.text = "Could not load video: ${(result as Exception).message}"
                }
            }
        }
    }

    private fun startTracking() {
        val uri = selectedUri ?: return
        if (firstFrame == null) return
        val box = preview.selection()
        if (box.width < 4f || box.height < 4f) {
            status.text = "Draw a rectangle around the object first."
            return
        }
        cancelled.set(false)
        chooseButton.isEnabled = false
        trackButton.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = false
        progress.progress = 0
        status.text = "Tracking..."
        executor.execute {
            val result = try {
                VideoTracker(contentResolver, cacheDir, cancelled) { completed, total ->
                    runOnUiThread {
                        progress.progress = (completed * 100 / total).coerceIn(0, 100)
                        status.text = "Tracking frame $completed of $total..."
                    }
                }.track(uri, box)
            } catch (error: Exception) {
                TrackResult(null, "Tracking failed: ${error.message ?: error.javaClass.simpleName}", 0)
            }
            runOnUiThread {
                chooseButton.isEnabled = true
                trackButton.isEnabled = true
                progress.visibility = View.GONE
                result.preview?.let { preview.setFrame(it, result.box) }
                status.text = result.message
            }
        }
    }

    override fun onDestroy() {
        cancelled.set(true)
        executor.shutdownNow()
        firstFrame?.recycle()
        super.onDestroy()
    }
}

private class FrameSelectionView(context: android.content.Context) : View(context) {
    private var frame: Bitmap? = null
    private var box = MotionEngine.BoundingBox(0f, 0f, 0f, 0f)
    private var dragging = false
    private var startX = 0f
    private var startY = 0f
    private val border = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 255, 100)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    fun setFrame(bitmap: Bitmap, trackedBox: MotionEngine.BoundingBox? = null) {
        frame = bitmap
        if (trackedBox != null) box = trackedBox
        invalidate()
    }

    fun selection() = box

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val bitmap = frame ?: return
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val left = (width - bitmap.width * scale) / 2f
        val top = (height - bitmap.height * scale) / 2f
        canvas.drawBitmap(bitmap, null,
            android.graphics.RectF(left, top, left + bitmap.width * scale, top + bitmap.height * scale), null)
        if (box.width > 0f) {
            canvas.drawRect(left + box.x * scale, top + box.y * scale,
                left + (box.x + box.width) * scale, top + (box.y + box.height) * scale, border)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val bitmap = frame ?: return false
        val scale = min(width.toFloat() / bitmap.width, height.toFloat() / bitmap.height)
        val left = (width - bitmap.width * scale) / 2f
        val top = (height - bitmap.height * scale) / 2f
        val x = ((event.x - left) / scale).coerceIn(0f, bitmap.width.toFloat())
        val y = ((event.y - top) / scale).coerceIn(0f, bitmap.height.toFloat())
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = x
                startY = y
                box = MotionEngine.BoundingBox(x, y, 0f, 0f)
                dragging = true
            }
            MotionEvent.ACTION_MOVE -> if (dragging) box = normalized(startX, startY, x, y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (dragging) box = normalized(startX, startY, x, y)
                dragging = false
            }
        }
        invalidate()
        return true
    }

    private fun normalized(x1: Float, y1: Float, x2: Float, y2: Float) =
        MotionEngine.BoundingBox(min(x1, x2), min(y1, y2), kotlin.math.abs(x2 - x1), kotlin.math.abs(y2 - y1))
}

private data class TrackResult(
    val preview: Bitmap?,
    val message: String,
    val frames: Int,
    val box: MotionEngine.BoundingBox? = null
)

private class VideoTracker(
    private val resolver: android.content.ContentResolver,
    private val cacheDir: File,
    private val cancelled: AtomicBoolean,
    private val onProgress: (completed: Int, total: Int) -> Unit
) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(0, 255, 80)
        style = Paint.Style.STROKE
        strokeWidth = 5f
    }

    fun track(uri: Uri, initialBox: MotionEngine.BoundingBox): TrackResult {
        val retriever = MediaMetadataRetriever()
        val descriptor = resolver.openFileDescriptor(uri, "r")
            ?: throw IllegalStateException("The selected video cannot be opened.")
        var previous: Bitmap? = null
        var last: Bitmap? = null
        var box = initialBox
        var frameCount = 0
        try {
            retriever.setDataSource(descriptor.fileDescriptor)
            val durationUs = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
                ?.toLongOrNull()?.times(1000L) ?: throw IllegalStateException("Video duration is unavailable.")
            val stepUs = 200_000L
            val totalFrames = max(1, min(600, (durationUs / stepUs + 1).toInt()))
            val csv = File(cacheDir, "motion-tracking-${System.currentTimeMillis()}.csv")
            csv.bufferedWriter().use { writer ->
                writer.appendLine("time_ms,x,y,width,height")
                var timeUs = 0L
                while (timeUs <= durationUs && frameCount < totalFrames && !cancelled.get()) {
                    val frame = retriever.getFrameAtTime(timeUs, MediaMetadataRetriever.OPTION_CLOSEST)
                        ?: throw IllegalStateException("Could not decode frame at ${timeUs / 1000} ms.")
                    val rgba = frame.copy(Bitmap.Config.ARGB_8888, false)
                    if (previous != null) {
                        box = MotionEngine.trackBoundingBox(previous!!.toRgbaBuffer(), rgba.toRgbaBuffer(),
                            rgba.width, rgba.height, rgba.width * 4, box,
                            searchRadius = max(24, min(rgba.width / 8, 96)),
                            templateRadius = max(8, min(box.width.toInt() / 2, 32)))
                    }
                    last?.recycle()
                    last = drawBox(rgba, box)
                    writer.appendLine(String.format(Locale.US, "%.0f,%.2f,%.2f,%.2f,%.2f",
                        timeUs / 1000.0, box.x, box.y, box.width, box.height))
                    previous?.recycle()
                    previous = rgba
                    frameCount++
                    onProgress(frameCount, totalFrames)
                    timeUs += stepUs
                }
            }
            val message = if (cancelled.get()) "Tracking cancelled after $frameCount frames."
            else "Tracked $frameCount frames. CSV saved at ${csv.absolutePath}"
            return TrackResult(last, message, frameCount, box)
        } finally {
            previous?.recycle()
            descriptor.close()
            retriever.release()
        }
    }

    private fun drawBox(bitmap: Bitmap, box: MotionEngine.BoundingBox): Bitmap {
        val result = bitmap.copy(Bitmap.Config.ARGB_8888, true)
        Canvas(result).drawRect(box.x, box.y, box.x + box.width, box.y + box.height, paint)
        return result
    }

    private fun Bitmap.toRgbaBuffer(): ByteBuffer {
        val pixels = IntArray(width * height)
        getPixels(pixels, 0, width, 0, 0, width, height)
        return ByteBuffer.allocateDirect(width * height * 4).apply {
            pixels.forEach { color ->
                put(Color.red(color).toByte())
                put(Color.green(color).toByte())
                put(Color.blue(color).toByte())
                put(Color.alpha(color).toByte())
            }
            rewind()
        }
    }
}
