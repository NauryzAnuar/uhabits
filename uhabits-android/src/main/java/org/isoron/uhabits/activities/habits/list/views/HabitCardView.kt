/*
 * Copyright (C) 2016-2025 Álinson Santos Xavier <git@axavier.org>
 *
 * This file is part of Loop Habit Tracker.
 */

package org.isoron.uhabits.activities.habits.list.views

import android.content.Context
import android.graphics.PointF
import android.graphics.text.LineBreaker.BREAK_STRATEGY_BALANCED
import android.os.Build
import android.os.Build.VERSION.SDK_INT
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.view.ViewGroup.LayoutParams.WRAP_CONTENT
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import me.tatarka.inject.annotations.Inject
import org.isoron.platform.gui.toInt
import org.isoron.platform.time.LocalDate
import org.isoron.platform.time.getToday
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.common.views.RingView
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.ModelObservable
import org.isoron.uhabits.core.ui.screens.habits.list.ListHabitsBehavior
import org.isoron.uhabits.inject.ActivityContext
import org.isoron.uhabits.utils.currentTheme
import org.isoron.uhabits.utils.dp
import org.isoron.uhabits.utils.sres

@Inject
class HabitCardViewFactory(
    @ActivityContext val context: Context,
    private val checkmarkPanelFactory: CheckmarkPanelViewFactory,
    private val numberPanelFactory: NumberPanelViewFactory,
    private val behavior: ListHabitsBehavior
) {
    fun create() = HabitCardView(context, checkmarkPanelFactory, numberPanelFactory, behavior)
}

