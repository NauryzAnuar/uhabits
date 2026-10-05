package org.isoron.uhabits.bridge

import android.content.Context
import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.NumericalHabitType
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import kotlin.math.max
import kotlin.math.min

/**
 * First real capacity planner for Loop 0.3.
 *
 * It combines:
 *  - existing Google Calendar busy intervals;
 *  - habit frequency;
 *  - min/typical/max planning metadata;
 *  - preferred start times;
 *  - a configurable active-day window.
 *
 * The planner never adds time for TRACKING-only habits, so aggregate trackers
 * such as "5 prayers on time" do not double-count the individual behavior.
 */
class LifePlanningEngine(
    private val context: Context,
    private val repository: BridgeLifeRepository,
    private val calendarManager: BridgeCalendarManager
) {
    private val app = context.applicationContext as HabitsApplication
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class PlanningResult(
        val date: String,
        val activeStartMillis: Long,
        val activeEndMillis: Long,
        val activeMinutes: Int,
        val externalBusyMinutes: Int,
        val plannedMinutes: Int,
        val unscheduledMinutes: Int,
        val freeMinutes: Int,
        val scheduledCount: Int,
        val unscheduledCount: Int,
        val googleEventsImported: Int
    )

    data class DaySummary(
        val date: String,
        val activeMinutes: Int,
        val externalBusyMinutes: Int,
        val plannedMinutes: Int,
        val unscheduledMinutes: Int,
        val freeMinutes: Int,
        val plannedCount: Int
    )

    private data class Candidate(
        val habit: Habit,
        val durationMinutes: Int,
        val preferredStartMinute: Int?,
        val role: String,
        val priority: Int,
        val isSleep: Boolean,
        val completed: Boolean,
        val routineUuid: String?,
        val routinePosition: Int,
        val routineStartMinute: Int?
    )

    private data class Interval(
        val start: Long,
        val end: Long
    )

    fun planningStartMinute(): Int =
        prefs.getInt(KEY_DAY_START, DEFAULT_DAY_START)

    fun planningEndMinute(): Int =
        prefs.getInt(KEY_DAY_END, DEFAULT_DAY_END)

    fun setPlanningWindow(startMinute: Int, endMinute: Int) {
        prefs.edit()
            .putInt(KEY_DAY_START, startMinute.coerceIn(0, 1439))
            .putInt(KEY_DAY_END, endMinute.coerceIn(1, 1440))
            .apply()
    }

    fun generateDay(dayMillis: Long): PlanningResult {
        val dayStart = localMidnight(dayMillis)
        val nextDay = dayStart + DAY_MILLIS
        val date = dateString(dayStart)

        val imported = if (calendarManager.hasCalendarPermission()) {
            calendarManager.refreshFromGoogle(dayStart, nextDay + 12 * HOUR_MILLIS)
        } else {
            0
        }

        val activeStart = dayStart + planningStartMinute() * MINUTE_MILLIS
        val activeEnd = dayStart + planningEndMinute() * MINUTE_MILLIS

        val life = repository.exportJson()
        val planningByHabit = life.objects("habitPlanning")
            .associateBy { it.optString("habit_uuid") }
        val schedulesByHabit = life.objects("schedules")
            .filter {
                it.optString("target_type") == "HABIT" &&
                    it.optInt("enabled", 1) == 1
            }
            .groupBy { it.optString("target_uuid") }
        val schedulesByRoutine = life.objects("schedules")
            .filter {
                it.optString("target_type") == "ROUTINE" &&
                    it.optInt("enabled", 1) == 1
            }
            .groupBy { it.optString("target_uuid") }
        val routineSteps = life.objects("routineSteps")
            .filter { it.optString("target_type") == "HABIT" }
        val stepsByRoutine = routineSteps
            .groupBy { it.optString("routine_uuid") }
        val routineStart = stepsByRoutine.keys.associateWith { routineUuid ->
            schedulesByRoutine[routineUuid]
                ?.lastOrNull()
                ?.nullableInt("preferred_start_minute")
                ?: stepsByRoutine[routineUuid]
                    .orEmpty()
                    .mapNotNull { step ->
                        schedulesByHabit[step.optString("target_uuid")]
                            ?.lastOrNull()
                            ?.nullableInt("preferred_start_minute")
                    }
                    .minOrNull()
        }
        val routineStepByHabit = routineSteps
            .groupBy { it.optString("target_uuid") }
            .mapValues { (_, values) ->
                values.minWithOrNull(
                    compareBy<JSONObject> {
                        routineStart[it.optString("routine_uuid")] ?: Int.MAX_VALUE
                    }.thenBy { it.optInt("position") }
                )
            }

        val lockedPlans = calendarManager.planItems(date)
            .filter { it.locked && it.startMillis != null && it.endMillis != null }

        val occupied = mutableListOf<Interval>()
        calendarManager.busyBlocks(activeStart, activeEnd).forEach {
            occupied += Interval(
                max(activeStart, it.startMillis),
                min(activeEnd, it.endMillis)
            )
        }
        lockedPlans.forEach {
            occupied += Interval(
                max(activeStart, it.startMillis!!),
                min(activeEnd, it.endMillis!!)
            )
        }

        val candidates = app.component.habitList
            .filter { !it.isArchived }
            .mapNotNull { habit ->
                val uuid = habit.uuid ?: return@mapNotNull null
                val planning = planningByHabit[uuid] ?: return@mapNotNull null
                val role = planning.optString("role", "CORE")
                val duration = planning.nullableInt("typical_duration_minutes") ?: 0
                if (role == "TRACKING" || duration <= 0) return@mapNotNull null
                if (!isDueOn(habit, dayStart)) return@mapNotNull null

                val schedule = schedulesByHabit[uuid]
                    ?.lastOrNull()

                val routineStep = routineStepByHabit[uuid]
                val routineUuid = routineStep?.optString("routine_uuid")

                Candidate(
                    habit = habit,
                    durationMinutes = duration,
                    preferredStartMinute = schedule?.nullableInt("preferred_start_minute"),
                    role = role,
                    priority = planning.optInt("priority", 0),
                    isSleep = isSleepHabit(habit, duration),
                    completed = isCompletedOn(habit, dayStart),
                    routineUuid = routineUuid,
                    routinePosition = routineStep?.optInt("position") ?: Int.MAX_VALUE,
                    routineStartMinute = routineUuid?.let { routineStart[it] }
                )
            }
            .sortedWith(
                compareBy<Candidate> {
                    it.routineStartMinute
                        ?: it.preferredStartMinute
                        ?: Int.MAX_VALUE
                }
                    .thenBy { it.routineUuid ?: "~${it.habit.position}" }
                    .thenBy { it.routinePosition }
                    .thenByDescending { it.priority }
                    .thenBy { it.habit.position }
            )

        val items = mutableListOf<BridgeCalendarManager.PlanItem>()
        var unscheduledMinutes = 0
        var unscheduledCount = 0
        val routineCursor = mutableMapOf<String, Long>()

        for (candidate in candidates) {
            val habitUuid = candidate.habit.uuid ?: continue
            val itemUuid = "habit:$habitUuid:$date"
            val durationMillis = candidate.durationMinutes * MINUTE_MILLIS
            val status = if (candidate.completed) "DONE" else "PLANNED"

            if (candidate.isSleep) {
                val startMinute = candidate.preferredStartMinute ?: planningEndMinute()
                val start = dayStart + startMinute * MINUTE_MILLIS
                items += BridgeCalendarManager.PlanItem(
                    uuid = itemUuid,
                    date = date,
                    targetType = "HABIT",
                    targetUuid = habitUuid,
                    startMillis = start,
                    endMillis = start + durationMillis,
                    status = status,
                    locked = false,
                    source = "AUTO"
                )
                continue
            }

            val routineDesired = candidate.routineUuid?.let { routineUuid ->
                routineCursor[routineUuid]
                    ?: candidate.routineStartMinute
                        ?.let { dayStart + it * MINUTE_MILLIS }
            }
            val preferred = routineDesired
                ?: candidate.preferredStartMinute
                    ?.let { dayStart + it * MINUTE_MILLIS }
                ?: activeStart
            val desired = preferred.coerceIn(activeStart, activeEnd)

            val slot = findSlot(
                desiredStart = desired,
                durationMillis = durationMillis,
                windowStart = activeStart,
                windowEnd = activeEnd,
                occupied = occupied
            )

            if (slot == null) {
                unscheduledMinutes += candidate.durationMinutes
                unscheduledCount++
                items += BridgeCalendarManager.PlanItem(
                    uuid = itemUuid,
                    date = date,
                    targetType = "HABIT",
                    targetUuid = habitUuid,
                    startMillis = null,
                    endMillis = null,
                    status = status,
                    locked = false,
                    source = "AUTO"
                )
            } else {
                val end = slot + durationMillis
                occupied += Interval(slot, end)
                candidate.routineUuid?.let { routineCursor[it] = end }
                items += BridgeCalendarManager.PlanItem(
                    uuid = itemUuid,
                    date = date,
                    targetType = "HABIT",
                    targetUuid = habitUuid,
                    startMillis = slot,
                    endMillis = end,
                    status = status,
                    locked = false,
                    source = "AUTO"
                )
            }
        }

        calendarManager.replaceAutoPlan(date, items)

        return summarize(
            dayMillis = dayStart,
            imported = imported,
            unscheduledOverride = unscheduledMinutes,
            unscheduledCountOverride = unscheduledCount
        )
    }

    fun generateWeek(dayMillis: Long): List<PlanningResult> {
        val weekStart = mondayStart(dayMillis)
        return (0 until 7).map { offset ->
            generateDay(weekStart + offset * DAY_MILLIS)
        }
    }

    fun summarizeDay(dayMillis: Long): DaySummary {
        val result = summarize(localMidnight(dayMillis))
        return DaySummary(
            date = result.date,
            activeMinutes = result.activeMinutes,
            externalBusyMinutes = result.externalBusyMinutes,
            plannedMinutes = result.plannedMinutes,
            unscheduledMinutes = result.unscheduledMinutes,
            freeMinutes = result.freeMinutes,
            plannedCount = result.scheduledCount + result.unscheduledCount
        )
    }

    fun weekSummaries(dayMillis: Long): List<DaySummary> {
        val weekStart = mondayStart(dayMillis)
        return (0 until 7).map { offset ->
            summarizeDay(weekStart + offset * DAY_MILLIS)
        }
    }

    fun recommendation(dayMillis: Long): String {
        val summary = summarizeDay(dayMillis)
        val date = dateString(localMidnight(dayMillis))
        val items = calendarManager.planItems(date)
        val life = repository.exportJson()
        val planning = life.objects("habitPlanning")
            .associateBy { it.optString("habit_uuid") }
        val names = app.component.habitList
            .mapNotNull { habit -> habit.uuid?.let { it to habit.name } }
            .toMap()

        if (summary.unscheduledMinutes > 0) {
            val bestReduction = items
                .filter { it.startMillis == null || it.endMillis == null }
                .mapNotNull { item ->
                    val p = planning[item.targetUuid] ?: return@mapNotNull null
                    val typical = p.nullableInt("typical_duration_minutes") ?: return@mapNotNull null
                    val min = p.nullableInt("min_duration_minutes") ?: typical
                    val saving = (typical - min).coerceAtLeast(0)
                    Triple(item.targetUuid, min, saving)
                }
                .maxByOrNull { it.third }

            if (bestReduction != null && bestReduction.third > 0) {
                val name = names[bestReduction.first] ?: "одну привычку"
                return "День перегружен примерно на ${summary.unscheduledMinutes} мин. " +
                    "Самое мягкое изменение: «$name» сделать в минимальном варианте " +
                    "${bestReduction.second} мин. Если этого мало — перенести одну гибкую привычку на другой день."
            }

            return "День перегружен примерно на ${summary.unscheduledMinutes} мин. " +
                "Не добавляй новую привычку: сначала перенеси или сократи одну из гибких задач."
        }

        if (summary.freeMinutes < 30) {
            return "План помещается, но запас маленький — около ${summary.freeMinutes} мин. " +
                "Лучше не заполнять его новой привычкой: оставь как резерв на задержки и семью."
        }

        return "После Google Calendar и текущих привычек остаётся около ${summary.freeMinutes} мин свободного времени. " +
            "Новая привычка допустима только если её обычная длительность реально помещается в этот остаток."
    }

    fun syncDayToGoogle(dayMillis: Long): Int {
        val dayStart = localMidnight(dayMillis)
        val end = dayStart + DAY_MILLIS + 12 * HOUR_MILLIS
        val habitByUuid = app.component.habitList
            .mapNotNull { habit -> habit.uuid?.let { it to habit.name } }
            .toMap()
        val sectionByHabit = routineSectionByHabit()

        return calendarManager.pushPlanToGoogle(
            startMillis = dayStart,
            endMillis = end
        ) { type, uuid ->
            if (type == "HABIT") {
                val name = habitByUuid[uuid] ?: "Привычка"
                sectionByHabit[uuid]?.let { "$it · $name" } ?: name
            } else {
                "План"
            }
        }
    }

    fun syncWeekToGoogle(dayMillis: Long): Int {
        val start = mondayStart(dayMillis)
        val end = start + 8 * DAY_MILLIS
        val habitByUuid = app.component.habitList
            .mapNotNull { habit -> habit.uuid?.let { it to habit.name } }
            .toMap()
        val sectionByHabit = routineSectionByHabit()

        return calendarManager.pushPlanToGoogle(
            startMillis = start,
            endMillis = end
        ) { type, uuid ->
            if (type == "HABIT") {
                val name = habitByUuid[uuid] ?: "Привычка"
                sectionByHabit[uuid]?.let { "$it · $name" } ?: name
            } else {
                "План"
            }
        }
    }

    private fun summarize(
        dayMillis: Long,
        imported: Int = 0,
        unscheduledOverride: Int? = null,
        unscheduledCountOverride: Int? = null
    ): PlanningResult {
        val dayStart = localMidnight(dayMillis)
        val date = dateString(dayStart)
        val activeStart = dayStart + planningStartMinute() * MINUTE_MILLIS
        val activeEnd = dayStart + planningEndMinute() * MINUTE_MILLIS
        val activeMinutes = ((activeEnd - activeStart) / MINUTE_MILLIS).toInt().coerceAtLeast(0)

        val externalIntervals = calendarManager.busyBlocks(activeStart, activeEnd)
            .map {
                Interval(
                    max(activeStart, it.startMillis),
                    min(activeEnd, it.endMillis)
                )
            }
            .filter { it.end > it.start }

        val plan = calendarManager.planItems(date)
        val plannedIntervals = plan
            .mapNotNull { item ->
                val start = item.startMillis ?: return@mapNotNull null
                val end = item.endMillis ?: return@mapNotNull null
                if (end <= activeStart || start >= activeEnd) return@mapNotNull null
                Interval(max(activeStart, start), min(activeEnd, end))
            }

        val externalBusy = intervalMinutes(externalIntervals)
        val planned = intervalMinutes(plannedIntervals)
        val allOccupied = intervalMinutes(externalIntervals + plannedIntervals)
        val unscheduled = unscheduledOverride
            ?: plan.filter { it.startMillis == null || it.endMillis == null }
                .sumOf { item -> typicalDurationFor(item.targetUuid) }
        val unscheduledCount = unscheduledCountOverride
            ?: plan.count { it.startMillis == null || it.endMillis == null }

        val free = (activeMinutes - allOccupied - unscheduled).coerceAtLeast(0)

        return PlanningResult(
            date = date,
            activeStartMillis = activeStart,
            activeEndMillis = activeEnd,
            activeMinutes = activeMinutes,
            externalBusyMinutes = externalBusy,
            plannedMinutes = planned,
            unscheduledMinutes = unscheduled,
            freeMinutes = free,
            scheduledCount = plan.count { it.startMillis != null && it.endMillis != null },
            unscheduledCount = unscheduledCount,
            googleEventsImported = imported
        )
    }

    private fun routineSectionByHabit(): Map<String, String> {
        val life = repository.exportJson()
        val routineNames = life.objects("routines").associate {
            it.optString("uuid") to it.optString("name")
        }
        return life.objects("routineSteps")
            .filter { it.optString("target_type") == "HABIT" }
            .sortedBy { it.optInt("position") }
            .groupBy { it.optString("target_uuid") }
            .mapValues { (_, steps) ->
                val routineName = steps.mapNotNull { step ->
                    routineNames[step.optString("routine_uuid")]
                }.firstOrNull().orEmpty()
                shortRoutineSection(routineName)
            }
            .filterValues { it.isNotBlank() }
    }

    private fun shortRoutineSection(name: String): String {
        val n = normalize(name)
        return when {
            n.contains("утро") -> "Утро"
            n.contains("рабоч") || n.contains("работ") -> "Работа"
            n.contains("семейн") -> "Вечер"
            n.contains("сном") || n.contains("сон") -> "Перед сном"
            else -> name
        }
    }

    private fun typicalDurationFor(habitUuid: String): Int {
        return repository.exportJson()
            .objects("habitPlanning")
            .lastOrNull { it.optString("habit_uuid") == habitUuid }
            ?.nullableInt("typical_duration_minutes")
            ?: 0
    }

    private fun isDueOn(habit: Habit, dayStart: Long): Boolean {
        val numerator = habit.frequency.numerator
        val denominator = habit.frequency.denominator
        if (numerator >= denominator) return true

        if (denominator == 7) {
            if (weeklyManualCount(habit, dayStart) >= numerator) return false

            val weekday = weekdayMondayZero(dayStart)
            val personal = personalWeekdays(habit.name)
            val targetDays = personal ?: when (numerator) {
                1 -> setOf(5) // Saturday
                2 -> setOf(0, 2) // Monday, Wednesday
                3 -> setOf(0, 2, 5) // Monday, Wednesday, Saturday
                4 -> setOf(0, 2, 4, 6)
                5 -> setOf(0, 1, 2, 4, 5)
                6 -> setOf(0, 1, 2, 3, 4, 5)
                else -> (0 until 7).toSet()
            }
            return weekday in targetDays
        }

        val epochDay = dayStart / DAY_MILLIS
        val phase = kotlin.math.abs((habit.uuid ?: habit.name).hashCode()) % denominator
        return ((epochDay + phase) % denominator) < numerator
    }

    private fun personalWeekdays(name: String): Set<Int>? {
        return when (normalize(name)) {
            normalize("Написать отцу") -> setOf(0, 2)
            normalize("1 Намаз в мечети джаммат кроме джума") -> setOf(6)
            normalize("Совместное время с женой") -> setOf(5)
            normalize("Убрать 5 вещей на свои места") -> setOf(1, 3, 5)
            normalize("Помочь одному человеку вне семьи") -> setOf(4)
            else -> null
        }
    }

    private fun weeklyManualCount(habit: Habit, dayStart: Long): Int {
        val monday = mondayStart(dayStart)
        var count = 0
        for (offset in 0 until 7) {
            val millis = monday + offset * DAY_MILLIS
            val date = LocalDate.fromUnixTime(millis)
            val value = habit.originalEntries.get(date).value
            if (value == Entry.YES_MANUAL) count++
        }
        return count
    }

    private fun isCompletedOn(habit: Habit, dayStart: Long): Boolean {
        val date = LocalDate.fromUnixTime(dayStart)
        val value = habit.computedEntries.get(date).value
        return if (habit.isNumerical) {
            when (habit.targetType) {
                NumericalHabitType.AT_LEAST -> value / 1000.0 >= habit.targetValue
                NumericalHabitType.AT_MOST -> value >= 0 && value / 1000.0 <= habit.targetValue
            }
        } else {
            value == Entry.YES_MANUAL
        }
    }

    private fun isSleepHabit(habit: Habit, durationMinutes: Int): Boolean {
        val name = normalize(habit.name)
        return durationMinutes >= 300 || name.contains("сон не менее")
    }

    private fun findSlot(
        desiredStart: Long,
        durationMillis: Long,
        windowStart: Long,
        windowEnd: Long,
        occupied: List<Interval>
    ): Long? {
        if (durationMillis <= 0) return desiredStart

        var candidate = max(desiredStart, windowStart)
        val merged = mergeIntervals(occupied)

        for (block in merged) {
            if (block.end <= candidate) continue
            if (candidate + durationMillis <= block.start) {
                return candidate
            }
            if (candidate < block.end) candidate = block.end
            if (candidate + durationMillis > windowEnd) return null
        }

        return if (candidate + durationMillis <= windowEnd) candidate else null
    }

    private fun mergeIntervals(intervals: List<Interval>): List<Interval> {
        val sorted = intervals
            .filter { it.end > it.start }
            .sortedBy { it.start }
        if (sorted.isEmpty()) return emptyList()

        val merged = mutableListOf<Interval>()
        var current = sorted.first()
        for (next in sorted.drop(1)) {
            if (next.start <= current.end) {
                current = Interval(current.start, max(current.end, next.end))
            } else {
                merged += current
                current = next
            }
        }
        merged += current
        return merged
    }

    private fun intervalMinutes(intervals: List<Interval>): Int =
        mergeIntervals(intervals)
            .sumOf { ((it.end - it.start) / MINUTE_MILLIS).toInt() }

    private fun localMidnight(millis: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = millis
            set(Calendar.HOUR_OF_DAY, 0)
            set(Calendar.MINUTE, 0)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        return cal.timeInMillis
    }

    private fun mondayStart(millis: Long): Long {
        val cal = Calendar.getInstance().apply {
            timeInMillis = localMidnight(millis)
            firstDayOfWeek = Calendar.MONDAY
            val dow = get(Calendar.DAY_OF_WEEK)
            val delta = (dow - Calendar.MONDAY + 7) % 7
            add(Calendar.DAY_OF_MONTH, -delta)
        }
        return cal.timeInMillis
    }

    private fun weekdayMondayZero(millis: Long): Int {
        val dow = Calendar.getInstance().apply { timeInMillis = millis }
            .get(Calendar.DAY_OF_WEEK)
        return (dow - Calendar.MONDAY + 7) % 7
    }

    private fun dateString(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd", Locale.US).apply {
            timeZone = TimeZone.getDefault()
        }.format(Date(millis))

    private fun normalize(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (index in 0 until array.length()) {
                array.optJSONObject(index)?.let { add(it) }
            }
        }
    }

    private fun JSONObject.nullableInt(key: String): Int? {
        if (!has(key) || isNull(key)) return null
        return optInt(key)
    }

    companion object {
        private const val PREFS = "bridge_calendar_prefs"
        private const val KEY_DAY_START = "planning_day_start_minute"
        private const val KEY_DAY_END = "planning_day_end_minute"
        private const val DEFAULT_DAY_START = 5 * 60 + 30
        private const val DEFAULT_DAY_END = 22 * 60
        const val MINUTE_MILLIS = 60_000L
        const val HOUR_MILLIS = 60 * MINUTE_MILLIS
        const val DAY_MILLIS = 24 * HOUR_MILLIS
    }
}
