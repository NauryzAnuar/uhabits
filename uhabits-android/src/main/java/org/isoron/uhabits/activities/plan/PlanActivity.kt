package org.isoron.uhabits.activities.plan

import android.app.TimePickerDialog
import android.content.res.ColorStateList
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.util.TypedValue
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.Toolbar
import com.google.android.material.button.MaterialButton
import com.google.android.material.card.MaterialCardView
import org.isoron.platform.time.getToday
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.activities.AndroidThemeSwitcher
import org.isoron.uhabits.bridge.BridgeLifeDatabase
import org.isoron.uhabits.bridge.BridgeLifeRepository
import org.isoron.uhabits.bridge.BridgePersonalSeeder
import org.isoron.uhabits.bridge.BridgeSnapshotFactory
import org.isoron.uhabits.bridge.BridgeSnapshotStore
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.NumericalHabitType
import org.json.JSONObject
import kotlin.math.roundToInt

class PlanActivity : AppCompatActivity() {

    private lateinit var app:
            HabitsApplication

    private lateinit var repository:
            BridgeLifeRepository

    private lateinit var content:
            LinearLayout

    private lateinit var todayButton:
            MaterialButton

    private lateinit var routinesButton:
            MaterialButton

    private lateinit var goalsButton:
            MaterialButton

    private var currentTab =
        Tab.TODAY

    override fun onCreate(
        savedInstanceState: Bundle?
    ) {
        super.onCreate(savedInstanceState)

        app =
            application as HabitsApplication

        AndroidThemeSwitcher(
            this,
            app.component.preferences
        ).apply()

        repository =
            BridgeLifeRepository(this)

        currentTab =
            when (intent.getStringExtra(EXTRA_TAB)) {
                TAB_ROUTINES -> Tab.ROUTINES
                TAB_GOALS -> Tab.GOALS
                else -> Tab.TODAY
            }

        val seeded =
            BridgePersonalSeeder.seedIfEmpty(
                context = this,
                habitList = app.component.habitList,
                repository = repository
            )

        if (seeded) {
            BridgeSnapshotStore.save(
                this,
                BridgeSnapshotFactory.create(
                    app.component.habitList
                )
            )
        }

        setContentView(
            createRootView()
        )

        render()
    }

    override fun onResume() {
        super.onResume()

        if (::content.isInitialized) {
            render()
        }
    }

    override fun onSupportNavigateUp():
            Boolean {
        finish()
        return true
    }

    private fun createRootView():
            View {
        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL
            }

        val toolbar =
            Toolbar(this).apply {
                title = "План"
                subtitle =
                    "Loop 0.3"

                setBackgroundColor(
                    resolveThemeColor(
                        androidx.appcompat.R.attr
                            .colorPrimary,
                        Color.DKGRAY
                    )
                )

                setTitleTextColor(
                    Color.WHITE
                )

                setSubtitleTextColor(
                    Color.LTGRAY
                )
            }

