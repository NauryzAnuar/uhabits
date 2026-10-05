package org.isoron.uhabits.activities.plan

import android.Manifest
import android.app.TimePickerDialog
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.R
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.bridge.BridgeCalendarManager
import org.isoron.uhabits.bridge.BridgeLifeRepository
import org.isoron.uhabits.bridge.BridgeSnapshotFactory
import org.isoron.uhabits.bridge.BridgeSnapshotStore
import org.isoron.uhabits.bridge.LifePlanningEngine
import org.isoron.uhabits.bridge.RoutineAnalyticsEngine
import org.isoron.uhabits.utils.StyledResources
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.roundToInt

/**
 * Human-first planner screen: Google busy time + Loop habits in one place.
 *
 * The previous version exposed too many controls at once. This version keeps
 * the same engine, but presents it in the order a person thinks:
 * date -> capacity -> timeline -> action -> insight -> settings.
 */
class CalendarActivity : AppCompatActivity() {

    private lateinit var app: HabitsApplication
    private lateinit var repository: BridgeLifeRepository
    private lateinit var calendarManager: BridgeCalendarManager
    private lateinit var planner: LifePlanningEngine
    private lateinit var routineAnalytics: RoutineAnalyticsEngine

    private lateinit var content: LinearLayout
    private lateinit var dayButton: MaterialButton
    private lateinit var weekButton: MaterialButton
    private lateinit var dateButton: MaterialButton
    private lateinit var accountButton: MaterialButton
    private lateinit var progress: ProgressBar

    private var selectedDay = System.currentTimeMillis()
    private var weekMode = false
    private var working = false

    private val primaryColor: Int
        get() = StyledResources(this).getColor(R.attr.colorPrimary)

    private val cardColor: Int
        get() = StyledResources(this).getColor(R.attr.cardBgColor)

    private val textColor: Int
        get() = StyledResources(this).getColor(R.attr.contrast100)

