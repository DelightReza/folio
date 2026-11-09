package com.allient.folio

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.DashPathEffect
import android.graphics.RectF
import android.app.Activity
import android.os.Build
import android.view.View
import android.view.WindowManager
import java.util.concurrent.Executor
import java.util.function.Consumer
import java.util.Collections
import java.util.WeakHashMap

/** A quiet on-screen guide for handwriting; never part of document exports. */
internal enum class WritingGridType(val label: String, val presetRows: Int? = null, val presetColumns: Int? = null) {
    CustomSize("Custom Size"),
    CustomRowColumn("Custom Row/Col"),
    Standard("Standard", 8, 8),
    Avatar("Avatar", 4, 4),
    CardView("Card View", 12, 8),
    NavDrawer("Nav Drawer", 16, 4),
    Typographic("Typographic", 12, 24),
}
internal enum class WritingGridLineStyle(val label: String) { Solid("Solid"), Dashed("Dashed"), Dotted("Dotted") }

internal data class WritingGridSettings(
    val type: WritingGridType = WritingGridType.CustomRowColumn,
    val rows: Int = 24,
    val columns: Int = 24,
    val thickness: Float = 1f,
    val color: Int = 0x403B8373,
    val opacity: Float = 1f,
    val majorEvery: Int = 0,
    val lineStyle: WritingGridLineStyle = WritingGridLineStyle.Solid,
    val lockAspect: Boolean = false,
    val margin: Float = 0f,
    val offsetX: Float = 0f,
    val offsetY: Float = 0f,
    val cellWidthMm: Float = 10f,
    val cellHeightMm: Float = 10f,
    val snap: Boolean = false,
)

internal val WritingGridColors = listOf(
    0x403B8373, 0x604A6FA5, 0x605C6570, 0x604B5563, 0x607A5C3B, 0x606B4E71
)

internal object WritingGrid {
    fun draw(canvas: Canvas, bounds: RectF, settings: WritingGridSettings = WritingGridSettings()) {
        if (bounds.width() <= 0f || bounds.height() <= 0f) return
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = (settings.color and 0x00FFFFFF) or
                (((settings.color ushr 24) * settings.opacity.coerceIn(0f, 1f)).toInt() shl 24)
            style = Paint.Style.STROKE
            strokeWidth = settings.thickness.coerceIn(.5f, 6f)
            pathEffect = when (settings.lineStyle) {
                WritingGridLineStyle.Solid -> null
                WritingGridLineStyle.Dashed -> DashPathEffect(floatArrayOf(10f, 7f), 0f)
                WritingGridLineStyle.Dotted -> DashPathEffect(floatArrayOf(1f, 7f), 0f)
            }

        }
        val insetX = bounds.width() * settings.margin.coerceIn(0f, .25f)
        val insetY = bounds.height() * settings.margin.coerceIn(0f, .25f)
        val area = RectF(bounds.left + insetX, bounds.top + insetY, bounds.right - insetX, bounds.bottom - insetY)
        val density = canvas.density.coerceAtLeast(1) / 25.4f
        val cellWidthMm = settings.cellWidthMm.coerceAtLeast(1f)
        val cellHeightMm = if (settings.lockAspect) cellWidthMm else settings.cellHeightMm.coerceAtLeast(1f)
        val sizeColumns = (area.width() / (cellWidthMm * density)).toInt().coerceIn(2, 100)
        val sizeRows = (area.height() / (cellHeightMm * density)).toInt().coerceIn(2, 100)
        val columns = if (settings.type == WritingGridType.CustomSize) sizeColumns else settings.columns.coerceIn(0, 100)
        val rows = if (settings.type == WritingGridType.CustomSize) sizeRows else settings.rows.coerceIn(0, 100)
        val columnStep = if (columns > 0) area.width() / columns else 0f
        val rowStep = if (rows > 0) area.height() / rows else 0f
        val offsetX = columnStep * settings.offsetX
        val offsetY = rowStep * settings.offsetY
        for (column in 1 until columns) {
            val x = area.left + offsetX + columnStep * column
            if (x > area.left && x < area.right) canvas.drawLine(x, area.top, x, area.bottom, paint)
        }

        for (row in 1 until rows) {
            val y = area.top + offsetY + rowStep * row
            if (y > area.top && y < area.bottom) canvas.drawLine(area.left, y, area.right, y, paint)
        }
        if (settings.majorEvery > 1) {
            val major = paint.strokeWidth * 1.8f
            paint.strokeWidth = major
            for (column in settings.majorEvery until columns step settings.majorEvery) {
                val x = area.left + offsetX + columnStep * column
                if (x > area.left && x < area.right) canvas.drawLine(x, area.top, x, area.bottom, paint)
            }
            for (row in settings.majorEvery until rows step settings.majorEvery) {
                val y = area.top + offsetY + rowStep * row
                if (y > area.top && y < area.bottom) canvas.drawLine(area.left, y, area.right, y, paint)
            }
        }

    }

    /** A transparent overlay that is hidden by the activity while Android captures the screen. */
    internal class SecureWritingGridView(context: android.content.Context) : View(context) {
        private val bounds = RectF()
        private var visible = false
        private var settings = WritingGridSettings()

        init {
            isClickable = false
            isFocusable = false
            captureViews.add(this)
        }

        fun update(next: RectF?, show: Boolean, nextSettings: WritingGridSettings = settings) {
            if (next != null) bounds.set(next) else bounds.setEmpty()
            visible = show
            settings = nextSettings
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (visible && !captureActive) WritingGrid.draw(canvas, bounds, settings)
        }

        override fun onDetachedFromWindow() {
            captureViews.remove(this)
            super.onDetachedFromWindow()
        }
    }

    @Volatile private var captureActive = false
    private val captureViews = Collections.newSetFromMap(WeakHashMap<SecureWritingGridView, Boolean>())

    internal fun setCaptureActive(active: Boolean) {
        captureActive = active
        synchronized(captureViews) { captureViews.toList().forEach(View::invalidate) }
    }
}

internal class WritingGridCaptureObserver(private val activity: Activity) {
    private val executor = Executor { it.run() }
    private val screenshotCallback = object : Activity.ScreenCaptureCallback {
        override fun onScreenCaptured() {
            WritingGrid.setCaptureActive(true)
            activity.window.decorView.postDelayed({ WritingGrid.setCaptureActive(false) }, 500L)
        }
    }
    private val recordingCallback = Consumer<Int> { state ->
        WritingGrid.setCaptureActive(state == WindowManager.SCREEN_RECORDING_STATE_VISIBLE)
    }

    fun register() {
        if (Build.VERSION.SDK_INT >= 34) activity.registerScreenCaptureCallback(executor, screenshotCallback)
        if (Build.VERSION.SDK_INT >= 35) activity.windowManager.addScreenRecordingCallback(executor, recordingCallback)
    }

    fun unregister() {
        if (Build.VERSION.SDK_INT >= 34) activity.unregisterScreenCaptureCallback(screenshotCallback)
        if (Build.VERSION.SDK_INT >= 35) activity.windowManager.removeScreenRecordingCallback(recordingCallback)
        WritingGrid.setCaptureActive(false)
    }
}
