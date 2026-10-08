package dev.otherguy.booxtracker

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.drawable.Drawable
import android.os.SystemClock
import androidx.core.graphics.withRotation

/**
 * Twelve spokes that darken towards the leading one and advance by one spoke every 100 ms while running, so an
 * e-ink panel redraws one small region at a slow rate. It is the one animated control, by the user's decision.
 */
class SyncSpinner(private val size: Int) :
    Drawable(),
    Runnable {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.BLACK
        strokeCap = Paint.Cap.ROUND
    }
    private var leading = 0

    /** Whether the spokes are stepping; true from [start] until [stop]. */
    var running = false
        private set

    override fun draw(canvas: Canvas) {
        val centerX = bounds.exactCenterX()
        val centerY = bounds.exactCenterY()
        val radius = minOf(bounds.width(), bounds.height()) / 2f
        paint.strokeWidth = radius * 0.24f
        for (spoke in 0 until SPOKES) {
            // The leading spoke is black; each one behind it is a step lighter.
            paint.alpha = 255 - ((spoke - leading + SPOKES) % SPOKES) * 17
            canvas.withRotation(spoke * 360f / SPOKES, centerX, centerY) {
                drawLine(centerX, centerY - radius + paint.strokeWidth / 2, centerX, centerY - radius / 2, paint)
            }
        }
    }

    override fun getIntrinsicWidth() = size

    override fun getIntrinsicHeight() = size

    override fun setAlpha(alpha: Int) = Unit

    override fun setColorFilter(colorFilter: ColorFilter?) = Unit

    @Deprecated("Deprecated in Java")
    override fun getOpacity() = PixelFormat.TRANSLUCENT

    fun start() {
        if (running) return
        running = true
        scheduleSelf(this, SystemClock.uptimeMillis() + STEP_MS)
    }

    fun stop() {
        running = false
        unscheduleSelf(this)
    }

    override fun run() {
        if (!running) return
        leading = (leading + 1) % SPOKES
        invalidateSelf()
        scheduleSelf(this, SystemClock.uptimeMillis() + STEP_MS)
    }

    private companion object {
        const val SPOKES = 12
        const val STEP_MS = 100L
    }
}
