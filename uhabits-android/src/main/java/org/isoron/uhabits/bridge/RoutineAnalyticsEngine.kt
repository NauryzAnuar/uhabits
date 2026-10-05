package org.isoron.uhabits.bridge

import org.isoron.platform.time.LocalDate
import org.isoron.uhabits.HabitsApplication
import org.isoron.uhabits.core.models.Entry
import org.json.JSONObject

/** Detects the weakest A -> B transition inside user-defined routines. */
class RoutineAnalyticsEngine(
    private val app: HabitsApplication,
    private val repository: BridgeLifeRepository
) {
    data class Breakpoint(
        val routineName: String,
        val fromHabitName: String,
        val toHabitName: String,
        val opportunities: Int,
        val breaks: Int,
        val breakRate: Double
    )

    fun weakestBreakpoint(
        endDayMillis: Long,
        lookbackDays: Int = 28
    ): Breakpoint? {
        val life = repository.exportJson()
        val routines = life.objects("routines")
            .associateBy { it.optString("uuid") }
        val stepsByRoutine = life.objects("routineSteps")
            .filter { it.optString("target_type") == "HABIT" }
            .groupBy { it.optString("routine_uuid") }

        val habits = app.component.habitList
            .mapNotNull { habit -> habit.uuid?.let { it to habit } }
            .toMap()

        var best: Breakpoint? = null

        for ((routineUuid, rawSteps) in stepsByRoutine) {
            val routineName = routines[routineUuid]?.optString("name") ?: continue
            val steps = rawSteps.sortedBy { it.optInt("position") }
            if (steps.size < 2) continue

            for (index in 0 until steps.lastIndex) {
                val from = habits[steps[index].optString("target_uuid")] ?: continue
                val to = habits[steps[index + 1].optString("target_uuid")] ?: continue

                var opportunities = 0
                var breaks = 0

                for (offset in 0 until lookbackDays) {
                    val millis = endDayMillis - offset * LifePlanningEngine.DAY_MILLIS
                    val date = LocalDate.fromUnixTime(millis)
                    val fromDone = from.originalEntries.get(date).value == Entry.YES_MANUAL
                    val toDone = to.originalEntries.get(date).value == Entry.YES_MANUAL

                    if (fromDone) {
                        opportunities++
                        if (!toDone) breaks++
                    }
                }

                if (opportunities < 2) continue
                val rate = breaks.toDouble() / opportunities
                val current = Breakpoint(
                    routineName = routineName,
                    fromHabitName = from.name,
                    toHabitName = to.name,
                    opportunities = opportunities,
                    breaks = breaks,
                    breakRate = rate
                )

                if (best == null || current.breakRate > best!!.breakRate) {
                    best = current
                }
            }
        }

        return best
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
