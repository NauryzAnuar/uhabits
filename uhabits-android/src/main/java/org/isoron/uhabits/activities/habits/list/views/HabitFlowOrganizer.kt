package org.isoron.uhabits.activities.habits.list.views

import android.content.Context
import org.isoron.uhabits.bridge.BridgeCalendarManager
import org.isoron.uhabits.bridge.BridgeLifeRepository
import org.isoron.uhabits.core.models.Habit
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale

/**
 * Turns the flat Loop list into a human day flow without changing the core habit database.
 * Habits keep their normal Loop cards/checkmarks, but are visually ordered under short
 * sections such as Morning, Work, During the day, Evening and Before sleep.
 */
class HabitFlowOrganizer(context: Context) {
    data class Item(
        val habit: Habit,
        val section: String,
        val meta: String,
        val sortKey: Long
    )

    private val appContext = context.applicationContext
    private val repository = BridgeLifeRepository(appContext)
    private val calendarManager = BridgeCalendarManager(appContext)

    private var cachedAt = 0L
    private var cachedSignature = ""
    private var cached: List<Item> = emptyList()

    fun organize(habits: List<Habit>): List<Item> {
        val signature = habits.joinToString("|") { "${it.uuid}:${it.position}:${it.isArchived}" }
        val now = System.currentTimeMillis()
        if (signature == cachedSignature && now - cachedAt < 750L) return cached

        val life = repository.exportJson()
        val planningByHabit = life.objects("habitPlanning")
            .associateBy { it.optString("habit_uuid") }
        val habitSchedules = life.objects("schedules")
            .filter {
                it.optString("target_type") == "HABIT" &&
                    it.optInt("enabled", 1) == 1
            }
            .groupBy { it.optString("target_uuid") }
            .mapValues { (_, values) -> values.lastOrNull() }
        val routineSchedules = life.objects("schedules")
            .filter {
                it.optString("target_type") == "ROUTINE" &&
                    it.optInt("enabled", 1) == 1
            }
            .groupBy { it.optString("target_uuid") }
            .mapValues { (_, values) -> values.lastOrNull() }
        val routines = life.objects("routines")
            .associateBy { it.optString("uuid") }
        val routineSteps = life.objects("routineSteps")
            .filter { it.optString("target_type") == "HABIT" }
        val sphereByUuid = life.objects("lifeSpheres")
            .associateBy { it.optString("uuid") }
        val primarySphereByHabit = life.objects("habitSpheres")
            .filter { it.optString("relation") == "PRIMARY" }
            .associate {
                it.optString("habit_uuid") to it.optString("sphere_uuid")
            }

        val habitDuration = planningByHabit.mapValues { (_, value) ->
            value.nullableInt("typical_duration_minutes") ?: 0
        }

        val routineStepsByRoutine = routineSteps
            .groupBy { it.optString("routine_uuid") }
            .mapValues { (_, values) -> values.sortedBy { it.optInt("position") } }

        val routineStart = routines.keys.associateWith { routineUuid ->
            routineSchedules[routineUuid]?.nullableInt("preferred_start_minute")
                ?: routineStepsByRoutine[routineUuid]
                    .orEmpty()
                    .mapNotNull { step ->
                        habitSchedules[step.optString("target_uuid")]
                            ?.nullableInt("preferred_start_minute")
                    }
                    .minOrNull()
        }

        // Estimate the start of each step when a routine is contiguous.
        val routineStepStart = mutableMapOf<Pair<String, String>, Int>()
        routineStepsByRoutine.forEach { (routineUuid, steps) ->
            var cursor = routineStart[routineUuid] ?: defaultStartForRoutine(
                routines[routineUuid]?.optString("name").orEmpty()
            )
            steps.forEach { step ->
                val habitUuid = step.optString("target_uuid")
                val explicit = habitSchedules[habitUuid]?.nullableInt("preferred_start_minute")
                if (explicit != null && explicit > cursor) cursor = explicit
                routineStepStart[routineUuid to habitUuid] = cursor
                cursor += habitDuration[habitUuid] ?: 0
            }
        }

        val routinesByHabit = routineSteps
            .groupBy { it.optString("target_uuid") }
            .mapValues { (_, steps) ->
                steps.sortedWith(
                    compareBy<JSONObject> {
                        routineStart[it.optString("routine_uuid")] ?: Int.MAX_VALUE
                    }.thenBy { it.optInt("position") }
                )
            }

        val todayPlan = calendarManager.planItems(todayString())
            .filter { it.targetType == "HABIT" }
            .associateBy { it.targetUuid }

        val result = habits
            .filter { !it.isArchived }
            .map { habit ->
                val uuid = habit.uuid.orEmpty()
                val routineStep = routinesByHabit[uuid]?.firstOrNull()
                val routineUuid = routineStep?.optString("routine_uuid")
                val routineName = routineUuid?.let { routines[it]?.optString("name") }
                val sphereKey = primarySphereByHabit[uuid]
                    ?.let { sphereByUuid[it]?.optString("system_key") }
                    .orEmpty()

                val actualStart = todayPlan[uuid]?.startMillis?.let { minuteOfDay(it) }
                val routineEstimated = routineUuid?.let { routineStepStart[it to uuid] }
                val explicitStart = habitSchedules[uuid]?.nullableInt("preferred_start_minute")
                val preferred = actualStart ?: routineEstimated ?: explicitStart

                val section = sectionFor(
                    routineName = routineName,
                    preferredStart = preferred,
                    sphereKey = sphereKey
                )

                val duration = habitDuration[uuid] ?: 0
                val meta = buildMeta(actualStart ?: preferred, duration)
                val sectionOrder = sectionOrder(section)
                val orderMinute = preferred ?: defaultStartForSection(section)
                val stepPosition = routineStep?.optInt("position") ?: habit.position

                Item(
                    habit = habit,
                    section = section,
                    meta = meta,
                    sortKey = sectionOrder * 1_000_000L +
                        orderMinute * 1_000L +
                        stepPosition.coerceAtLeast(0)
                )
            }
            .sortedWith(compareBy<Item> { it.sortKey }.thenBy { it.habit.position })

        cachedSignature = signature
        cachedAt = now
        cached = result
        return result
    }