        root.addView(
            toolbar,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams
                    .MATCH_PARENT,
                ViewGroup.LayoutParams
                    .WRAP_CONTENT
            )
        )

        setSupportActionBar(toolbar)

        supportActionBar
            ?.setDisplayHomeAsUpEnabled(
                true
            )

        val tabs =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL

                setPadding(
                    dp(8),
                    dp(8),
                    dp(8),
                    dp(8)
                )
            }

        todayButton =
            createTabButton(
                "Сегодня",
                Tab.TODAY
            )

        routinesButton =
            createTabButton(
                "Рутины",
                Tab.ROUTINES
            )

        goalsButton =
            createTabButton(
                "Цели",
                Tab.GOALS
            )

        tabs.addView(
            todayButton,
            tabLayoutParams()
        )

        tabs.addView(
            routinesButton,
            tabLayoutParams()
        )

        tabs.addView(
            goalsButton,
            tabLayoutParams()
        )

        root.addView(
            tabs,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams
                    .MATCH_PARENT,
                ViewGroup.LayoutParams
                    .WRAP_CONTENT
            )
        )

        content =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(12),
                    dp(4),
                    dp(12),
                    dp(24)
                )
            }

        val scroll =
            ScrollView(this).apply {
                addView(
                    content,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams
                            .MATCH_PARENT,
                        ViewGroup.LayoutParams
                            .WRAP_CONTENT
                    )
                )
            }

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams
                    .MATCH_PARENT,
                0,
                1f
            )
        )

        updateTabButtons()

        return root
    }

    private fun createTabButton(
        text: String,
        tab: Tab
    ): MaterialButton {
        return MaterialButton(this).apply {
            this.text = text
            isAllCaps = false
            cornerRadius = dp(18)
            insetTop = 0
            insetBottom = 0
            setOnClickListener {
                currentTab = tab
                updateTabButtons()
                render()
            }
        }
    }

    private fun tabLayoutParams():
            LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            0,
            ViewGroup.LayoutParams
                .WRAP_CONTENT,
            1f
        ).apply {
            marginStart =
                dp(2)

            marginEnd =
                dp(2)
        }
    }

    private fun updateTabButtons() {
        styleTab(todayButton, currentTab == Tab.TODAY)
        styleTab(routinesButton, currentTab == Tab.ROUTINES)
        styleTab(goalsButton, currentTab == Tab.GOALS)
    }

    private fun styleTab(
        button: MaterialButton,
        selected: Boolean
    ) {
        val primary = resolveThemeColor(
            org.isoron.uhabits.R.attr.colorPrimary,
            Color.DKGRAY
        )
        val surface = resolveThemeColor(
            org.isoron.uhabits.R.attr.cardBgColor,
            Color.WHITE
        )
        val text = resolveThemeColor(
            org.isoron.uhabits.R.attr.contrast100,
            Color.BLACK
        )

        button.backgroundTintList =
            ColorStateList.valueOf(if (selected) primary else surface)
        button.setTextColor(if (selected) Color.WHITE else text)
        button.strokeWidth = if (selected) 0 else dp(1)
        button.strokeColor = ColorStateList.valueOf(withAlpha(primary, 48))
    }

    private fun render() {
        content.removeAllViews()

        when (currentTab) {
            Tab.TODAY ->
                renderToday()

            Tab.ROUTINES ->
                renderRoutines()

            Tab.GOALS ->
                renderGoals()
        }
    }

    // ------------------------------------------------
    // TODAY
    // ------------------------------------------------

    private fun renderToday() {
        val life =
            repository.exportJson()

        val spheres =
            life.objects(
                "lifeSpheres"
            )

        val sphereByUuid =
            spheres.associateBy {
                it.optString("uuid")
            }

        val primarySphereByHabit =
            life.objects(
                "habitSpheres"
            )
                .filter {
                    it.optString(
                        "relation"
                    ) == "PRIMARY"
                }
                .associateBy {
                    it.optString(
                        "habit_uuid"
                    )
                }

        val planningByHabit =
            life.objects(
                "habitPlanning"
            )
                .associateBy {
                    it.optString(
                        "habit_uuid"
                    )
                }

        val scheduleByHabit =
            life.objects("schedules")
                .filter {
                    it.optString("target_type") == "HABIT"
                }
                .associateBy {
                    it.optString("target_uuid")
                }

        val anchorByHabit =
            life.objects("anchors")
                .filter {
                    it.optString("target_type") == "HABIT"
                }
                .associateBy {
                    it.optString("target_uuid")
                }

        val habits =
            activeHabits()

        var weeklyMinutes =
            0.0

        var withoutDuration =
            0

        habits.forEach { habit ->
            val uuid =
                habit.uuid
                    ?: return@forEach

            val planning =
                planningByHabit[uuid]

            val duration =
                planning
                    ?.nullableInt(
                        "typical_duration_minutes"
                    )

            val role =
                planning?.optString(
                    "role",
                    "CORE"
                ) ?: "CORE"

            if (duration == null) {
                if (role != "TRACKING") {
                    withoutDuration++
                }
            } else {
                weeklyMinutes +=
                    duration *
                            habit.frequency
                                .toDouble() *
                            7.0
            }
        }

        addTitle(
            "План дня"
        )

        addSphereWheel(
            life = life,
            habits = habits
        )

        addInfo(
            "Нагрузка привычек",
            buildString {
                append(
                    weeklyMinutes
                        .roundToInt()
                )

                append(
                    " мин/нед"
                )

                append(
                    "  •  ≈ "
                )

                append(
                    (
                            weeklyMinutes /
                                    7.0
                            ).roundToInt()
                )

                append(
                    " мин/день"
                )

                if (
                    withoutDuration > 0
                ) {
                    append(
                        "\n"
                    )

                    append(
                        "Без оценки времени: "
                    )

                    append(
                        withoutDuration
                    )
                }
            }
        )

        addActionButton(
            "Сферы жизни (${spheres.size})"
        ) {
            showSpheresDialog()
        }

        addSubtitle(
            "Привычки"
        )

        habits.forEach { habit ->
            val uuid =
                habit.uuid
                    ?: return@forEach

            val relation =
                primarySphereByHabit[
                    uuid
                ]

            val sphereUuid =
                relation?.optString(
                    "sphere_uuid"
                )

            val sphereName =
                if (
                    sphereUuid.isNullOrBlank()
                ) {
                    "Без сферы"
                } else {
                    sphereByUuid[
                        sphereUuid
                    ]?.optString(
                        "name"
                    ) ?: "Без сферы"
                }

            val planning =
                planningByHabit[
                    uuid
                ]

            val minDuration =
                planning?.nullableInt(
                    "min_duration_minutes"
                )

            val typicalDuration =
                planning?.nullableInt(
                    "typical_duration_minutes"
                )

            val maxDuration =
                planning?.nullableInt(
                    "max_duration_minutes"
                )

            val durationText =
                if (
                    minDuration == null &&
                    typicalDuration == null &&
                    maxDuration == null
                ) {
                    "длительность не задана"
                } else {
                    "${minDuration ?: 0} / ${typicalDuration ?: 0} / ${maxDuration ?: 0} мин"
                }

            val startMinute =
                scheduleByHabit[uuid]
                    ?.nullableInt(
                        "preferred_start_minute"
                    )

            val anchorText =
                anchorByHabit[uuid]
                    ?.optString(
                        "custom_text"
                    )
                    ?.takeIf { it.isNotBlank() }

            val startText =
                when {
                    startMinute != null && anchorText != null ->
                        "≈${formatMinute(startMinute)} · $anchorText"

                    startMinute != null ->
                        "≈${formatMinute(startMinute)}"

                    anchorText != null ->
                        anchorText

                    else ->
                        "гибко"
                }

            val frequency =
                frequencyText(
                    habit
                )

            val todayStatus =
                if (
                    habit.isCompletedToday()
                ) {
                    "✓ сегодня"
                } else {
                    "○ сегодня"
                }

            addRow(
                habit.name,
                "$sphereName\n" +
                    "Старт: $startText • мин/обычно/макс: $durationText\n" +
                    "$frequency • $todayStatus"
            ) {
                showHabitPlanningDialog(
                    habit
                )
            }
        }
    }

    private fun addSphereWheel(
        life: JSONObject,
        habits: List<Habit>
    ) {
        val spheres =
            life.objects("lifeSpheres")
                .filter {
                    it.optInt("archived", 0) == 0
                }

        val primaryLinks =
            life.objects("habitSpheres")
                .filter {
                    it.optString("relation") == "PRIMARY"
                }

        val today = getToday()
        val from = today.minus(27)
        val windowDays = 28.0

        fun habitScore(habit: Habit): Float {
            val expected =
                windowDays *
                    habit.frequency.numerator.toDouble() /
                    habit.frequency.denominator.toDouble()

            if (expected <= 0.0) return 0f

            val successful =
                habit.originalEntries
                    .getByInterval(from, today)
                    .count { entry ->
                        if (habit.isNumerical) {
                            if (
                                entry.value == Entry.UNKNOWN ||
                                entry.value == Entry.SKIP
                            ) {
                                false
                            } else {
                                val value =
                                    entry.value / 1000.0

                                when (habit.targetType) {
                                    NumericalHabitType.AT_LEAST ->
                                        value >= habit.targetValue

                                    NumericalHabitType.AT_MOST ->
                                        value <= habit.targetValue
                                }
                            }
                        } else {
                            entry.value == Entry.YES_MANUAL
                        }
                    }

            return (
                successful.toDouble() / expected
            )
                .coerceIn(0.0, 1.0)
                .toFloat()
        }

        fun shortName(key: String, full: String): String =
            when (key) {
                "health" -> "Здоровье"
                "emotional_wellbeing" -> "Эмоции"
                "relationships" -> "Отношения"
                "work_career" -> "Работа"
                "learning" -> "Развитие"
                "finances" -> "Финансы"
                "home_environment" -> "Дом"
                "leisure" -> "Отдых"
                "spirituality" -> "Духовность"
                "community" -> "Вклад"
                else -> full.take(12)
            }

        val habitByUuid =
            habits.mapNotNull { habit ->
                habit.uuid?.let { uuid ->
                    uuid to habit
                }
            }.toMap()

        val wheelItems =
            spheres.map { sphere ->
                val sphereUuid =
                    sphere.optString("uuid")

                val linkedHabits =
                    primaryLinks
                        .filter {
                            it.optString("sphere_uuid") ==
                                sphereUuid
                        }
                        .mapNotNull {
                            habitByUuid[
                                it.optString("habit_uuid")
                            ]
                        }

                val score =
                    if (linkedHabits.isEmpty()) {
                        null
                    } else {
                        linkedHabits
                            .map(::habitScore)
                            .average()
                            .toFloat()
                    }

                SphereWheelView.Item(
                    label = shortName(
                        sphere.optString("system_key"),
                        sphere.optString("name")
                    ),
                    score = score,
                    habitCount = linkedHabits.size
                )
            }

        addSubtitle(
            "Баланс сфер · 28 дней"
        )

        content.addView(
            SphereWheelView(this).apply {
                setItems(wheelItems)
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(390)
            ).apply {
                bottomMargin = dp(8)
            }
        )

        val scored =
            wheelItems.filter { it.score != null }

        if (scored.isNotEmpty()) {
            val best = scored.maxByOrNull { it.score ?: 0f }
            val weakest = scored.minByOrNull { it.score ?: 0f }

            addInfo(
                "Сейчас",
                buildString {
                    if (best != null) {
                        append("Сильнее: ${best.label} · ${(best.score!! * 100).roundToInt()}%")
                    }
                    if (weakest != null && weakest != best) {
                        append("  •  Слабее: ${weakest.label} · ${(weakest.score!! * 100).roundToInt()}%")
                    }
                }
            )
        }
    }

    private fun showHabitPlanningDialog(
        habit: Habit
    ) {
        val habitUuid =
            habit.uuid

        if (habitUuid == null) {
            toast(
                "У привычки нет UUID"
            )

            return
        }

        val life =
            repository.exportJson()

        val spheres =
            life.objects(
                "lifeSpheres"
            )
                .filter {
                    it.optInt(
                        "archived",
                        0
                    ) == 0
                }

        val primary =
            life.objects(
                "habitSpheres"
            )
                .lastOrNull {
                    it.optString(
                        "habit_uuid"
                    ) == habitUuid &&
                            it.optString(
                                "relation"
                            ) == "PRIMARY"
                }

        val planning =
            life.objects(
                "habitPlanning"
            )
                .lastOrNull {
                    it.optString(
                        "habit_uuid"
                    ) == habitUuid
                }

        val schedule =
            life.objects("schedules")
                .lastOrNull {
                    it.optString("target_type") == "HABIT" &&
                        it.optString("target_uuid") == habitUuid
                }

        val anchor =
            life.objects("anchors")
                .lastOrNull {
                    it.optString("target_type") == "HABIT" &&
                        it.optString("target_uuid") == habitUuid
                }

        var selectedStartMinute =
            schedule?.nullableInt(
                "preferred_start_minute"
            )

        val anchorText =
            anchor?.optString(
                "custom_text"
            )?.takeIf { it.isNotBlank() }

        val box =
            dialogBox()

        box.addView(
            dialogLabel(
                "Сфера"
            )
        )

        val sphereNames =
            mutableListOf(
                "Без сферы"
            )

        sphereNames.addAll(
            spheres.map {
                it.optString(
                    "name"
                )
            }
        )

        val sphereSpinner =
            spinner(
                sphereNames
            )

        val currentSphereUuid =
            primary?.optString(
                "sphere_uuid"
            )

        val currentSphereIndex =
            spheres.indexOfFirst {
                it.optString(
                    "uuid"
                ) ==
                        currentSphereUuid
            }

        sphereSpinner.setSelection(
            if (
                currentSphereIndex < 0
            ) {
                0
            } else {
                currentSphereIndex + 1
            }
        )

        box.addView(
            sphereSpinner
        )

        box.addView(
            dialogLabel(
                "Роль привычки"
            )
        )

        val roleCodes =
            listOf(
                "CORE",
                "SUPPORT",
                "OPTIONAL",
                "TRACKING"
            )

        val roleLabels =
            listOf(
                "Основная",
                "Поддерживающая",
                "Опциональная",
                "Показатель / внутри другого действия"
            )

        val roleSpinner =
            spinner(
                roleLabels
            )

        val currentRole =
            planning?.optString(
                "role",
                "CORE"
            ) ?: "CORE"

        roleSpinner.setSelection(
            roleCodes
                .indexOf(
                    currentRole
                )
                .coerceAtLeast(0)
        )

        box.addView(
            roleSpinner
        )

        val minField =
            numberField(
                "Минимум, минут",
                planning?.nullableInt(
                    "min_duration_minutes"
                )
            )

        val typicalField =
            numberField(
                "Обычно, минут",
                planning?.nullableInt(
                    "typical_duration_minutes"
                )
            )

        val maxField =
            numberField(
                "Максимум, минут",
                planning?.nullableInt(
                    "max_duration_minutes"
                )
            )

        box.addView(minField)
        box.addView(typicalField)
        box.addView(maxField)

        box.addView(
            dialogLabel(
                "Когда обычно начинается"
            )
        )

        val startButton =
            Button(this).apply {
                isAllCaps = false
            }

        fun refreshStartButton() {
            startButton.text =
                when {
                    selectedStartMinute != null && anchorText != null ->
                        "≈${formatMinute(selectedStartMinute!!)} · $anchorText"

                    selectedStartMinute != null ->
                        "≈${formatMinute(selectedStartMinute!!)}"

                    anchorText != null ->
                        anchorText

                    else ->
                        "Гибкое время"
                }
        }

        refreshStartButton()

        startButton.setOnClickListener {
            val current = selectedStartMinute ?: 8 * 60

            TimePickerDialog(
                this,
                { _, hour, minute ->
                    selectedStartMinute =
                        hour * 60 + minute
                    refreshStartButton()
                },
                current / 60,
                current % 60,
                true
            ).show()
        }

        val clearStartButton =
            Button(this).apply {
                text = "Без фиксированного времени"
                isAllCaps = false
                setOnClickListener {
                    selectedStartMinute = null
                    refreshStartButton()
                }
            }

        box.addView(startButton)
        box.addView(clearStartButton)

        AlertDialog.Builder(this)
            .setTitle(
                habit.name
            )
            .setView(box)
            .setNegativeButton(
                "Отмена",
                null
            )
            .setPositiveButton(
                "Сохранить"
            ) { _, _ ->

                val db =
                    BridgeLifeDatabase
                        .get(this)
                        .writableDatabase

                db.delete(
                    "habit_spheres",
                    "habit_uuid = ? AND relation = ?",
                    arrayOf(
                        habitUuid,
                        "PRIMARY"
                    )
                )

                val selectedSphere =
                    sphereSpinner
                        .selectedItemPosition

                if (
                    selectedSphere > 0
                ) {
                    repository
                        .linkHabitToSphere(
                            habitUuid =
                                habitUuid,
                            sphereUuid =
                                spheres[
                                    selectedSphere -
                                            1
                                ]
                                    .optString(
                                        "uuid"
                                    ),
                            relation =
                                "PRIMARY"
                        )
                }

                repository
                    .setHabitPlanning(
                        habitUuid =
                            habitUuid,
                        role =
                            roleCodes[
                                roleSpinner
                                    .selectedItemPosition
                            ],
                        minDurationMinutes =
                            minField
                                .text
                                .toString()
                                .toIntOrNull(),
                        typicalDurationMinutes =
                            typicalField
                                .text
                                .toString()
                                .toIntOrNull(),
                        maxDurationMinutes =
                            maxField
                                .text
                                .toString()
                                .toIntOrNull()
                    )

                db.delete(
                    "schedules",
                    "target_type = ? AND target_uuid = ?",
                    arrayOf(
                        "HABIT",
                        habitUuid
                    )
                )

                selectedStartMinute?.let { minute ->
                    repository.addSchedule(
                        targetType = "HABIT",
                        targetUuid = habitUuid,
                        preferredStartMinute = minute,
                        flexibility = "WINDOW"
                    )
                }

                syncBridge()
                render()
            }
            .show()
    }

    private fun showSpheresDialog() {
        val spheres =
            repository
                .exportJson()
                .objects(
                    "lifeSpheres"
                )
                .filter {
                    it.optInt(
                        "archived",
                        0
                    ) == 0
                }

        val text =
            spheres.joinToString(
                separator = "\n"
            ) {
                "• ${
                    it.optString(
                        "name"
                    )
                }"
            }

        AlertDialog.Builder(this)
            .setTitle(
                "Сферы жизни"
            )
            .setMessage(text)
            .setNegativeButton(
                "Закрыть",
                null
            )
            .setPositiveButton(
                "Добавить"
            ) { _, _ ->
                showCreateSphereDialog()
            }
            .show()
    }

    private fun showCreateSphereDialog() {
        val input =
            EditText(this).apply {
                hint =
                    "Название сферы"

                setPadding(
                    dp(20),
                    dp(8),
                    dp(20),
                    dp(8)
                )
            }

        AlertDialog.Builder(this)
            .setTitle(
                "Новая сфера"
            )
            .setView(input)
            .setNegativeButton(
                "Отмена",
                null
            )
            .setPositiveButton(
                "Создать"
            ) { _, _ ->

                val name =
                    input.text
                        .toString()
                        .trim()

                if (
                    name.isNotEmpty()
                ) {
                    repository
                        .createSphere(
                            name
                        )

                    syncBridge()
                    render()
                }
            }
            .show()
    }

    // ------------------------------------------------
    // ROUTINES
    // ------------------------------------------------

    private fun renderRoutines() {
        val life =
            repository.exportJson()

        val routines =
            life.objects(
                "routines"
            )
                .filter {
                    it.optInt(
                        "enabled",
                        1
                    ) == 1
                }

        val steps =
            life.objects(
                "routineSteps"
            )

        val schedules =
            life.objects(
                "schedules"
            )

        val habitByUuid =
            activeHabits()
                .mapNotNull {
                    val uuid =
                        it.uuid

                    if (uuid == null) {
                        null
                    } else {
                        uuid to it
                    }
                }
                .toMap()

        addTitle(
            "Рутины"
        )

        addActionButton(
            "＋ Создать рутину"
        ) {
            showCreateRoutineDialog()
        }

        if (routines.isEmpty()) {
            addEmpty(
                "Рутин пока нет."
            )

            return
        }

        routines.forEach { routine ->
            val routineUuid =
                routine.optString(
                    "uuid"
                )

            val routineSteps =
                steps
                    .filter {
                        it.optString(
                            "routine_uuid"
                        ) ==
                                routineUuid
                    }
                    .sortedBy {
                        it.optInt(
                            "position"
                        )
                    }

            val names =
                routineSteps.mapNotNull {
                    if (
                        it.optString(
                            "target_type"
                        ) != "HABIT"
                    ) {
                        null
                    } else {
                        habitByUuid[
                            it.optString(
                                "target_uuid"
                            )
                        ]?.name
                    }
                }

            val schedule =
                schedules.lastOrNull {
                    it.optString(
                        "target_type"
                    ) == "ROUTINE" &&
                            it.optString(
                                "target_uuid"
                            ) ==
                            routineUuid
                }

            val startMinute =
                schedule?.nullableInt(
                    "preferred_start_minute"
                )

            val timeText =
                if (
                    startMinute == null
                ) {
                    "время не задано"
                } else {
                    formatMinute(
                        startMinute
                    )
                }

            val sequence =
                if (names.isEmpty()) {
                    "пока без шагов"
                } else {
                    names.joinToString(
                        " → "
                    )
                }

            addRow(
                routine.optString(
                    "name"
                ),
                "$timeText\n$sequence"
            ) {
                showRoutineDialog(
                    routineUuid
                )
            }
        }
    }

    private fun showCreateRoutineDialog() {
        val input =
            EditText(this).apply {
                hint =
                    "Например: Утро"
            }

        val box =
            dialogBox().apply {
                addView(input)
            }

        AlertDialog.Builder(this)
            .setTitle(
                "Новая рутина"
            )
            .setView(box)
            .setNegativeButton(
                "Отмена",
                null
            )
            .setPositiveButton(
                "Создать"
            ) { _, _ ->

                val name =
                    input.text
                        .toString()
                        .trim()

                if (
                    name.isNotEmpty()
                ) {
                    repository
                        .createRoutine(
                            name
                        )

                    syncBridge()
                    render()
                }
            }
            .show()
    }

    private fun showRoutineDialog(
        routineUuid: String
    ) {
        val life =
            repository.exportJson()

        val routine =
            life.objects(
                "routines"
            )
                .lastOrNull {
                    it.optString(
                        "uuid"
                    ) ==
                            routineUuid
                }
                ?: return

        val steps =
            life.objects(
                "routineSteps"
            )
                .filter {
                    it.optString(
                        "routine_uuid"
                    ) ==
                            routineUuid
                }
                .sortedBy {
                    it.optInt(
                        "position"
                    )
                }

        val habitMap =
            activeHabits()
                .mapNotNull {
                    val uuid =
                        it.uuid

                    if (uuid == null) {
                        null
                    } else {
                        uuid to it.name
                    }
                }
                .toMap()

        val stepText =
            if (steps.isEmpty()) {
                "Шагов пока нет."
            } else {
                steps.mapIndexed {
                        index,
                        step ->

                    val name =
                        habitMap[
                            step.optString(
                                "target_uuid"
                            )
                        ] ?: "Неизвестная привычка"

                    "${index + 1}. $name"
                }.joinToString(
                    "\n"
                )
            }

        val box =
            dialogBox()

        box.addView(
            TextView(this).apply {
                text = stepText
                textSize = 16f

                setPadding(
                    0,
                    dp(4),
                    0,
                    dp(16)
                )
            }
        )

        val addHabit =
            Button(this).apply {
                this.text =
                    "Добавить привычку"

                isAllCaps =
                    false
            }

        val setTime =
            Button(this).apply {
                text =
                    "Задать время"

                isAllCaps =
                    false
            }

        box.addView(addHabit)
        box.addView(setTime)

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(
                    routine.optString(
                        "name"
                    )
                )
                .setView(box)
                .setPositiveButton(
                    "Закрыть",
                    null
                )
                .create()

        addHabit.setOnClickListener {
            dialog.dismiss()

            showChooseHabitForRoutine(
                routineUuid
            )
        }

        setTime.setOnClickListener {
            dialog.dismiss()

            showRoutineTimePicker(
                routineUuid
            )
        }

        dialog.show()
    }

    private fun showChooseHabitForRoutine(
        routineUuid: String
    ) {
        val life =
            repository.exportJson()

        val currentSteps =
            life.objects(
                "routineSteps"
            )
                .filter {
                    it.optString(
                        "routine_uuid"
                    ) ==
                            routineUuid
                }

        val existing =
            currentSteps.map {
                it.optString(
                    "target_uuid"
                )
            }.toSet()

        val available =
            activeHabits()
                .filter {
                    val uuid =
                        it.uuid

                    uuid != null &&
                            uuid !in existing
                }

        if (available.isEmpty()) {
            toast(
                "Все привычки уже добавлены"
            )

            return
        }

        val nextPosition =
            (
                    currentSteps.maxOfOrNull {
                        it.optInt(
                            "position"
                        )
                    } ?: -1
                    ) + 1

        AlertDialog.Builder(this)
            .setTitle(
                "Добавить шаг"
            )
            .setItems(
                available
                    .map {
                        it.name
                    }
                    .toTypedArray()
            ) { _, which ->

                val habit =
                    available[
                        which
                    ]

                val uuid =
                    habit.uuid
                        ?: return@setItems

                repository
                    .addRoutineHabit(
                        routineUuid =
                            routineUuid,
                        habitUuid =
                            uuid,
                        position =
                            nextPosition
                    )

                syncBridge()
                render()

                toast(
                    "Добавлено: ${habit.name}"
                )
            }
            .show()
    }

    private fun showRoutineTimePicker(
        routineUuid: String
    ) {
        val life =
            repository.exportJson()

        val current =
            life.objects(
                "schedules"
            )
                .lastOrNull {
                    it.optString(
                        "target_type"
                    ) == "ROUTINE" &&
                            it.optString(
                                "target_uuid"
                            ) ==
                            routineUuid
                }
                ?.nullableInt(
                    "preferred_start_minute"
                )
                ?: 8 * 60

        TimePickerDialog(
            this,
            { _, hour, minute ->

                val db =
                    BridgeLifeDatabase
                        .get(this)
                        .writableDatabase

                db.delete(
                    "schedules",
                    "target_type = ? AND target_uuid = ?",
                    arrayOf(
                        "ROUTINE",
                        routineUuid
                    )
                )

                repository.addSchedule(
                    targetType =
                        "ROUTINE",
                    targetUuid =
                        routineUuid,
                    preferredStartMinute =
                        hour * 60 +
                                minute,
                    flexibility =
                        "WINDOW"
                )

                syncBridge()
                render()
            },
            current / 60,
            current % 60,
            true
        ).show()
    }

    // ------------------------------------------------
    // GOALS
    // ------------------------------------------------

    private fun renderGoals() {
        val life =
            repository.exportJson()

        val goals =
            life.objects(
                "goals"
            )

        val goalHabits =
            life.objects(
                "goalHabits"
            )

        val goalRoutines =
            life.objects(
                "goalRoutines"
            )

        val sphereMap =
            life.objects(
                "lifeSpheres"
            )
                .associateBy {
                    it.optString(
                        "uuid"
                    )
                }

        addTitle(
            "Цели"
        )

        addActionButton(
            "＋ Создать цель"
        ) {
            showCreateGoalDialog()
        }

        if (goals.isEmpty()) {
            addEmpty(
                "Целей пока нет."
            )

            return
        }

        goals.forEach { goal ->
            val uuid =
                goal.optString(
                    "uuid"
                )

            val habitsCount =
                goalHabits.count {
                    it.optString(
                        "goal_uuid"
                    ) == uuid
                }

            val routinesCount =
                goalRoutines.count {
                    it.optString(
                        "goal_uuid"
                    ) == uuid
                }

            val sphereUuid =
                goal.optString(
                    "primary_sphere_uuid"
                )

            val sphere =
                sphereMap[
                    sphereUuid
                ]?.optString(
                    "name"
                ) ?: "Без сферы"

            addRow(
                goal.optString(
                    "title"
                ),
                "$sphere\n" +
                        "$habitsCount привычек • " +
                        "$routinesCount рутин"
            ) {
                showGoalDialog(
                    uuid
                )
            }
        }
    }

    private fun showCreateGoalDialog() {
        val life =
            repository.exportJson()

        val spheres =
            life.objects(
                "lifeSpheres"
            )
                .filter {
                    it.optInt(
                        "archived",
                        0
                    ) == 0
                }

        val box =
            dialogBox()

        val title =
            EditText(this).apply {
                hint =
                    "Название цели"
            }

        val sphereNames =
            mutableListOf(
                "Без сферы"
            )

        sphereNames.addAll(
            spheres.map {
                it.optString(
                    "name"
                )
            }
        )

        val sphereSpinner =
            spinner(
                sphereNames
            )

        box.addView(title)

        box.addView(
            dialogLabel(
                "Сфера"
            )
        )

        box.addView(
            sphereSpinner
        )

        AlertDialog.Builder(this)
            .setTitle(
                "Новая цель"
            )
            .setView(box)
            .setNegativeButton(
                "Отмена",
                null
            )
            .setPositiveButton(
                "Создать"
            ) { _, _ ->

                val text =
                    title.text
                        .toString()
                        .trim()

                if (
                    text.isNotEmpty()
                ) {
                    val index =
                        sphereSpinner
                            .selectedItemPosition

                    val sphereUuid =
                        if (index == 0) {
                            null
                        } else {
                            spheres[
                                index - 1
                            ].optString(
                                "uuid"
                            )
                        }

                    repository.createGoal(
                        title = text,
                        primarySphereUuid =
                            sphereUuid
                    )

                    syncBridge()
                    render()
                }
            }
            .show()
    }

    private fun showGoalDialog(
        goalUuid: String
    ) {
        val life =
            repository.exportJson()

        val goal =
            life.objects(
                "goals"
            )
                .lastOrNull {
                    it.optString(
                        "uuid"
                    ) ==
                            goalUuid
                }
                ?: return

        val goalHabits =
            life.objects(
                "goalHabits"
            )
                .filter {
                    it.optString(
                        "goal_uuid"
                    ) ==
                            goalUuid
                }

        val goalRoutines =
            life.objects(
                "goalRoutines"
            )
                .filter {
                    it.optString(
                        "goal_uuid"
                    ) ==
                            goalUuid
                }

        val habitMap =
            activeHabits()
                .mapNotNull {
                    val uuid =
                        it.uuid

                    if (uuid == null) {
                        null
                    } else {
                        uuid to it.name
                    }
                }
                .toMap()

        val routineMap =
            life.objects(
                "routines"
            )
                .associate {
                    it.optString(
                        "uuid"
                    ) to
                            it.optString(
                                "name"
                            )
                }

        val text =
            buildString {
                append(
                    "Привычки:\n"
                )

                if (
                    goalHabits.isEmpty()
                ) {
                    append(
                        "— пока нет"
                    )
                } else {
                    goalHabits.forEach {
                        append(
                            "\n• "
                        )

                        append(
                            habitMap[
                                it.optString(
                                    "habit_uuid"
                                )
                            ] ?: "Неизвестная привычка"
                        )
                    }
                }

                append(
                    "\n\nРутины:\n"
                )

                if (
                    goalRoutines.isEmpty()
                ) {
                    append(
                        "— пока нет"
                    )
                } else {
                    goalRoutines.forEach {
                        append(
                            "\n• "
                        )

                        append(
                            routineMap[
                                it.optString(
                                    "routine_uuid"
                                )
                            ] ?: "Неизвестная рутина"
                        )
                    }
                }
            }

        val box =
            dialogBox()

        box.addView(
            TextView(this).apply {
                this.text = text
                textSize = 16f

                setPadding(
                    0,
                    0,
                    0,
                    dp(16)
                )
            }
        )

        val addHabit =
            Button(this).apply {
                this.text =
                    "Добавить привычку"

                isAllCaps =
                    false
            }

        val addRoutine =
            Button(this).apply {
                this.text =
                    "Добавить рутину"

                isAllCaps =
                    false
            }

        box.addView(addHabit)
        box.addView(addRoutine)

        val dialog =
            AlertDialog.Builder(this)
                .setTitle(
                    goal.optString(
                        "title"
                    )
                )
                .setView(box)
                .setPositiveButton(
                    "Закрыть",
                    null
                )
                .create()

        addHabit.setOnClickListener {
            dialog.dismiss()

            showChooseHabitForGoal(
                goalUuid
            )
        }

        addRoutine.setOnClickListener {
            dialog.dismiss()

            showChooseRoutineForGoal(
                goalUuid
            )
        }

        dialog.show()
    }

    private fun showChooseHabitForGoal(
        goalUuid: String
    ) {
        val linked =
            repository
                .exportJson()
                .objects(
                    "goalHabits"
                )
                .filter {
                    it.optString(
                        "goal_uuid"
                    ) ==
                            goalUuid
                }
                .map {
                    it.optString(
                        "habit_uuid"
                    )
                }
                .toSet()

        val available =
            activeHabits()
                .filter {
                    val uuid =
                        it.uuid

                    uuid != null &&
                            uuid !in linked
                }

        if (available.isEmpty()) {
            toast(
                "Все привычки уже связаны"
            )

            return
        }

        AlertDialog.Builder(this)
            .setTitle(
                "Добавить привычку"
            )
            .setItems(
                available
                    .map {
                        it.name
                    }
                    .toTypedArray()
            ) { _, which ->

                val habit =
                    available[
                        which
                    ]

                val uuid =
                    habit.uuid
                        ?: return@setItems

                repository
                    .linkHabitToGoal(
                        habitUuid =
                            uuid,
                        goalUuid =
                            goalUuid,
                        position =
                            linked.size
                    )

                syncBridge()
                render()
            }
            .show()
    }

    private fun showChooseRoutineForGoal(
        goalUuid: String
    ) {
        val life =
            repository.exportJson()

        val linked =
            life.objects(
                "goalRoutines"
            )
                .filter {
                    it.optString(
                        "goal_uuid"
                    ) ==
                            goalUuid
                }
                .map {
                    it.optString(
                        "routine_uuid"
                    )
                }
                .toSet()

        val available =
            life.objects(
                "routines"
            )
                .filter {
                    it.optInt(
                        "enabled",
                        1
                    ) == 1 &&
                            it.optString(
                                "uuid"
                            ) !in linked
                }

        if (available.isEmpty()) {
            toast(
                "Нет доступных рутин"
            )

            return
        }

        AlertDialog.Builder(this)
            .setTitle(
                "Добавить рутину"
            )
            .setItems(
                available
                    .map {
                        it.optString(
                            "name"
                        )
                    }
                    .toTypedArray()
            ) { _, which ->

                repository
                    .linkRoutineToGoal(
                        routineUuid =
                            available[
                                which
                            ].optString(
                                "uuid"
                            ),
                        goalUuid =
                            goalUuid,
                        position =
                            linked.size
                    )

                syncBridge()
                render()
            }
            .show()
    }

    // ------------------------------------------------
    // COMMON UI
    // ------------------------------------------------

    private fun addTitle(
        text: String
    ) {
        content.addView(
            TextView(this).apply {
                this.text = text
                textSize = 25f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(2), dp(16), dp(2), dp(12))
            }
        )
    }

    private fun addSubtitle(
        text: String
    ) {
        content.addView(
            TextView(this).apply {
                this.text = text
                textSize = 19f
                setTypeface(typeface, android.graphics.Typeface.BOLD)
                setPadding(dp(2), dp(18), dp(2), dp(8))
            }
        )
    }

    private fun addInfo(
        title: String,
        body: String
    ) {
        val card = polishedCard().apply {
            addView(
                LinearLayout(this@PlanActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    addView(
                        TextView(this@PlanActivity).apply {
                            text = title
                            textSize = 16f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        }
                    )
                    addView(
                        TextView(this@PlanActivity).apply {
                            text = body
                            textSize = 13.5f
                            alpha = 0.78f
                            setPadding(0, dp(5), 0, 0)
                        }
                    )
                }
            )
        }
        addCardToContent(card)
    }

    private fun addRow(
        title: String,
        subtitle: String,
        onClick: () -> Unit
    ) {
        val card = polishedCard().apply {
            isClickable = true
            isFocusable = true
            setOnClickListener { onClick() }
            addView(
                LinearLayout(this@PlanActivity).apply {
                    orientation = LinearLayout.VERTICAL
                    setPadding(dp(16), dp(14), dp(16), dp(14))
                    addView(
                        TextView(this@PlanActivity).apply {
                            text = title
                            textSize = 16f
                            setTypeface(typeface, android.graphics.Typeface.BOLD)
                        }
                    )
                    addView(
                        TextView(this@PlanActivity).apply {
                            text = subtitle
                            textSize = 13.5f
                            alpha = 0.74f
                            setPadding(0, dp(5), 0, 0)
                        }
                    )
                }
            )
        }
        addCardToContent(card)
    }

    private fun addActionButton(
        text: String,
        onClick: () -> Unit
    ) {
        val primary = resolveThemeColor(
            org.isoron.uhabits.R.attr.colorPrimary,
            Color.DKGRAY
        )
        content.addView(
            MaterialButton(this).apply {
                this.text = text
                isAllCaps = false
                cornerRadius = dp(18)
                backgroundTintList = ColorStateList.valueOf(primary)
                setTextColor(Color.WHITE)
                setOnClickListener { onClick() }
            },
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                dp(50)
            ).apply {
                bottomMargin = dp(9)
            }
        )
    }

    private fun addEmpty(
        text: String
    ) {
        val card = polishedCard().apply {
            addView(
                TextView(this@PlanActivity).apply {
                    this.text = text
                    textSize = 15f
                    alpha = 0.7f
                    setPadding(dp(16), dp(18), dp(16), dp(18))
                }
            )
        }
        addCardToContent(card)
    }

    private fun polishedCard(): MaterialCardView {
        val primary = resolveThemeColor(
            org.isoron.uhabits.R.attr.colorPrimary,
            Color.DKGRAY
        )
        val surface = resolveThemeColor(
            org.isoron.uhabits.R.attr.cardBgColor,
            Color.WHITE
        )
        return MaterialCardView(this).apply {
            radius = dp(18).toFloat()
            cardElevation = 0f
            strokeWidth = dp(1)
            strokeColor = withAlpha(primary, 34)
            setCardBackgroundColor(surface)
        }
    }

    private fun addCardToContent(card: View) {
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

    private fun withAlpha(color: Int, alpha: Int): Int =
        (color and 0x00FFFFFF) or ((alpha.coerceIn(0, 255)) shl 24)

    private fun dialogBox():
            LinearLayout {
        return LinearLayout(this).apply {
            orientation =
                LinearLayout.VERTICAL

            setPadding(
                dp(20),
                dp(8),
                dp(20),
                dp(8)
            )
        }
    }

    private fun dialogLabel(
        text: String
    ): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 13f

            setPadding(
                0,
                dp(12),
                0,
                dp(2)
            )
        }
    }

    private fun spinner(
        values: List<String>
    ): Spinner {
        return Spinner(this).apply {
            adapter =
                ArrayAdapter(
                    this@PlanActivity,
                    android.R.layout
                        .simple_spinner_item,
                    values
                ).apply {
                    setDropDownViewResource(
                        android.R.layout
                            .simple_spinner_dropdown_item
                    )
                }
        }
    }

    private fun numberField(
        hint: String,
        value: Int?
    ): EditText {
        return EditText(this).apply {
            this.hint = hint

            inputType =
                InputType.TYPE_CLASS_NUMBER

            if (value != null) {
                setText(
                    value.toString()
                )
            }
        }
    }

    private fun activeHabits():
            List<Habit> {
        return app.component
            .habitList
            .filter {
                !it.isArchived
            }
    }

    private fun frequencyText(
        habit: Habit
    ): String {
        val numerator =
            habit.frequency
                .numerator

        val denominator =
            habit.frequency
                .denominator

        return if (
            numerator == 1 &&
            denominator == 1
        ) {
            "ежедневно"
        } else {
            "$numerator раз / " +
                    "$denominator дн."
        }
    }

    private fun syncBridge() {
        BridgeSnapshotStore.save(
            this,
            BridgeSnapshotFactory.create(
                app.component.habitList
            )
        )
    }

    private fun formatMinute(
        minuteOfDay: Int
    ): String {
        val hour =
            minuteOfDay / 60

        val minute =
            minuteOfDay % 60

        return "%02d:%02d".format(
            hour,
            minute
        )
    }

    private fun JSONObject.objects(
        key: String
    ): List<JSONObject> {
        val array =
            optJSONArray(key)
                ?: return emptyList()

        return buildList {
            for (
            index in 0
                    until array.length()
            ) {
                val item =
                    array.optJSONObject(
                        index
                    )

                if (item != null) {
                    add(item)
                }
            }
        }
    }

    private fun JSONObject.nullableInt(
        key: String
    ): Int? {
        if (
            !has(key) ||
            isNull(key)
        ) {
            return null
        }

        return optInt(key)
    }

    private fun LinearLayout
            .applySelectableBackground() {
        val value =
            TypedValue()

        theme.resolveAttribute(
            android.R.attr
                .selectableItemBackground,
            value,
            true
        )

        if (
            value.resourceId != 0
        ) {
            setBackgroundResource(
                value.resourceId
            )
        }
    }

    private fun resolveThemeColor(
        attribute: Int,
        fallback: Int
    ): Int {
        val value =
            TypedValue()

        return if (
            theme.resolveAttribute(
                attribute,
                value,
                true
            )
        ) {
            value.data
        } else {
            fallback
        }
    }

    private fun dp(
        value: Int
    ): Int {
        return (
                value *
                        resources
                            .displayMetrics
                            .density
                ).roundToInt()
    }

    private fun toast(
        text: String
    ) {
        Toast.makeText(
            this,
            text,
            Toast.LENGTH_SHORT
        ).show()
    }

    companion object {
        const val EXTRA_TAB = "plan_initial_tab"
        const val TAB_TODAY = "TODAY"
        const val TAB_ROUTINES = "ROUTINES"
        const val TAB_GOALS = "GOALS"
    }

    private enum class Tab {
        TODAY,
        ROUTINES,
        GOALS
    }
}