    private val permissionLauncher =
        registerForActivityResult(
            ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val granted =
                permissions[Manifest.permission.READ_CALENDAR] == true &&
                    permissions[Manifest.permission.WRITE_CALENDAR] == true

            if (granted) {
                chooseCalendar()
            } else {
                toast("Без доступа к календарю Loop не увидит занятое время.")
                render()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        app = application as HabitsApplication
        AndroidThemeSwitcher(this, app.component.preferences).apply()

        repository = BridgeLifeRepository(this)
        calendarManager = BridgeCalendarManager(this)
        planner = LifePlanningEngine(this, repository, calendarManager)
        routineAnalytics = RoutineAnalyticsEngine(app, repository)

        setContentView(createRoot())
        render()

        if (calendarManager.hasCalendarPermission() &&
            calendarManager.selectedCalendar() != null
        ) {
            refreshGoogleInBackground()
        }
    }

    override fun onResume() {
        super.onResume()
        if (::content.isInitialized) render()
    }

    override fun onSupportNavigateUp(): Boolean {
        finish()
        return true
    }

    private fun createRoot(): View {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val toolbar = Toolbar(this).apply {
            title = "Календарь"
            setBackgroundColor(primaryColor)
            setTitleTextColor(Color.WHITE)
            setSubtitleTextColor(withAlpha(Color.WHITE, 190))
        }

        root.addView(toolbar, matchWrap())
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)

        val top = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(10), dp(12), dp(6))
        }

        val modeRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        dayButton = smallSegment("День") {
            if (weekMode) {
                weekMode = false
                styleModeButtons()
                render()
                refreshGoogleInBackground()
            }
        }

        weekButton = smallSegment("Неделя") {
            if (!weekMode) {
                weekMode = true
                styleModeButtons()
                render()
                refreshGoogleInBackground()
            }
        }

        modeRow.addView(dayButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginEnd = dp(4) })
        modeRow.addView(weekButton, LinearLayout.LayoutParams(0, dp(42), 1f).apply { marginStart = dp(4) })
        top.addView(modeRow, matchWrap())

        val nav = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }

        val previous = navButton("‹") {
            selectedDay = addDays(selectedDay, if (weekMode) -7 else -1)
            render()
            refreshGoogleInBackground()
        }

        dateButton = MaterialButton(this).apply {
            isAllCaps = false
            textSize = 16f
            cornerRadius = dp(16)
            backgroundTintList = ColorStateList.valueOf(cardColor)
            setTextColor(textColor)
            setOnClickListener { jumpToToday() }
        }

        val next = navButton("›") {
            selectedDay = addDays(selectedDay, if (weekMode) 7 else 1)
            render()
            refreshGoogleInBackground()
        }

        nav.addView(previous, LinearLayout.LayoutParams(dp(52), dp(46)))
        nav.addView(dateButton, LinearLayout.LayoutParams(0, dp(46), 1f).apply {
            marginStart = dp(5)
            marginEnd = dp(5)
        })
        nav.addView(next, LinearLayout.LayoutParams(dp(52), dp(46)))
        top.addView(nav, matchWrap())

        progress = ProgressBar(this).apply {
            visibility = View.GONE
        }
        top.addView(
            progress,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                gravity = Gravity.CENTER_HORIZONTAL
                topMargin = dp(4)
            }
        )

        root.addView(top, matchWrap())

        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(2), dp(12), dp(30))
        }

        val scroll = ScrollView(this).apply {
            clipToPadding = false
            addView(
                content,
                ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        styleModeButtons()
        return root
    }

    private fun render() {
        if (!::content.isInitialized) return

        styleModeButtons()
        dateButton.text = if (weekMode) weekLabel(selectedDay) else dayLabel(selectedDay)
        content.removeAllViews()

        if (weekMode) renderWeek() else renderDay()
        addCalendarConnectionCard()
    }

    private fun renderDay() {
        val summary = planner.summarizeDay(selectedDay)
        addCapacityHero(summary)
        addPrimaryActions()

        val dayStart = localMidnight(selectedDay)
        val dayEnd = dayStart + LifePlanningEngine.DAY_MILLIS
        val google = calendarManager.busyBlocks(dayStart, dayEnd)
        val plan = calendarManager.planItems(dateString(dayStart))

        addSectionTitle("Расписание")

        val habitNames = app.component.habitList
            .mapNotNull { h -> h.uuid?.let { it to h.name } }
            .toMap()
        val sectionByHabit = routineSectionByHabit()

        val timelineBlocks = buildList {
            google.forEach { block ->
                add(
                    DayTimelineView.Block(
                        title = block.title ?: "Занято",
                        startMillis = block.startMillis,
                        endMillis = block.endMillis,
                        kind = DayTimelineView.Kind.GOOGLE
                    )
                )
            }

            plan.forEach { item ->
                val start = item.startMillis ?: return@forEach
                val end = item.endMillis ?: return@forEach
                add(
                    DayTimelineView.Block(
                        title = buildString {
                            sectionByHabit[item.targetUuid]?.let {
                                append(it)
                                append(" · ")
                            }
                            append(habitNames[item.targetUuid] ?: "Привычка")
                        },
                        startMillis = start,
                        endMillis = end,
                        kind = if (item.status == "DONE") {
                            DayTimelineView.Kind.DONE
                        } else {
                            DayTimelineView.Kind.LOOP
                        }
                    )
                )
            }
        }

        if (timelineBlocks.isEmpty()) {
            addEmptyCard(
                title = "Пока пусто",
                body = if (!calendarManager.hasCalendarPermission()) {
                    "Подключи Google Calendar."
                } else {
                    "Нажми «Спланировать день»."
                }
            )
        } else {
            addCard(
                MaterialCardView(this).apply {
                    radius = dp(20).toFloat()
                    cardElevation = 0f
                    strokeWidth = dp(1)
                    strokeColor = withAlpha(primaryColor, 34)
                    setCardBackgroundColor(cardColor)
                    addView(
                        DayTimelineView(this@CalendarActivity).apply {
                            setData(
                                dayStartMillis = dayStart,
                                startMinute = planner.planningStartMinute(),
                                endMinute = planner.planningEndMinute(),
                                blocks = timelineBlocks
                            )
                        },
                        ViewGroup.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.WRAP_CONTENT
                        )
                    )
                }
            )
        }

        val unscheduled = plan.filter { it.startMillis == null || it.endMillis == null }
        if (unscheduled.isNotEmpty()) {
            addSectionTitle("Не поместилось")
            val names = unscheduled.map { habitNames[it.targetUuid] ?: "Привычка" }
            addWarningCard(
                title = "Нужно решить ${unscheduled.size} ${word(unscheduled.size, "пункт", "пункта", "пунктов")}",
                body = names.take(4).joinToString(" • ") +
                    if (names.size > 4) " • ещё ${names.size - 4}" else ""
            )
        }

        addInsightCards()
    }

    private fun renderWeek() {
        val summaries = planner.weekSummaries(selectedDay)
        val totalFree = summaries.sumOf { it.freeMinutes }
        val totalOver = summaries.sumOf { it.unscheduledMinutes }

        addHeroCard(
            eyebrow = "Неделя",
            headline = if (totalOver > 0) {
                "Перегруз ${formatDuration(totalOver)}"
            } else {
                "Свободно ${formatDuration(totalFree)}"
            },
            supporting = if (totalOver > 0) {
                "Часть привычек не помещается."
            } else {
                "После занятых интервалов и плана Loop."
            }
        )

        addPrimaryActions()
        addSectionTitle("7 дней")

        summaries.forEach { summary ->
            val millis = parseDate(summary.date)
            addDayCard(summary, millis)
        }
    }

    private fun addCapacityHero(summary: LifePlanningEngine.DaySummary) {
        val overloaded = summary.unscheduledMinutes > 0
        val supporting = buildString {
            append("Занято ${formatDuration(summary.externalBusyMinutes)}")
            append("  •  Привычки ${formatDuration(summary.plannedMinutes)}")
            append("  •  ${summary.plannedCount} ${word(summary.plannedCount, "пункт", "пункта", "пунктов")}")
        }

        addHeroCard(
            eyebrow = if (overloaded) "Сегодня • перегруз" else "Вместимость дня",
            headline = if (overloaded) {
                "Не помещается ${formatDuration(summary.unscheduledMinutes)}"
            } else {
                "Свободно ${formatDuration(summary.freeMinutes)}"
            },
            supporting = supporting
        )
    }

    private fun addHeroCard(
        eyebrow: String,
        headline: String,
        supporting: String
    ) {
        addCard(
            MaterialCardView(this).apply {
                radius = dp(24).toFloat()
                cardElevation = dp(1).toFloat()
                strokeWidth = dp(1)
                strokeColor = withAlpha(primaryColor, 52)
                setCardBackgroundColor(cardColor)

                addView(
                    LinearLayout(this@CalendarActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(18), dp(17), dp(18), dp(17))

                        addView(
                            TextView(this@CalendarActivity).apply {
                                text = eyebrow
                                textSize = 13f
                                alpha = 0.66f
                            }
                        )

                        addView(
                            TextView(this@CalendarActivity).apply {
                                text = headline
                                textSize = 25f
                                setTypeface(typeface, Typeface.BOLD)
                                setPadding(0, dp(4), 0, dp(5))
                            }
                        )

                        addView(
                            TextView(this@CalendarActivity).apply {
                                text = supporting
                                textSize = 14f
                                alpha = 0.78f
                            }
                        )
                    }
                )
            }
        )
    }

    private fun addPrimaryActions() {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(2), 0, dp(8))
        }

        val planButton = MaterialButton(this).apply {
            text = if (weekMode) "Спланировать неделю" else "Спланировать день"
            isAllCaps = false
            cornerRadius = dp(18)
            backgroundTintList = ColorStateList.valueOf(primaryColor)
            setTextColor(Color.WHITE)
            setOnClickListener { generatePlan() }
        }

        val syncButton = MaterialButton(this).apply {
            text = "Google ↻"
            isAllCaps = false
            cornerRadius = dp(18)
            backgroundTintList = ColorStateList.valueOf(cardColor)
            strokeWidth = dp(1)
            strokeColor = ColorStateList.valueOf(withAlpha(primaryColor, 70))
            setTextColor(textColor)
            setOnClickListener { syncGoogle() }
        }

        row.addView(planButton, LinearLayout.LayoutParams(0, dp(50), 1f).apply { marginEnd = dp(5) })
        row.addView(syncButton, LinearLayout.LayoutParams(dp(112), dp(50)).apply { marginStart = dp(5) })
        content.addView(row, matchWrap())
    }

    private fun addInsightCards() {
        addSectionTitle("Что важно")

        addInfoCard(
            title = "Рекомендация",
            body = planner.recommendation(selectedDay)
        )

        routineAnalytics.weakestBreakpoint(selectedDay)?.let { breakpoint ->
            addInfoCard(
                title = "Слабый переход в рутине",
                body = "${breakpoint.routineName}: ${breakpoint.fromHabitName} → ${breakpoint.toHabitName} · " +
                    "${breakpoint.breaks}/${breakpoint.opportunities}"
            )
        }
    }

    private fun addDayCard(
        summary: LifePlanningEngine.DaySummary,
        millis: Long
    ) {
        val capacity = summary.activeMinutes.coerceAtLeast(1)
        val used = (summary.externalBusyMinutes + summary.plannedMinutes).coerceAtMost(capacity)
        val progressValue = ((used.toDouble() / capacity) * 100).roundToInt().coerceIn(0, 100)

        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = withAlpha(primaryColor, 30)
            setCardBackgroundColor(cardColor)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                selectedDay = millis
                weekMode = false
                render()
                refreshGoogleInBackground()
            }

            addView(
                LinearLayout(this@CalendarActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(15), dp(13), dp(15), dp(13))

                    addView(
                        LinearLayout(this@CalendarActivity).apply {
                            orientation = LinearLayout.HORIZONTAL
                            gravity = Gravity.CENTER_VERTICAL

                            addView(
                                TextView(this@CalendarActivity).apply {
                                    text = shortDayLabel(millis)
                                    textSize = 16f
                                    setTypeface(typeface, Typeface.BOLD)
                                },
                                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)
                            )

                            addView(
                                TextView(this@CalendarActivity).apply {
                                    text = if (summary.unscheduledMinutes > 0) {
                                        "−${formatDuration(summary.unscheduledMinutes)}"
                                    } else {
                                        "+${formatDuration(summary.freeMinutes)}"
                                    }
                                    textSize = 14f
                                    setTypeface(typeface, Typeface.BOLD)
                                    setTextColor(
                                        if (summary.unscheduledMinutes > 0) {
                                            Color.rgb(183, 28, 28)
                                        } else primaryColor
                                    )
                                }
                            )
                        }
                    )

                    addView(
                        ProgressBar(
                            this@CalendarActivity,
                            null,
                            android.R.attr.progressBarStyleHorizontal
                        ).apply {
                            max = 100
                            progress = progressValue
                            progressTintList = ColorStateList.valueOf(primaryColor)
                            progressBackgroundTintList = ColorStateList.valueOf(withAlpha(textColor, 24))
                        },
                        LinearLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            dp(6)
                        ).apply { topMargin = dp(9) }
                    )

                    addView(
                        TextView(this@CalendarActivity).apply {
                            text = "Занято ${formatDuration(summary.externalBusyMinutes)}  •  Привычки ${formatDuration(summary.plannedMinutes)}"
                            textSize = 12.5f
                            alpha = 0.68f
                            setPadding(0, dp(7), 0, 0)
                        }
                    )
                }
            )
        }

        addCard(card)
    }

    private fun addCalendarConnectionCard() {
        addSectionTitle("Google Calendar")

        val connected = calendarManager.hasCalendarPermission() &&
            calendarManager.selectedCalendar() != null

        val selected = calendarManager.selectedCalendar()
        val title = if (connected) {
            selected?.accountName ?: "Google Calendar"
        } else {
            "Подключить календарь"
        }

        val body = if (connected) {
            "${selected?.displayName ?: "Календарь"}"
        } else {
            "Занятость + синхронизация плана"
        }

        val card = MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = withAlpha(primaryColor, 34)
            setCardBackgroundColor(cardColor)

            addView(
                LinearLayout(this@CalendarActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(15), dp(13), dp(15), dp(13))

                    addView(
                        TextView(this@CalendarActivity).apply {
                            text = title
                            textSize = 16f
                            setTypeface(typeface, Typeface.BOLD)
                        }
                    )
                    addView(
                        TextView(this@CalendarActivity).apply {
                            text = body
                            textSize = 13f
                            alpha = 0.72f
                            setPadding(0, dp(4), 0, dp(9))
                        }
                    )

                    accountButton = MaterialButton(this@CalendarActivity).apply {
                        text = if (connected) "Сменить календарь" else "Подключить"
                        isAllCaps = false
                        cornerRadius = dp(16)
                        backgroundTintList = ColorStateList.valueOf(cardColor)
                        strokeWidth = dp(1)
                        strokeColor = ColorStateList.valueOf(withAlpha(primaryColor, 72))
                        setTextColor(primaryColor)
                        setOnClickListener { onAccountClick() }
                    }
                    addView(accountButton, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))

                    val window = MaterialButton(this@CalendarActivity).apply {
                        text = "Активный день ${formatMinute(planner.planningStartMinute())}–${formatMinute(planner.planningEndMinute())}"
                        isAllCaps = false
                        backgroundTintList = ColorStateList.valueOf(Color.TRANSPARENT)
                        setTextColor(withAlpha(textColor, 190))
                        setOnClickListener { editPlanningWindow() }
                    }
                    addView(window, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(44)))
                }
            )
        }

        addCard(card)
    }

    private fun onAccountClick() {
        if (!calendarManager.hasCalendarPermission()) {
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.READ_CALENDAR,
                    Manifest.permission.WRITE_CALENDAR
                )
            )
        } else {
            chooseCalendar()
        }
    }

    private fun chooseCalendar() {
        val calendars = calendarManager.listWritableGoogleCalendars()
        if (calendars.isEmpty()) {
            AlertDialog.Builder(this)
                .setTitle("Google Calendar")
                .setMessage("На телефоне не найден доступный Google Calendar. Добавь Google-аккаунт в Android.")
                .setNegativeButton("Отмена", null)
                .setPositiveButton("Добавить аккаунт") { _, _ ->
                    try {
                        startActivity(
                            Intent(Settings.ACTION_ADD_ACCOUNT).apply {
                                putExtra("account_types", arrayOf("com.google"))
                            }
                        )
                    } catch (_: Exception) {
                        startActivity(Intent(Settings.ACTION_SYNC_SETTINGS))
                    }
                }
                .show()
            return
        }

        val labels = calendars.map {
            "${it.accountName} • ${it.displayName}${if (it.isPrimary) " • основной" else ""}"
        }.toTypedArray()

        AlertDialog.Builder(this)
            .setTitle("Календарь Loop")
            .setItems(labels) { _, which ->
                calendarManager.selectCalendar(calendars[which])
                render()
                refreshGoogleInBackground()
            }
            .show()
    }

    private fun refreshGoogleInBackground() {
        if (working || !calendarManager.hasCalendarPermission() || calendarManager.selectedCalendar() == null) {
            return
        }

        val range = visibleRange()
        setWorking(true)
        Thread {
            try {
                calendarManager.refreshFromGoogle(range.first, range.second)
                runOnUiThread {
                    setWorking(false)
                    render()
                    syncBridgeSnapshot()
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setWorking(false)
                    toast("Не удалось обновить Google Calendar: ${e.message ?: "ошибка"}")
                    render()
                }
            }
        }.start()
    }

    private fun generatePlan() {
        if (working) return
        setWorking(true)

        Thread {
            try {
                if (weekMode) planner.generateWeek(selectedDay) else planner.generateDay(selectedDay)

                val canSync = calendarManager.hasCalendarPermission() &&
                    calendarManager.selectedCalendar() != null
                val synced = if (canSync) {
                    if (weekMode) planner.syncWeekToGoogle(selectedDay)
                    else planner.syncDayToGoogle(selectedDay)
                } else {
                    0
                }

                if (canSync) {
                    val range = visibleRange()
                    calendarManager.refreshFromGoogle(range.first, range.second)
                }

                runOnUiThread {
                    setWorking(false)
                    render()
                    syncBridgeSnapshot()
                    toast(
                        if (canSync) {
                            if (weekMode) "Неделя обновлена · Google $synced" else "День обновлён · Google $synced"
                        } else {
                            if (weekMode) "Неделя спланирована" else "День спланирован"
                        }
                    )
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setWorking(false)
                    toast("Не удалось составить план: ${e.message ?: "ошибка"}")
                }
            }
        }.start()
    }

    private fun syncGoogle() {
        if (!calendarManager.hasCalendarPermission()) {
            onAccountClick()
            return
        }
        if (calendarManager.selectedCalendar() == null) {
            chooseCalendar()
            return
        }
        if (working) return

        setWorking(true)
        Thread {
            try {
                val count = if (weekMode) {
                    planner.syncWeekToGoogle(selectedDay)
                } else {
                    planner.syncDayToGoogle(selectedDay)
                }

                val range = visibleRange()
                calendarManager.refreshFromGoogle(range.first, range.second)

                runOnUiThread {
                    setWorking(false)
                    render()
                    syncBridgeSnapshot()
                    toast("Синхронизировано: $count")
                }
            } catch (e: Exception) {
                runOnUiThread {
                    setWorking(false)
                    toast("Синхронизация не удалась: ${e.message ?: "ошибка"}")
                }
            }
        }.start()
    }

    private fun editPlanningWindow() {
        var start = planner.planningStartMinute()
        var end = planner.planningEndMinute()

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        val startButton = MaterialButton(this).apply { isAllCaps = false }
        val endButton = MaterialButton(this).apply { isAllCaps = false }

        fun refreshLabels() {
            startButton.text = "Начало: ${formatMinute(start)}"
            endButton.text = "Конец: ${formatMinute(end)}"
        }
        refreshLabels()

        startButton.setOnClickListener {
            TimePickerDialog(
                this,
                { _, h, m ->
                    start = h * 60 + m
                    refreshLabels()
                },
                start / 60,
                start % 60,
                true
            ).show()
        }

        endButton.setOnClickListener {
            TimePickerDialog(
                this,
                { _, h, m ->
                    end = h * 60 + m
                    refreshLabels()
                },
                end / 60,
                end % 60,
                true
            ).show()
        }

        box.addView(startButton)
        box.addView(endButton)

        AlertDialog.Builder(this)
            .setTitle("Активный день")
            .setMessage("Окно автопланирования")
            .setView(box)
            .setNegativeButton("Отмена", null)
            .setPositiveButton("Сохранить") { _, _ ->
                if (end <= start) {
                    toast("Конец должен быть позже начала")
                } else {
                    planner.setPlanningWindow(start, end)
                    render()
                }
            }
            .show()
    }

    private fun addSectionTitle(text: String) {
        content.addView(
            TextView(this).apply {
                this.text = text
                textSize = 19f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(dp(2), dp(17), dp(2), dp(8))
            }
        )
    }

    private fun addInfoCard(title: String, body: String) {
        addCard(
            MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = withAlpha(primaryColor, 30)
                setCardBackgroundColor(cardColor)
                addView(
                    LinearLayout(this@CalendarActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(15), dp(13), dp(15), dp(13))
                        addView(
                            TextView(this@CalendarActivity).apply {
                                text = title
                                textSize = 16f
                                setTypeface(typeface, Typeface.BOLD)
                            }
                        )
                        addView(
                            TextView(this@CalendarActivity).apply {
                                text = body
                                textSize = 13.5f
                                alpha = 0.78f
                                setPadding(0, dp(5), 0, 0)
                            }
                        )
                    }
                )
            }
        )
    }

    private fun addWarningCard(title: String, body: String) {
        val warning = Color.rgb(183, 28, 28)
        addCard(
            MaterialCardView(this).apply {
                radius = dp(18).toFloat()
                cardElevation = 0f
                strokeWidth = dp(1)
                strokeColor = withAlpha(warning, 92)
                setCardBackgroundColor(cardColor)
                addView(
                    LinearLayout(this@CalendarActivity).apply {
                        orientation = LinearLayout.VERTICAL
                        setPadding(dp(15), dp(13), dp(15), dp(13))
                        addView(
                            TextView(this@CalendarActivity).apply {
                                text = title
                                textSize = 16f
                                setTypeface(typeface, Typeface.BOLD)
                                setTextColor(warning)
                            }
                        )
                        addView(
                            TextView(this@CalendarActivity).apply {
                                text = body
                                textSize = 13.5f
                                alpha = 0.78f
                                setPadding(0, dp(5), 0, 0)
                            }
                        )
                    }
                )
            }
        )
    }

    private fun addEmptyCard(title: String, body: String) {
        addInfoCard(title, body)
    }

    private fun addCard(card: View) {
        content.addView(
            card,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                bottomMargin = dp(9)
            }
        )
    }

    private fun smallSegment(text: String, action: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            cornerRadius = dp(16)
            setOnClickListener { action() }
        }

    private fun navButton(text: String, action: () -> Unit): MaterialButton =
        MaterialButton(this).apply {
            this.text = text
            textSize = 24f
            isAllCaps = false
            cornerRadius = dp(16)
            backgroundTintList = ColorStateList.valueOf(cardColor)
            setTextColor(textColor)
            setOnClickListener { action() }
        }

    private fun styleModeButtons() {
        if (!::dayButton.isInitialized) return
        styleSegment(dayButton, !weekMode)
        styleSegment(weekButton, weekMode)
    }

    private fun styleSegment(button: MaterialButton, selected: Boolean) {
        button.backgroundTintList = ColorStateList.valueOf(
            if (selected) primaryColor else cardColor
        )
        button.setTextColor(if (selected) Color.WHITE else textColor)
        button.strokeWidth = if (selected) 0 else dp(1)
        button.strokeColor = ColorStateList.valueOf(withAlpha(primaryColor, 50))
    }

    private fun setWorking(value: Boolean) {
        working = value
        progress.visibility = if (value) View.VISIBLE else View.GONE
        dayButton.isEnabled = !value
        weekButton.isEnabled = !value
        if (::accountButton.isInitialized) accountButton.isEnabled = !value
    }

    private fun routineSectionByHabit(): Map<String, String> {
        val life = repository.exportJson()
        val routineNames = mutableMapOf<String, String>()
        val routines = life.optJSONArray("routines")
        if (routines != null) {
            for (i in 0 until routines.length()) {
                val item = routines.optJSONObject(i) ?: continue
                routineNames[item.optString("uuid")] = item.optString("name")
            }
        }

        val grouped = mutableMapOf<String, MutableList<Pair<Int, String>>>()
        val steps = life.optJSONArray("routineSteps")
        if (steps != null) {
            for (i in 0 until steps.length()) {
                val step = steps.optJSONObject(i) ?: continue
                if (step.optString("target_type") != "HABIT") continue
                val habitUuid = step.optString("target_uuid")
                val routineName = routineNames[step.optString("routine_uuid")].orEmpty()
                grouped.getOrPut(habitUuid) { mutableListOf() }
                    .add(step.optInt("position") to routineName)
            }
        }

        return grouped.mapValues { (_, values) ->
            val name = values.sortedBy { it.first }
                .map { it.second }
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
            shortRoutineSection(name)
        }.filterValues { it.isNotBlank() }
    }

    private fun shortRoutineSection(name: String): String {
        val n = name.lowercase(Locale.getDefault()).replace('ё', 'е')
        return when {
            n.contains("утро") -> "Утро"
            n.contains("рабоч") || n.contains("работ") -> "Работа"
            n.contains("семейн") -> "Вечер"
            n.contains("сном") || n.contains("сон") -> "Перед сном"
            else -> name
        }
    }

    private fun syncBridgeSnapshot() {
        BridgeSnapshotStore.save(
            this,
            BridgeSnapshotFactory.create(app.component.habitList)
        )
    }

    private fun visibleRange(): Pair<Long, Long> = if (weekMode) {
        val start = mondayStart(selectedDay)
        start to start + 7 * LifePlanningEngine.DAY_MILLIS
    } else {
        val start = localMidnight(selectedDay)
        start to start + LifePlanningEngine.DAY_MILLIS
    }

    private fun jumpToToday() {
        selectedDay = System.currentTimeMillis()
        render()
        refreshGoogleInBackground()
    }

    private fun addDays(millis: Long, days: Int): Long =
        Calendar.getInstance().apply {
            timeInMillis = millis
            add(Calendar.DAY_OF_MONTH, days)
        }.timeInMillis

    private fun mondayStart(millis: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = localMidnight(millis)
            firstDayOfWeek = Calendar.MONDAY
            val delta = (get(Calendar.DAY_OF_WEEK) - Calendar.MONDAY + 7) % 7
            add(Calendar.DAY_OF_MONTH, -delta)
        }.timeInMillis

    private fun localMidnight(millis: Long): Long =
        Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    private fun dayLabel(millis: Long): String =
        SimpleDateFormat("EEE, d MMMM", Locale("ru")).format(Date(millis))

    private fun shortDayLabel(millis: Long): String =
        SimpleDateFormat("EEE, d MMM", Locale("ru")).format(Date(millis))

    private fun weekLabel(millis: Long): String {
        val start = mondayStart(millis)
        val end = start + 6 * LifePlanningEngine.DAY_MILLIS
        val formatter = SimpleDateFormat("d MMM", Locale("ru"))
        return "${formatter.format(Date(start))} – ${formatter.format(Date(end))}"
    }

    private fun dateString(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(millis))

    private fun parseDate(value: String): Long =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.parse(value)?.time ?: System.currentTimeMillis()

    private fun formatMinute(minute: Int): String =
        "%02d:%02d".format(minute / 60, minute % 60)

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

    private fun word(n: Int, one: String, few: String, many: String): String {
        val mod100 = n % 100
        val mod10 = n % 10
        return when {
            mod100 in 11..14 -> many
            mod10 == 1 -> one
            mod10 in 2..4 -> few
            else -> many
        }
    }

    private fun matchWrap() =
        LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).roundToInt()

    private fun toast(text: String) {
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show()
    }
}