    private fun sectionFor(
        routineName: String?,
        preferredStart: Int?,
        sphereKey: String
    ): String {
        val r = normalize(routineName.orEmpty())
        if (r.contains("утро")) return "Утро"
        if (r.contains("рабоч") || r.contains("работ")) return "Работа"
        if (r.contains("семейн")) return "Вечер"
        if (r.contains("сном") || r.contains("сон")) return "Перед сном"
        if (routineName?.isNotBlank() == true) return routineName

        if (sphereKey == "work_career") return "Работа"

        return when (preferredStart) {
            null -> "В течение дня"
            in 0 until 540 -> "Утро"
            in 540 until 1080 -> "В течение дня"
            in 1080 until 1260 -> "Вечер"
            else -> "Перед сном"
        }
    }

    private fun buildMeta(startMinute: Int?, duration: Int): String {
        val time = startMinute?.let { formatMinute(it) }
        val length = if (duration > 0) "$duration мин" else null
        return listOfNotNull(time, length).joinToString(" · ")
    }

    private fun sectionOrder(section: String): Long = when (section) {
        "Утро" -> 100L
        "Работа" -> 200L
        "В течение дня" -> 300L
        "Вечер" -> 400L
        "Перед сном" -> 500L
        else -> 350L
    }

    private fun defaultStartForSection(section: String): Int = when (section) {
        "Утро" -> 360
        "Работа" -> 540
        "В течение дня" -> 780
        "Вечер" -> 1080
        "Перед сном" -> 1260
        else -> 900
    }

    private fun defaultStartForRoutine(name: String): Int {
        val n = normalize(name)
        return when {
            n.contains("утро") -> 360
            n.contains("начало рабочего") -> 540
            n.contains("завершение рабочего") -> 1065
            n.contains("семейн") -> 1080
            n.contains("сном") -> 1260
            else -> 780
        }
    }

    private fun formatMinute(value: Int): String {
        val minute = value.coerceIn(0, 1439)
        return "%02d:%02d".format(minute / 60, minute % 60)
    }

    private fun minuteOfDay(millis: Long): Int {
        val cal = Calendar.getInstance().apply { timeInMillis = millis }
        return cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
    }

    private fun todayString(): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date())

    private fun normalize(value: String): String =
        value.lowercase(Locale.getDefault()).replace('ё', 'е')

    private fun JSONObject.nullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(it) }
            }
        }
    }
}
