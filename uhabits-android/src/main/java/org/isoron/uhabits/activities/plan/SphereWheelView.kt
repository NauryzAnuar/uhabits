package org.isoron.uhabits.activities.plan

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.util.AttributeSet
import android.util.TypedValue
import android.view.View
import androidx.core.content.ContextCompat
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin

/**
 * A lightweight radar / "life wheel" view with no external chart dependency.
 * Scores are 0..1. A null score means that no habits are linked to the sphere.
 */
class SphereWheelView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    data class Item(
        val label: String,
        val score: Float?,
        val habitCount: Int
    )

    private var items: List<Item> = emptyList()

    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = withAlpha(resolveColor(android.R.attr.textColorPrimary, Color.DKGRAY), 55)
    }

    private val spokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(1f)
        color = withAlpha(resolveColor(android.R.attr.textColorPrimary, Color.DKGRAY), 45)
    }

    private val dataStrokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(2.5f)
        color = resolveColor(androidx.appcompat.R.attr.colorAccent, Color.rgb(33, 150, 243))
    }

    private val dataFillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = withAlpha(
            resolveColor(androidx.appcompat.R.attr.colorAccent, Color.rgb(33, 150, 243)),
            58
        )
    }

    private val pointPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = resolveColor(androidx.appcompat.R.attr.colorAccent, Color.rgb(33, 150, 243))
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(11f)
        color = resolveColor(android.R.attr.textColorPrimary, Color.DKGRAY)
        textAlign = Paint.Align.CENTER
    }

    private val scorePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        textSize = sp(10f)
        color = resolveColor(android.R.attr.textColorSecondary, Color.GRAY)
        textAlign = Paint.Align.CENTER
    }

    fun setItems(value: List<Item>) {
        items = value
        invalidate()
        requestLayout()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val width = MeasureSpec.getSize(widthMeasureSpec)
        val desiredHeight = dp(390f).toInt()
        val height = resolveSize(desiredHeight, heightMeasureSpec)
        setMeasuredDimension(width, height)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (items.size < 3) return

        val cx = width / 2f
        val cy = height / 2f + dp(2f)
        val radius = min(width * 0.31f, height * 0.31f)
        val n = items.size

        for (level in 1..5) {
            val r = radius * level / 5f
            canvas.drawPath(polygonPath(cx, cy, r, n), gridPaint)
        }

        for (i in 0 until n) {
            val angle = angleFor(i, n)
            val x = cx + cos(angle) * radius
            val y = cy + sin(angle) * radius
            canvas.drawLine(cx, cy, x, y, spokePaint)
        }

        val dataPath = Path()
        items.forEachIndexed { index, item ->
            val score = (item.score ?: 0f).coerceIn(0f, 1f)
            val angle = angleFor(index, n)
            val x = cx + cos(angle) * radius * score
            val y = cy + sin(angle) * radius * score
            if (index == 0) dataPath.moveTo(x, y) else dataPath.lineTo(x, y)
        }
        dataPath.close()
        canvas.drawPath(dataPath, dataFillPaint)
        canvas.drawPath(dataPath, dataStrokePaint)

        items.forEachIndexed { index, item ->
            val score = (item.score ?: 0f).coerceIn(0f, 1f)
            val angle = angleFor(index, n)
            val px = cx + cos(angle) * radius * score
            val py = cy + sin(angle) * radius * score
            if (item.score != null) {
                canvas.drawCircle(px, py, dp(3.5f), pointPaint)
            }

            val labelRadius = radius + dp(35f)
            val lx = cx + cos(angle) * labelRadius
            val ly = cy + sin(angle) * labelRadius

            canvas.drawText(item.label, lx, ly, textPaint)
            val scoreText = if (item.score == null) {
                "—"
            } else {
                "${(item.score * 100).toInt()}%"
            }
            canvas.drawText(scoreText, lx, ly + dp(14f), scorePaint)
        }
    }

    private fun polygonPath(cx: Float, cy: Float, radius: Float, n: Int): Path {
        val path = Path()
        for (i in 0 until n) {
            val angle = angleFor(i, n)
            val x = cx + cos(angle) * radius
            val y = cy + sin(angle) * radius
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        path.close()
        return path
    }

    private fun angleFor(index: Int, n: Int): Float =
        (-PI / 2.0 + 2.0 * PI * index / n).toFloat()

    private fun resolveColor(attribute: Int, fallback: Int): Int {
        val value = TypedValue()
        if (!context.theme.resolveAttribute(attribute, value, true)) return fallback
        return if (value.resourceId != 0) {
            ContextCompat.getColor(context, value.resourceId)
        } else {
            value.data
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    private fun sp(value: Float): Float =
        value * resources.displayMetrics.scaledDensity
}
