package org.isoron.uhabits.activities.habits.edit

import android.app.TimePickerDialog
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import org.isoron.uhabits.bridge.BridgeLifeDatabase
import org.isoron.uhabits.bridge.BridgeLifeRepository
import org.json.JSONObject
import kotlin.math.roundToInt

/**
 * Adds Loop 0.3 planning fields to the existing habit editor without changing
 * the original XML form. This keeps the upstream edit layout easier to merge.
 */
class HabitLifeEditor(
    private val activity: AppCompatActivity,
    private val repository: BridgeLifeRepository
) {

    private var selectedSphereUuid: String? = null
    private val selectedGoalUuids = linkedSetOf<String>()
    private val selectedRoutineUuids = linkedSetOf<String>()

    private var minDuration: Int? = null
    private var typicalDuration: Int? = null
    private var maxDuration: Int? = null
    private var preferredStartMinute: Int? = null
    private var startLabel: String? = null
    private var role: String = "CORE"
    private var planningNotes: String = ""

    private var summaryButton: Button? = null

    fun attach(root: ViewGroup, habitUuid: String?) {
        load(habitUuid)

        val scroll = findFirstScrollView(root) ?: return
        val form = scroll.getChildAt(0) as? LinearLayout ?: return

        val sectionTitle = TextView(activity).apply {
            text = "Планирование Loop 0.3"
            textSize = 14f
            setPadding(dp(12), dp(18), dp(12), dp(4))
        }

        val button = Button(activity).apply {
            isAllCaps = false
            setOnClickListener { showEditorDialog() }
        }

        summaryButton = button
        updateSummary()

        form.addView(
            sectionTitle,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        form.addView(
            button,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                marginStart = dp(4)
                marginEnd = dp(4)
                bottomMargin = dp(12)
            }
        )
    }

    fun save(habitUuid: String) {
        val db = BridgeLifeDatabase.get(activity).writableDatabase
        val lifeBefore = repository.exportJson()

        db.beginTransaction()
        try {
            db.delete(
                "habit_spheres",
                "habit_uuid = ? AND relation = ?",
                arrayOf(habitUuid, "PRIMARY")
            )

            selectedSphereUuid?.let { sphereUuid ->
                repository.linkHabitToSphere(
                    habitUuid = habitUuid,
                    sphereUuid = sphereUuid,
                    relation = "PRIMARY"
                )
            }

            db.delete(
                "goal_habits",
                "habit_uuid = ?",
                arrayOf(habitUuid)
            )

            selectedGoalUuids.forEachIndexed { index, goalUuid ->
                repository.linkHabitToGoal(
                    habitUuid = habitUuid,
                    goalUuid = goalUuid,
                    position = index
                )
            }

            val oldRoutinePositions =
                lifeBefore.objects("routineSteps")
                    .filter {
                        it.optString("target_type") == "HABIT" &&
                            it.optString("target_uuid") == habitUuid
                    }
                    .associate {
                        it.optString("routine_uuid") to
                            it.optInt("position")
                    }

            db.delete(
                "routine_steps",
                "target_type = ? AND target_uuid = ?",
                arrayOf("HABIT", habitUuid)
            )

            val allSteps = lifeBefore.objects("routineSteps")

            selectedRoutineUuids.forEach { routineUuid ->
                val position = oldRoutinePositions[routineUuid]
                    ?: (
                        allSteps
                            .filter {
                                it.optString("routine_uuid") == routineUuid &&
                                    !(it.optString("target_type") == "HABIT" &&
                                        it.optString("target_uuid") == habitUuid)
                            }
                            .maxOfOrNull { it.optInt("position") }
                            ?.plus(1)
                            ?: 0
                        )

                repository.addRoutineHabit(
                    routineUuid = routineUuid,
                    habitUuid = habitUuid,
                    position = position
                )
            }

            repository.setHabitPlanning(
                habitUuid = habitUuid,
                role = role,
                minDurationMinutes = minDuration,
                typicalDurationMinutes = typicalDuration,
                maxDurationMinutes = maxDuration,
                planningNotes = planningNotes
            )

            db.delete(
                "schedules",
                "target_type = ? AND target_uuid = ?",
                arrayOf("HABIT", habitUuid)
            )

            preferredStartMinute?.let { minute ->
                repository.addSchedule(
                    targetType = "HABIT",
                    targetUuid = habitUuid,
                    preferredStartMinute = minute,
                    flexibility = "WINDOW"
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun load(habitUuid: String?) {
        if (habitUuid == null) return

        val life = repository.exportJson()

        selectedSphereUuid =
            life.objects("habitSpheres")
                .lastOrNull {
                    it.optString("habit_uuid") == habitUuid &&
                        it.optString("relation") == "PRIMARY"
                }
                ?.optString("sphere_uuid")
                ?.takeIf { it.isNotBlank() }

        selectedGoalUuids.clear()
        life.objects("goalHabits")
            .filter { it.optString("habit_uuid") == habitUuid }
            .sortedBy { it.optInt("position") }
            .forEach { selectedGoalUuids += it.optString("goal_uuid") }

        selectedRoutineUuids.clear()
        life.objects("routineSteps")
            .filter {
                it.optString("target_type") == "HABIT" &&
                    it.optString("target_uuid") == habitUuid
            }
            .sortedBy { it.optInt("position") }
            .forEach { selectedRoutineUuids += it.optString("routine_uuid") }

        val planning =
            life.objects("habitPlanning")
                .lastOrNull { it.optString("habit_uuid") == habitUuid }

        minDuration = planning?.nullableInt("min_duration_minutes")
        typicalDuration = planning?.nullableInt("typical_duration_minutes")
        maxDuration = planning?.nullableInt("max_duration_minutes")
        role = planning?.optString("role", "CORE") ?: "CORE"
        planningNotes = planning?.optString("planning_notes", "") ?: ""

        preferredStartMinute =
            life.objects("schedules")
                .lastOrNull {
                    it.optString("target_type") == "HABIT" &&
                        it.optString("target_uuid") == habitUuid
                }
                ?.nullableInt("preferred_start_minute")

        startLabel =
            life.objects("anchors")
                .lastOrNull {
                    it.optString("target_type") == "HABIT" &&
                        it.optString("target_uuid") == habitUuid
                }
                ?.optString("custom_text")
                ?.takeIf { it.isNotBlank() }
    }

    private fun showEditorDialog() {
        val oldSphere = selectedSphereUuid
        val oldGoals = selectedGoalUuids.toSet()
        val oldRoutines = selectedRoutineUuids.toSet()
        val oldMin = minDuration
        val oldTypical = typicalDuration
        val oldMax = maxDuration
        val oldStart = preferredStartMinute

        fun restoreDraft() {
            selectedSphereUuid = oldSphere
            selectedGoalUuids.clear()
            selectedGoalUuids.addAll(oldGoals)
            selectedRoutineUuids.clear()
            selectedRoutineUuids.addAll(oldRoutines)
            minDuration = oldMin
            typicalDuration = oldTypical
            maxDuration = oldMax
            preferredStartMinute = oldStart
            updateSummary()
        }

        val life = repository.exportJson()

        val spheres = life.objects("lifeSpheres")
            .filter { it.optInt("archived", 0) == 0 }

        val goals = life.objects("goals")
            .filter { it.optString("status", "ACTIVE") == "ACTIVE" }

        val routines = life.objects("routines")
            .filter { it.optInt("enabled", 1) == 1 }

        val box = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(8), dp(20), dp(8))
        }

        val sphereButton = Button(activity).apply {
            isAllCaps = false
        }

        val goalsButton = Button(activity).apply {
            isAllCaps = false
        }

        val routinesButton = Button(activity).apply {
            isAllCaps = false
        }

        val minField = numberField("Минимум, минут", minDuration)
        val typicalField = numberField("Обычно, минут", typicalDuration)
        val maxField = numberField("Максимум, минут", maxDuration)

        fun updateButtons() {
            val sphereName = spheres
                .firstOrNull { it.optString("uuid") == selectedSphereUuid }
                ?.optString("name")
                ?: "Без сферы"

            sphereButton.text = "Сфера: $sphereName"
            goalsButton.text = "Цели: ${selectedGoalUuids.size}"
            routinesButton.text = "Рутины: ${selectedRoutineUuids.size}"
        }

        updateButtons()

        sphereButton.setOnClickListener {
            val labels = mutableListOf("Без сферы")
            labels += spheres.map { it.optString("name") }

            val checked = spheres.indexOfFirst {
                it.optString("uuid") == selectedSphereUuid
            } + 1

            AlertDialog.Builder(activity)
                .setTitle("Сфера")
                .setSingleChoiceItems(labels.toTypedArray(), checked.coerceAtLeast(0)) { dialog, which ->
                    selectedSphereUuid = if (which == 0) {
                        null
                    } else {
                        spheres[which - 1].optString("uuid")
                    }
                    updateButtons()
                    dialog.dismiss()
                }
                .show()
        }

        goalsButton.setOnClickListener {
            if (goals.isEmpty()) {
                AlertDialog.Builder(activity)
                    .setMessage("Сначала создай цель в разделе «План → Цели».")
                    .setPositiveButton("ОК", null)
                    .show()
                return@setOnClickListener
            }

            val labels = goals.map { it.optString("title") }.toTypedArray()
            val checked = BooleanArray(goals.size) { index ->
                goals[index].optString("uuid") in selectedGoalUuids
            }

            AlertDialog.Builder(activity)
                .setTitle("Цели")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    val uuid = goals[which].optString("uuid")
                    if (isChecked) selectedGoalUuids += uuid else selectedGoalUuids -= uuid
                    updateButtons()
                }
                .setPositiveButton("Готово", null)
                .show()
        }

        routinesButton.setOnClickListener {
            if (routines.isEmpty()) {
                AlertDialog.Builder(activity)
                    .setMessage("Сначала создай рутину в разделе «План → Рутины».")
                    .setPositiveButton("ОК", null)
                    .show()
                return@setOnClickListener
            }

            val labels = routines.map { it.optString("name") }.toTypedArray()
            val checked = BooleanArray(routines.size) { index ->
                routines[index].optString("uuid") in selectedRoutineUuids
            }

            AlertDialog.Builder(activity)
                .setTitle("Рутины")
                .setMultiChoiceItems(labels, checked) { _, which, isChecked ->
                    val uuid = routines[which].optString("uuid")
                    if (isChecked) selectedRoutineUuids += uuid else selectedRoutineUuids -= uuid
                    updateButtons()
                }
                .setPositiveButton("Готово", null)
                .show()
        }

        box.addView(sphereButton)
        box.addView(goalsButton)
        box.addView(routinesButton)

        box.addView(TextView(activity).apply {
            text = "Время"
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
        })

        box.addView(minField)
        box.addView(typicalField)
        box.addView(maxField)

        box.addView(TextView(activity).apply {
            text = "Когда обычно начинается"
            textSize = 13f
            setPadding(0, dp(12), 0, 0)
        })

        val startButton = Button(activity).apply {
            isAllCaps = false
        }

        fun updateStartButton() {
            startButton.text = when {
                preferredStartMinute != null && startLabel != null ->
                    "≈${formatMinute(preferredStartMinute!!)} · $startLabel"
                preferredStartMinute != null ->
                    "≈${formatMinute(preferredStartMinute!!)}"
                startLabel != null ->
                    startLabel
                else ->
                    "Гибкое время"
            }
        }

        updateStartButton()

        startButton.setOnClickListener {
            val current = preferredStartMinute ?: 8 * 60
            TimePickerDialog(
                activity,
                { _, hour, minute ->
                    preferredStartMinute = hour * 60 + minute
                    updateStartButton()
                },
                current / 60,
                current % 60,
                true
            ).show()
        }

        val clearStartButton = Button(activity).apply {
            text = "Без фиксированного времени"
            isAllCaps = false
            setOnClickListener {
                preferredStartMinute = null
                updateStartButton()
            }
        }

        box.addView(startButton)
        box.addView(clearStartButton)

        val dialog = AlertDialog.Builder(activity)
            .setTitle("Планирование привычки")
            .setView(box)
            .setNegativeButton("Отмена") { _, _ ->
                restoreDraft()
            }
            .setPositiveButton("Готово") { _, _ ->
                minDuration = minField.text.toString().toIntOrNull()
                typicalDuration = typicalField.text.toString().toIntOrNull()
                maxDuration = maxField.text.toString().toIntOrNull()
                updateSummary()
            }
            .create()

        dialog.setOnCancelListener {
            restoreDraft()
        }

        dialog.show()
    }

    private fun updateSummary() {
        val life = repository.exportJson()
        val sphereName = life.objects("lifeSpheres")
            .firstOrNull { it.optString("uuid") == selectedSphereUuid }
            ?.optString("name")
            ?: "Без сферы"

        val duration =
            if (minDuration == null && typicalDuration == null && maxDuration == null) {
                "длительность не задана"
            } else {
                "${minDuration ?: 0}/${typicalDuration ?: 0}/${maxDuration ?: 0} мин"
            }

        val start = when {
            preferredStartMinute != null && startLabel != null ->
                "≈${formatMinute(preferredStartMinute!!)} · $startLabel"
            preferredStartMinute != null ->
                "≈${formatMinute(preferredStartMinute!!)}"
            startLabel != null ->
                startLabel!!
            else ->
                "гибко"
        }

        summaryButton?.text =
            "Сфера: $sphereName\n" +
                "Цели: ${selectedGoalUuids.size} • Рутины: ${selectedRoutineUuids.size}\n" +
                "Старт: $start • мин/обычно/макс: $duration"
    }

    private fun numberField(hint: String, value: Int?): EditText =
        EditText(activity).apply {
            this.hint = hint
            inputType = InputType.TYPE_CLASS_NUMBER
            value?.let { setText(it.toString()) }
        }

    private fun findFirstScrollView(view: View): ScrollView? {
        if (view is ScrollView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findFirstScrollView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { add(it) }
            }
        }
    }

    private fun JSONObject.nullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }

    private fun formatMinute(minuteOfDay: Int): String =
        "%02d:%02d".format(
            minuteOfDay / 60,
            minuteOfDay % 60
        )

    private fun dp(value: Int): Int =
        (value * activity.resources.displayMetrics.density).roundToInt()
}
