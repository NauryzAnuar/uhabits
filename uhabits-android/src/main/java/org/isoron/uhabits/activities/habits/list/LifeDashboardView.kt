package org.isoron.uhabits.activities.habits.list

import android.content.Context
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.plan.CalendarActivity
import org.isoron.uhabits.activities.plan.PlanActivity
import org.isoron.uhabits.bridge.BridgeCalendarManager
import org.isoron.uhabits.bridge.BridgeLifeRepository
import org.isoron.uhabits.bridge.BridgePersonalSeeder
import org.isoron.uhabits.bridge.BridgeSnapshotFactory
import org.isoron.uhabits.bridge.BridgeSnapshotStore
import org.isoron.uhabits.bridge.LifePlanningEngine
import org.isoron.uhabits.utils.StyledResources
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/**
 * One compact status row. The main screen itself is now the routine/time flow,
 * so navigation chrome should not push the habits below the fold.
 */
class LifeDashboardView(context: Context) : LinearLayout(context) {
    private val repository = BridgeLifeRepository(context)
    private val calendarManager = BridgeCalendarManager(context)
    private val planner = LifePlanningEngine(context, repository, calendarManager)
    private var autoPlanning = false

    private val primaryColor: Int
        get() = StyledResources(context).getColor(R.attr.colorPrimary)

    private val cardColor: Int
        get() = StyledResources(context).getColor(R.attr.cardBgColor)

    init {
        orientation = VERTICAL
        setPadding(dp(8), dp(5), dp(8), dp(3))
        refresh()
    }

    fun refresh() {
        removeAllViews()

        val app = context.applicationContext as HabitsApplication
        val seeded = BridgePersonalSeeder.seedIfEmpty(
            context = context,
            habitList = app.component.habitList,
            repository = repository
        )
        if (seeded) {
            BridgeSnapshotStore.save(
                context,
                BridgeSnapshotFactory.create(app.component.habitList)
            )
        }

        // Build a first plan only when none exists. Do it off the UI thread.
        if (calendarManager.planItems(todayString()).isEmpty() && !autoPlanning) {
            autoPlanning = true
            Thread {
                try {
                    planner.generateDay(System.currentTimeMillis())
                } finally {
                    post {
                        autoPlanning = false
                        refresh()
                    }
                }
            }.start()
        }

        val summary = planner.summarizeDay(System.currentTimeMillis())
        val planCount = calendarManager.planItems(todayString()).count {
            it.startMillis != null && it.endMillis != null
        }

        addView(statusCard(summary.freeMinutes, summary.unscheduledMinutes, planCount))
    }

    private fun statusCard(
        freeMinutes: Int,
        unscheduledMinutes: Int,
        planCount: Int
    ): MaterialCardView {
        val overloaded = unscheduledMinutes > 0
        val status = if (overloaded) {
            "−${formatDuration(unscheduledMinutes)} не помещается"
        } else {
            "${formatDuration(freeMinutes)} свободно"
        }
        val date = SimpleDateFormat("d MMM", Locale("ru")).format(Date())

        return MaterialCardView(context).apply {
            radius = dp(16).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = withAlpha(primaryColor, 35)
            setCardBackgroundColor(cardColor)

            addView(
                LinearLayout(context).apply {
                    orientation = HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setPadding(dp(12), dp(7), dp(7), dp(7))

                    addView(
                        LinearLayout(context).apply {
                            orientation = VERTICAL
                            addView(TextView(context).apply {
                                text = "Сегодня · $date"
                                textSize = 12f
                                alpha = 0.62f
                            })
                            addView(TextView(context).apply {
                                text = status
                                textSize = 16.5f
                                setTypeface(typeface, Typeface.BOLD)
                            })
                            if (planCount > 0) {
                                addView(TextView(context).apply {
                                    text = "$planCount привычек в плане"
                                    textSize = 11.5f
                                    alpha = 0.58f
                                })
                            }
                        },
                        LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                    )

                    addView(actionButton("Календарь") {
                        context.startActivity(Intent(context, CalendarActivity::class.java))
                    })
                    addView(actionButton("План") {
                        context.startActivity(Intent(context, PlanActivity::class.java))
                    })
                }
            )
        }
    }

    private fun actionButton(text: String, onClick: () -> Unit): MaterialButton =
        MaterialButton(context).apply {
            this.text = text
            isAllCaps = false
            textSize = 11.5f
            minWidth = 0
            cornerRadius = dp(13)
            backgroundTintList = ColorStateList.valueOf(primaryColor)
            setTextColor(Color.WHITE)
            setPadding(dp(8), 0, dp(8), 0)
            setOnClickListener { onClick() }
            layoutParams = LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(38)).apply {
                marginStart = dp(5)
            }
        }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == VISIBLE) refresh()
    }

    private fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun formatDuration(minutes: Int): String {
        if (minutes <= 0) return "0 мин"
        val h = minutes / 60
        val m = minutes % 60
        return when {
            h == 0 -> "$m мин"
            m == 0 -> "$h ч"
            else -> "$h ч $m мин"
        }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()
}
