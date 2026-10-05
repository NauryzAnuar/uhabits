package org.isoron.uhabits.activities.plan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import org.isoron.uhabits.R
import org.isoron.uhabits.utils.StyledResources
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Lightweight Google-Calendar-like day timeline.
 * It intentionally stays view-only: editing remains in the planning engine.
 */
class DayTimelineView(context: Context) : View(context) {

    enum class Kind { GOOGLE, LOOP, DONE }

    data class Block(
        val title: String,
        val startMillis: Long,
        val endMillis: Long,
        val kind: Kind
    )

    private var dayStartMillis: Long = 0L
    private var startMinute: Int = 330
    private var endMinute: Int = 1320
    private var blocks: List<Block> = emptyList()

    private val primary = StyledResources(context).getColor(R.attr.colorPrimary)
    private val surface = StyledResources(context).getColor(R.attr.cardBgColor)
    private val textColor = StyledResources(context).getColor(R.attr.contrast100)

    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        strokeWidth = dp(1).toFloat()
        color = withAlpha(textColor, 32)
    }

    private val timePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(textColor, 150)
        textSize = sp(11f)
    }

    private val titlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = textColor
        textSize = sp(12.5f)
        isFakeBoldText = true
    }

    private val smallPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = withAlpha(textColor, 175)
        textSize = sp(10.5f)
    }

    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val nowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(218, 60, 52)
        strokeWidth = dp(2).toFloat()
    }

    fun setData(
        dayStartMillis: Long,
        startMinute: Int,
        endMinute: Int,
        blocks: List<Block>
    ) {
        this.dayStartMillis = dayStartMillis
        this.startMinute = startMinute
        this.endMinute = endMinute
        this.blocks = blocks.sortedBy { it.startMillis }
        requestLayout()
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val minutes = max(60, endMinute - startMinute)
        val desiredHeight = dp(24) + ((minutes / 60f) * dp(64)).roundToInt()
        setMeasuredDimension(width, desiredHeight)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (dayStartMillis == 0L) return

        val gutter = dp(52).toFloat()
        val right = width - dp(8).toFloat()
        val top = dp(14).toFloat()
        val pixelsPerMinute = dp(64) / 60f

        var hourMinute = (startMinute / 60) * 60
        if (hourMinute < startMinute) hourMinute += 60

        while (hourMinute <= endMinute) {
            val y = top + (hourMinute - startMinute) * pixelsPerMinute
            canvas.drawLine(gutter, y, right, y, linePaint)
            canvas.drawText(
                "%02d:00".format(hourMinute / 60),
                dp(4).toFloat(),
                y + sp(4f),
                timePaint
            )
            hourMinute += 60
        }

        val laneEnds = longArrayOf(Long.MIN_VALUE, Long.MIN_VALUE)
        val laneGap = dp(4).toFloat()
        val usableWidth = right - gutter - dp(8)
        val laneWidth = (usableWidth - laneGap) / 2f

        blocks.forEach { block ->
            val blockStartMinute = millisToMinute(block.startMillis)
            val blockEndMinute = millisToMinute(block.endMillis)

            if (blockEndMinute <= startMinute || blockStartMinute >= endMinute) return@forEach

            val clippedStart = max(startMinute, blockStartMinute)
            val clippedEnd = minOf(endMinute, blockEndMinute)

            var lane = laneEnds.indexOfFirst { block.startMillis >= it }
            if (lane < 0) lane = 0
            laneEnds[lane] = block.endMillis

            val left = gutter + dp(8) + lane * (laneWidth + laneGap)
            val eventRight = left + laneWidth
            val y1 = top + (clippedStart - startMinute) * pixelsPerMinute + dp(2)
            val y2 = max(y1 + dp(28), top + (clippedEnd - startMinute) * pixelsPerMinute - dp(2))

            fillPaint.color = when (block.kind) {
                Kind.GOOGLE -> mix(surface, primary, 0.15f)
                Kind.LOOP -> mix(surface, primary, 0.32f)
                Kind.DONE -> mix(surface, Color.rgb(46, 125, 50), 0.28f)
            }

            val rect = RectF(left, y1, eventRight, y2)
            canvas.drawRoundRect(rect, dp(10).toFloat(), dp(10).toFloat(), fillPaint)

            val accent = when (block.kind) {
                Kind.GOOGLE -> withAlpha(primary, 120)
                Kind.LOOP -> primary
                Kind.DONE -> Color.rgb(46, 125, 50)
            }
            fillPaint.color = accent
            canvas.drawRoundRect(
                RectF(left, y1, left + dp(4), y2),
                dp(4).toFloat(),
                dp(4).toFloat(),
                fillPaint
            )

            val labelX = left + dp(10)
            val maxChars = max(8, ((laneWidth - dp(18)) / sp(7f)).toInt())
            val title = if (block.title.length > maxChars) {
                block.title.take(maxChars - 1) + "…"
            } else block.title

            canvas.drawText(title, labelX, y1 + dp(18), titlePaint)
            if (y2 - y1 >= dp(44)) {
                canvas.drawText(
                    "${time(block.startMillis)}–${time(block.endMillis)}",
                    labelX,
                    y1 + dp(35),
                    smallPaint
                )
            }
        }

        drawNowLine(canvas, gutter, right, top, pixelsPerMinute)
    }

    private fun drawNowLine(
        canvas: Canvas,
        gutter: Float,
        right: Float,
        top: Float,
        pixelsPerMinute: Float
    ) {
        val today = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

        if (kotlin.math.abs(today - dayStartMillis) > 60_000L) return

        val now = Calendar.getInstance()
        val minute = now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
        if (minute !in startMinute..endMinute) return

        val y = top + (minute - startMinute) * pixelsPerMinute
        canvas.drawCircle(gutter + dp(2), y, dp(4).toFloat(), nowPaint)
        canvas.drawLine(gutter + dp(4), y, right, y, nowPaint)
    }

    private fun millisToMinute(millis: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun time(millis: Long): String =
        SimpleDateFormat("HH:mm", Locale.getDefault()).format(Date(millis))

    private fun mix(a: Int, b: Int, amount: Float): Int {
        val t = amount.coerceIn(0f, 1f)
        val r = (Color.red(a) * (1 - t) + Color.red(b) * t).roundToInt()
        val g = (Color.green(a) * (1 - t) + Color.green(b) * t).roundToInt()
        val bl = (Color.blue(a) * (1 - t) + Color.blue(b) * t).roundToInt()
        return Color.rgb(r, g, bl)
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun sp(value: Float): Float =
        value * resources.displayMetrics.scaledDensity
}