class HabitCardView(
    @ActivityContext context: Context,
    checkmarkPanelFactory: CheckmarkPanelViewFactory,
    numberPanelFactory: NumberPanelViewFactory,
    private val behavior: ListHabitsBehavior
) : FrameLayout(context),
    ModelObservable.Listener {

    var buttonCount
        get() = checkmarkPanel.buttonCount
        set(value) {
            checkmarkPanel.buttonCount = value
            numberPanel.buttonCount = value
        }

    var dataOffset = 0
        set(value) {
            field = value
            checkmarkPanel.dataOffset = value
            numberPanel.dataOffset = value
        }

    var habit: Habit? = null
        set(newHabit) {
            if (isAttachedToWindow) {
                field?.observable?.removeListener(this)
                newHabit?.observable?.addListener(this)
            }
            field = newHabit
            if (newHabit != null) copyAttributesFrom(newHabit)
        }

    var score
        get() = scoreRing.getPercentage().toDouble()
        set(value) {
            scoreRing.setPercentage(value.toFloat())
            scoreRing.setPrecision(1.0f / 16)
        }

    var unit
        get() = numberPanel.units
        set(value) {
            numberPanel.units = value
        }

    var values
        get() = checkmarkPanel.values
        set(values) {
            checkmarkPanel.values = values
            numberPanel.values = values.map { it / 1000.0 }.toDoubleArray()
        }

    var threshold: Double
        get() = numberPanel.threshold
        set(value) {
            numberPanel.threshold = value
        }

    var notes
        get() = checkmarkPanel.notes
        set(values) {
            checkmarkPanel.notes = values
            numberPanel.notes = values
        }

    var checkmarkPanel: CheckmarkPanelView
    private var numberPanel: NumberPanelView
    private var innerFrame: LinearLayout
    private var label: TextView
    private var metaLabel: TextView
    private var labelColumn: LinearLayout
    private var scoreRing: RingView
    private var sectionHeader: TextView
    private var verticalFrame: LinearLayout

    var sectionTitle: String? = null
        set(value) {
            field = value
            sectionHeader.text = value.orEmpty()
            sectionHeader.visibility = if (value.isNullOrBlank()) GONE else VISIBLE
        }

    var planningMeta: String = ""
        set(value) {
            field = value
            metaLabel.text = value
            metaLabel.visibility = if (value.isBlank()) GONE else VISIBLE
        }

    private var currentToggleTaskId = 0

    init {
        scoreRing = RingView(context).apply {
            val thickness = dp(3f)
            val margin = dp(8f).toInt()
            val ringSize = dp(15f).toInt()
            layoutParams = LinearLayout.LayoutParams(ringSize, ringSize).apply {
                setMargins(margin, 0, margin, 0)
                gravity = Gravity.CENTER
            }
            setThickness(thickness)
        }

        label = TextView(context).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            if (SDK_INT >= Build.VERSION_CODES.Q) {
                breakStrategy = BREAK_STRATEGY_BALANCED
            }
        }

        metaLabel = TextView(context).apply {
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            textSize = 11.5f
            alpha = 0.62f
            visibility = GONE
            setPadding(0, dp(1f).toInt(), 0, 0)
        }

        sectionHeader = TextView(context).apply {
            textSize = 17f
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setTextColor(sres.getColor(R.attr.contrast100))
            visibility = GONE
            setPadding(dp(8f).toInt(), dp(14f).toInt(), dp(8f).toInt(), dp(6f).toInt())
        }

        labelColumn = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, WRAP_CONTENT, 1f)
            addView(label, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(metaLabel, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        checkmarkPanel = checkmarkPanelFactory.create().apply {
            onToggle = { date, value, notes ->
                triggerRipple(date)
                val location = getAbsoluteButtonLocation(date)
                habit?.let {
                    behavior.onToggle(
                        it,
                        date,
                        value,
                        notes,
                        location.x,
                        location.y
                    )
                }
            }
            onEdit = { date ->
                triggerRipple(date)
                val location = getAbsoluteButtonLocation(date)
                habit?.let { behavior.onEdit(it, date, location.x, location.y) }
            }
        }

        numberPanel = numberPanelFactory.create().apply {
            visibility = GONE
            onEdit = { date ->
                triggerRipple(date)
                val location = getAbsoluteButtonLocation(date)
                habit?.let { behavior.onEdit(it, date, location.x, location.y) }
            }
        }

        innerFrame = LinearLayout(context).apply {
            gravity = Gravity.CENTER_VERTICAL
            orientation = LinearLayout.HORIZONTAL
            layoutParams = LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            elevation = dp(1f)

            addView(scoreRing)
            addView(labelColumn)
            addView(checkmarkPanel)
            addView(numberPanel)

            setOnTouchListener { v, event ->
                v.background.setHotspot(event.x, event.y)
                false
            }
        }

        verticalFrame = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
            addView(sectionHeader, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
            addView(innerFrame, LinearLayout.LayoutParams(MATCH_PARENT, WRAP_CONTENT))
        }

        clipToPadding = false
        layoutParams = LayoutParams(MATCH_PARENT, WRAP_CONTENT)
        val margin = dp(3f).toInt()
        setPadding(margin, 0, margin, margin)
        addView(verticalFrame)
    }

    override fun onModelChange() {
        Handler(Looper.getMainLooper()).post {
            habit?.let { copyAttributesFrom(it) }
        }
    }

    override fun setSelected(isSelected: Boolean) {
        super.setSelected(isSelected)
        updateBackground(isSelected)
    }

    fun triggerRipple(date: LocalDate) {
        val location = getRelativeButtonLocation(date)
        triggerRipple(location.x, location.y)
    }

    private fun getRelativeButtonLocation(date: LocalDate): PointF {
        val today = getToday()
        val offset = date.daysUntil(today) - dataOffset
        val panel = when (habit!!.isNumerical) {
            true -> numberPanel
            false -> checkmarkPanel
        }
        val button = panel.buttons[offset]
        val y = button.height / 2.0f
        val x = panel.x + button.x + (button.width / 2).toFloat()
        return PointF(x, y)
    }

    private fun getAbsoluteButtonLocation(date: LocalDate): PointF {
        val containerLocation = IntArray(2)
        this.getLocationInWindow(containerLocation)
        val relButtonLocation = getRelativeButtonLocation(date)
        val windowInsets = rootWindowInsets
        val xInset = windowInsets?.displayCutout?.safeInsetLeft ?: 0
        val yInset = if (SDK_INT <= Build.VERSION_CODES.VANILLA_ICE_CREAM) {
            windowInsets?.systemWindowInsetTop ?: 0
        } else {
            0
        }
        return PointF(
            containerLocation[0].toFloat() + relButtonLocation.x - xInset,
            containerLocation[1].toFloat() + relButtonLocation.y - yInset
        )
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        habit?.observable?.addListener(this)
    }

    override fun onDetachedFromWindow() {
        habit?.observable?.removeListener(this)
        super.onDetachedFromWindow()
    }

    private fun copyAttributesFrom(h: Habit) {
        fun getActiveColor(habit: Habit): Int {
            return when (habit.isArchived) {
                true -> sres.getColor(R.attr.contrast60)
                false -> currentTheme().color(habit.color).toInt()
            }
        }

        val c = getActiveColor(h)
        label.apply {
            text = h.name
            setTextColor(c)
        }

        metaLabel.apply {
            text = planningMeta
            setTextColor(sres.getColor(R.attr.contrast60))
            visibility = if (planningMeta.isBlank()) GONE else VISIBLE
        }

        scoreRing.apply {
            setColor(c)
        }
        checkmarkPanel.apply {
            color = c
            visibility = when (h.isNumerical) {
                true -> View.GONE
                false -> View.VISIBLE
            }
        }
        numberPanel.apply {
            color = c
            units = h.unit
            targetType = h.targetType
            threshold = h.targetValue
            visibility = when (h.isNumerical) {
                true -> View.VISIBLE
                false -> View.GONE
            }
        }
    }

    private fun triggerRipple(x: Float, y: Float) {
        val background = innerFrame.background
        background.setHotspot(x, y)
        background.state = intArrayOf(
            android.R.attr.state_pressed,
            android.R.attr.state_enabled
        )
        Handler().postDelayed({ background.state = intArrayOf() }, 25)
    }

    private fun updateBackground(isSelected: Boolean) {
        val background = when (isSelected) {
            true -> R.drawable.selected_box
            false -> R.drawable.ripple
        }
        innerFrame.setBackgroundResource(background)
    }

    companion object {
        fun (() -> Unit).delay(delayInMillis: Long) {
            Handler(Looper.getMainLooper()).postDelayed(this, delayInMillis)
        }
    }
}
