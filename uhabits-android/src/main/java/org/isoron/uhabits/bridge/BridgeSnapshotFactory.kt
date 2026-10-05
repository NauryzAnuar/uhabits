package org.isoron.uhabits.bridge

import org.isoron.platform.time.getToday
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.HabitList
import org.isoron.uhabits.core.models.NumericalHabitType

object BridgeSnapshotFactory {

    fun create(habitList: HabitList): BridgeSnapshot {
        val today = getToday()

        return BridgeSnapshot(
            habits = habitList.mapNotNull { habit ->
                val id = habit.id ?: return@mapNotNull null

                val originalToday =
                    habit.originalEntries.get(today)

                val manualCompletedToday =
                    if (habit.isNumerical) {
                        if (originalToday.value == Entry.UNKNOWN) {
                            false
                        } else {
                            val value = originalToday.value / 1000.0

                            when (habit.targetType) {
                                NumericalHabitType.AT_LEAST ->
                                    value >= habit.targetValue

                                NumericalHabitType.AT_MOST ->
                                    value <= habit.targetValue
                            }
                        }
                    } else {
                        originalToday.value == Entry.YES_MANUAL
                    }

                val history =
                    habit.computedEntries
                        .getByInterval(
                            today.minus(29),
                            today
                        )
                        .map { entry ->
                            BridgeEntry(
                                date = entry.date.toCSVString(),
                                value = entry.value,
                                status = entry.formattedValue,
                                notes = entry.notes
                            )
                        }

                BridgeHabit(
                    id = id,
                    name = habit.name,
                    description = habit.description,
                    question = habit.question,
                    archived = habit.isArchived,
                    habitType = habit.type.name,
                    isNumerical = habit.isNumerical,
                    frequencyNumerator =
                        habit.frequency.numerator,
                    frequencyDenominator =
                        habit.frequency.denominator,
                    targetType =
                        habit.targetType.name,
                    targetValue =
                        habit.targetValue,
                    unit =
                        habit.unit,
                    enteredToday =
                        habit.isEnteredToday(),
                    completedToday =
                        habit.isCompletedToday(),
                    manualCompletedToday =
                        manualCompletedToday,
                    history =
                        history
                )
            }
        )
    }
}