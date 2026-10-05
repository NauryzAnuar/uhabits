package org.isoron.uhabits.bridge

import android.content.Context
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.json.JSONObject

/**
 * One-time personal bootstrap for the current 28-habit dataset.
 *
 * It only runs when the life model is still empty, so it will not overwrite
 * choices the user makes later in the UI.
 */
object BridgePersonalSeeder {

    fun seedIfEmpty(
        context: Context,
        habitList: HabitList,
        repository: BridgeLifeRepository
    ): Boolean {
        val v1 = seedV1IfNeeded(context, habitList, repository)
        val v2 = seedPlanningV2IfNeeded(context, habitList, repository)
        return v1 || v2
    }

    private fun seedV1IfNeeded(
        context: Context,
        habitList: HabitList,
        repository: BridgeLifeRepository
    ): Boolean {
        val prefs =
            context.getSharedPreferences(
                "bridge_prefs",
                Context.MODE_PRIVATE
            )

        if (prefs.getBoolean("personal_seed_v1_done", false)) {
            return false
        }

        val life = repository.exportJson()

        val habits = habitList
            .filter { !it.isArchived }
            .associateBy { normalize(it.name) }

        val spheresByKey = life.objects("lifeSpheres")
            .associateBy { it.optString("system_key") }

        fun sphereUuid(key: String): String? =
            spheresByKey[key]?.optString("uuid")?.takeIf { it.isNotBlank() }

        fun habit(name: String): Habit? = habits[normalize(name)]

        val existingPrimarySphereByHabit =
            life.objects("habitSpheres")
                .filter { it.optString("relation") == "PRIMARY" }
                .associateBy { it.optString("habit_uuid") }

        fun linkSphere(name: String, key: String) {
            val h = habit(name) ?: return
            val habitUuid = h.uuid ?: return
            if (existingPrimarySphereByHabit.containsKey(habitUuid)) return
            val sphere = sphereUuid(key) ?: return
            repository.linkHabitToSphere(habitUuid, sphere, "PRIMARY")
        }

        // Primary sphere for every current habit.
        val sphereAssignments = mapOf(
            "Дуа ояну/шторы" to "spirituality",
            "Фаджр 25 минут" to "spirituality",
            "У. зикр ниет су 27 минут" to "spirituality",
            "Завтрак 30 минут" to "health",
            "Сон не менее 7 часов" to "health",
            "заправь постель 1 минут" to "home_environment",
            "Комплекс разминки" to "health",
            "Ходьба 10 минут" to "health",
            "15 минут дочка обнять сказать 1 теплое слово" to "relationships",
            "5 намаз во время" to "spirituality",
            "Контакт Нурик" to "relationships",
            "20 минут досуга" to "leisure",
            "1 Намаз в мечети джаммат кроме джума" to "spirituality",
            "Хушу" to "spirituality",
            "В постели к 22:00" to "health",
            "Азкары перед сном" to "spirituality",
            "Читать 10 минут" to "learning",
            "Написать отцу" to "relationships",
            "Разговор жена обнять без телефона во время еды" to "relationships",
            "Запись результат рабочего дня" to "work_career",
            "Вечерние азкары" to "spirituality",
            "Совместное время с женой" to "relationships",
            "Ким тарак/сакал мурт" to "health",
            "Рабочие входящие" to "work_career",
            "План дня - 3 дела" to "work_career",
            "Убрать 5 вещей на свои места" to "home_environment",
            "Назвать 1 эмоции дня" to "emotional_wellbeing",
            "Помочь одному человеку вне семьи" to "community"
        )

        sphereAssignments.forEach { (name, key) -> linkSphere(name, key) }

        // Time estimates. Explicit times from habit names are used directly;
        // the rest are conservative planning estimates and can be edited later.
        data class Planning(
            val minutes: Int?,
            val role: String = "CORE",
            val note: String = ""
        )

        val planning = mapOf(
            "Дуа ояну/шторы" to Planning(2, note = "Автооценка Loop 0.3 — можно изменить."),
            "Фаджр 25 минут" to Planning(25),
            "У. зикр ниет су 27 минут" to Planning(27),
            "Завтрак 30 минут" to Planning(30),
            "Сон не менее 7 часов" to Planning(420),
            "заправь постель 1 минут" to Planning(1),
            "Комплекс разминки" to Planning(5, note = "Автооценка Loop 0.3 — можно изменить."),
            "Ходьба 10 минут" to Planning(10),
            "15 минут дочка обнять сказать 1 теплое слово" to Planning(15),
            "5 намаз во время" to Planning(null, role = "TRACKING", note = "Сводный показатель; время не считаем отдельно, чтобы не дублировать отдельные молитвы."),
            "Контакт Нурик" to Planning(5, note = "Автооценка Loop 0.3 — можно изменить."),
            "20 минут досуга" to Planning(20),
            "1 Намаз в мечети джаммат кроме джума" to Planning(30, note = "Автооценка без дороги — можно изменить."),
            "Хушу" to Planning(0, role = "TRACKING", note = "Практика внутри намаза; отдельное время не добавляем."),
            "В постели к 22:00" to Planning(0, role = "TRACKING", note = "Это временная граница, а не отдельный блок нагрузки."),
            "Азкары перед сном" to Planning(5, note = "Автооценка Loop 0.3 — можно изменить."),
            "Читать 10 минут" to Planning(10),
            "Написать отцу" to Planning(5, note = "Автооценка Loop 0.3 — можно изменить."),
            "Разговор жена обнять без телефона во время еды" to Planning(10, note = "Часто совмещается с ужином; можно изменить."),
            "Запись результат рабочего дня" to Planning(5, note = "Автооценка Loop 0.3 — можно изменить."),
            "Вечерние азкары" to Planning(10, note = "Автооценка Loop 0.3 — можно изменить."),
            "Совместное время с женой" to Planning(30, note = "Автооценка Loop 0.3 — можно изменить."),
            "Ким тарак/сакал мурт" to Planning(5, note = "Автооценка Loop 0.3 — можно изменить."),
            "Рабочие входящие" to Planning(10, note = "Автооценка Loop 0.3 — можно изменить."),
            "План дня - 3 дела" to Planning(5, note = "Автооценка Loop 0.3 — можно изменить."),
            "Убрать 5 вещей на свои места" to Planning(5, note = "Автооценка Loop 0.3 — можно изменить."),
            "Назвать 1 эмоции дня" to Planning(1, note = "Автооценка Loop 0.3 — можно изменить."),
            "Помочь одному человеку вне семьи" to Planning(15, note = "Очень вариативно; это только стартовая оценка.")
        )

        val existingPlanningHabits =
            life.objects("habitPlanning")
                .map { it.optString("habit_uuid") }
                .toSet()

        planning.forEach { (name, p) ->
            val h = habit(name) ?: return@forEach
            val uuid = h.uuid ?: return@forEach
            if (uuid in existingPlanningHabits) return@forEach
            repository.setHabitPlanning(
                habitUuid = uuid,
                role = p.role,
                typicalDurationMinutes = p.minutes,
                planningNotes = p.note
            )
        }

        // Goals: outcome/direction, not duplicates of individual habits.
        val goals = linkedMapOf<String, String>()
        val existingGoalsByTitle =
            life.objects("goals")
                .associateBy { it.optString("title") }

        fun createGoal(key: String, title: String, sphereKey: String): String {
            val existingUuid =
                existingGoalsByTitle[title]
                    ?.optString("uuid")
                    ?.takeIf { it.isNotBlank() }

            val uuid = existingUuid ?: repository.createGoal(
                title = title,
                primarySphereUuid = sphereUuid(sphereKey)
            )
            goals[key] = uuid
            return uuid
        }

        createGoal("spiritual", "Стабильная духовная практика", "spirituality")
        createGoal("health", "Здоровье, сон и энергия", "health")
        createGoal("family", "Крепкие отношения с семьёй", "relationships")
        createGoal("social", "Поддерживать близкие связи", "relationships")
        createGoal("work", "Системный рабочий день", "work_career")
        createGoal("learning", "Регулярно развиваться", "learning")
        createGoal("home", "Поддерживать порядок дома", "home_environment")
        createGoal("rest", "Регулярно полноценно отдыхать", "leisure")
        createGoal("emotion", "Развивать эмоциональную осознанность", "emotional_wellbeing")
        createGoal("community", "Регулярно помогать людям", "community")

        val goalAssignments = mapOf(
            "spiritual" to listOf(
                "Дуа ояну/шторы",
                "Фаджр 25 минут",
                "У. зикр ниет су 27 минут",
                "5 намаз во время",
                "1 Намаз в мечети джаммат кроме джума",
                "Хушу",
                "Вечерние азкары",
                "Азкары перед сном"
            ),
            "health" to listOf(
                "Завтрак 30 минут",
                "Сон не менее 7 часов",
                "Комплекс разминки",
                "Ходьба 10 минут",
                "В постели к 22:00",
                "Ким тарак/сакал мурт"
            ),
            "family" to listOf(
                "15 минут дочка обнять сказать 1 теплое слово",
                "Написать отцу",
                "Разговор жена обнять без телефона во время еды",
                "Совместное время с женой"
            ),
            "social" to listOf("Контакт Нурик"),
            "work" to listOf(
                "План дня - 3 дела",
                "Рабочие входящие",
                "Запись результат рабочего дня"
            ),
            "learning" to listOf("Читать 10 минут"),
            "home" to listOf(
                "заправь постель 1 минут",
                "Убрать 5 вещей на свои места"
            ),
            "rest" to listOf("20 минут досуга"),
            "emotion" to listOf("Назвать 1 эмоции дня"),
            "community" to listOf("Помочь одному человеку вне семьи")
        )

        goalAssignments.forEach { (goalKey, names) ->
            val goalUuid = goals[goalKey] ?: return@forEach
            names.forEachIndexed { index, name ->
                val h = habit(name) ?: return@forEachIndexed
                val habitUuid = h.uuid ?: return@forEachIndexed
                repository.linkHabitToGoal(habitUuid, goalUuid, index)
            }
        }

        // Routines: only habits that really form a useful sequence.
        val routines = linkedMapOf<String, String>()
        val existingRoutinesByName =
            life.objects("routines")
                .associateBy { it.optString("name") }
        val existingSteps = life.objects("routineSteps")

        fun createRoutine(
            key: String,
            title: String,
            habitNames: List<String>,
            anchorText: String
        ): String {
            val existingUuid =
                existingRoutinesByName[title]
                    ?.optString("uuid")
                    ?.takeIf { it.isNotBlank() }

            val isNew = existingUuid == null
            val uuid = existingUuid ?: repository.createRoutine(title)
            routines[key] = uuid

            val alreadyLinkedHabitUuids =
                existingSteps
                    .filter {
                        it.optString("routine_uuid") == uuid &&
                            it.optString("target_type") == "HABIT"
                    }
                    .map { it.optString("target_uuid") }
                    .toSet()

            var nextPosition =
                existingSteps
                    .filter { it.optString("routine_uuid") == uuid }
                    .maxOfOrNull { it.optInt("position") }
                    ?.plus(1)
                    ?: 0

            habitNames.forEachIndexed { index, name ->
                val h = habit(name) ?: return@forEachIndexed
                val habitUuid = h.uuid ?: return@forEachIndexed
                if (habitUuid in alreadyLinkedHabitUuids) return@forEachIndexed
                repository.addRoutineHabit(
                    uuid,
                    habitUuid,
                    if (isNew) index else nextPosition++
                )
            }

            if (isNew) {
                repository.addAnchor(
                    targetType = "ROUTINE",
                    targetUuid = uuid,
                    anchorType = "EVENT",
                    customText = anchorText
                )
            }

            return uuid
        }

        createRoutine(
            "morning",
            "Утро",
            listOf(
                "Дуа ояну/шторы",
                "Фаджр 25 минут",
                "У. зикр ниет су 27 минут",
                "Комплекс разминки",
                "Завтрак 30 минут",
                "заправь постель 1 минут",
                "Ким тарак/сакал мурт"
            ),
            "После пробуждения"
        )

        createRoutine(
            "work_start",
            "Начало рабочего дня",
            listOf(
                "План дня - 3 дела",
                "Рабочие входящие"
            ),
            "Когда начинается рабочий день"
        )

        createRoutine(
            "work_close",
            "Завершение рабочего дня",
            listOf(
                "Запись результат рабочего дня",
                "Назвать 1 эмоции дня"
            ),
            "Перед завершением рабочего дня"
        )

        createRoutine(
            "family_evening",
            "Семейный вечер",
            listOf(
                "15 минут дочка обнять сказать 1 теплое слово",
                "Разговор жена обнять без телефона во время еды"
            ),
            "Вечером дома"
        )

        val bedtime = createRoutine(
            "bedtime",
            "Перед сном",
            listOf(
                "Читать 10 минут",
                "Азкары перед сном",
                "В постели к 22:00"
            ),
            "Перед сном"
        )

        val bedtimeScheduleExists =
            life.objects("schedules").any {
                it.optString("target_type") == "ROUTINE" &&
                    it.optString("target_uuid") == bedtime
            }

        if (!bedtimeScheduleExists) {
            repository.addSchedule(
                targetType = "ROUTINE",
                targetUuid = bedtime,
                latestEndMinute = 22 * 60,
                flexibility = "WINDOW"
            )
        }

        // Routines can support more than one goal.
        fun linkRoutine(goalKey: String, routineKey: String, position: Int = 0) {
            val goalUuid = goals[goalKey] ?: return
            val routineUuid = routines[routineKey] ?: return
            repository.linkRoutineToGoal(routineUuid, goalUuid, position)
        }

        linkRoutine("spiritual", "morning")
        linkRoutine("health", "morning")
        linkRoutine("work", "work_start")
        linkRoutine("work", "work_close", 1)
        linkRoutine("emotion", "work_close")
        linkRoutine("family", "family_evening")
        linkRoutine("health", "bedtime", 1)
        linkRoutine("spiritual", "bedtime", 1)
        linkRoutine("learning", "bedtime")

        prefs.edit()
            .putBoolean("personal_seed_v1_done", true)
            .apply()

        return true
    }

    private fun seedPlanningV2IfNeeded(
        context: Context,
        habitList: HabitList,
        repository: BridgeLifeRepository
    ): Boolean {
        val prefs = context.getSharedPreferences(
            "bridge_prefs",
            Context.MODE_PRIVATE
        )

        if (prefs.getBoolean("personal_seed_v2_done", false)) {
            return false
        }

        val habits = habitList
            .filter { !it.isArchived }
            .associateBy { normalize(it.name) }

        fun habit(name: String): Habit? = habits[normalize(name)]

        data class Estimate(
            val min: Int,
            val typical: Int,
            val max: Int,
            val startMinute: Int? = null,
            val startLabel: String? = null,
            val role: String = "CORE",
            val note: String = "Автооценка Loop 0.3 — можно изменить."
        )

        val estimates = mapOf(
            "Дуа ояну/шторы" to Estimate(1, 2, 4, 6 * 60 + 20, "сразу после пробуждения"),
            "Фаджр 25 минут" to Estimate(15, 25, 35, null, "по времени фаджра"),
            "У. зикр ниет су 27 минут" to Estimate(20, 27, 35, null, "сразу после фаджра"),
            "Завтрак 30 минут" to Estimate(20, 30, 40, 7 * 60 + 30),
            "Сон не менее 7 часов" to Estimate(420, 450, 540, 22 * 60, "ночной сон"),
            "заправь постель 1 минут" to Estimate(1, 1, 2, 8 * 60 + 5),
            "Комплекс разминки" to Estimate(3, 5, 8, 7 * 60 + 20),
            "Ходьба 10 минут" to Estimate(10, 15, 30, 18 * 60 + 30),
            "15 минут дочка обнять сказать 1 теплое слово" to Estimate(15, 20, 30, 18 * 60),
            "5 намаз во время" to Estimate(0, 0, 0, null, "в течение дня — по времени каждого намаза", "TRACKING", "Сводный показатель; отдельное время не добавляем."),
            "Контакт Нурик" to Estimate(3, 5, 15, 20 * 60),
            "20 минут досуга" to Estimate(20, 30, 60, 20 * 60 + 30),
            "1 Намаз в мечети джаммат кроме джума" to Estimate(25, 45, 75, 13 * 60 + 30, "на выходном около зухра/асра"),
            "Хушу" to Estimate(0, 0, 1, null, "внутри одного из намазов", "TRACKING", "Практика внутри намаза; отдельное время почти не добавляет."),
            "В постели к 22:00" to Estimate(0, 0, 0, 22 * 60, "граница отхода ко сну", "TRACKING", "Это временная граница, а не отдельный блок нагрузки."),
            "Азкары перед сном" to Estimate(4, 7, 12, 21 * 60 + 45, "перед сном"),
            "Читать 10 минут" to Estimate(10, 15, 30, 21 * 60 + 25),
            "Написать отцу" to Estimate(3, 7, 15, 20 * 60),
            "Разговор жена обнять без телефона во время еды" to Estimate(10, 20, 40, 19 * 60, "во время ужина"),
            "Запись результат рабочего дня" to Estimate(3, 5, 10, 17 * 60 + 45),
            "Вечерние азкары" to Estimate(7, 10, 15, null, "после магриба"),
            "Совместное время с женой" to Estimate(30, 60, 120, 20 * 60),
            "Ким тарак/сакал мурт" to Estimate(3, 5, 10, 8 * 60),
            "Рабочие входящие" to Estimate(10, 15, 30, 9 * 60 + 10),
            "План дня - 3 дела" to Estimate(3, 5, 10, 9 * 60),
            "Убрать 5 вещей на свои места" to Estimate(3, 5, 10, 21 * 60),
            "Назвать 1 эмоции дня" to Estimate(1, 2, 5, 17 * 60 + 55),
            "Помочь одному человеку вне семьи" to Estimate(10, 20, 60, 13 * 60, "гибкий недельный слот")
        )

        val db = BridgeLifeDatabase.get(context).writableDatabase
        val life = repository.exportJson()

        db.beginTransaction()
        try {
            estimates.forEach { (name, estimate) ->
                val h = habit(name) ?: return@forEach
                val uuid = h.uuid ?: return@forEach

                repository.setHabitPlanning(
                    habitUuid = uuid,
                    role = estimate.role,
                    minDurationMinutes = estimate.min,
                    typicalDurationMinutes = estimate.typical,
                    maxDurationMinutes = estimate.max,
                    planningNotes = estimate.note
                )

                db.delete(
                    "schedules",
                    "target_type = ? AND target_uuid = ?",
                    arrayOf("HABIT", uuid)
                )

                if (estimate.startMinute != null) {
                    repository.addSchedule(
                        targetType = "HABIT",
                        targetUuid = uuid,
                        preferredStartMinute = estimate.startMinute,
                        flexibility = "WINDOW"
                    )
                }

                db.delete(
                    "anchors",
                    "target_type = ? AND target_uuid = ?",
                    arrayOf("HABIT", uuid)
                )

                if (!estimate.startLabel.isNullOrBlank()) {
                    repository.addAnchor(
                        targetType = "HABIT",
                        targetUuid = uuid,
                        anchorType = "CUSTOM",
                        customText = estimate.startLabel
                    )
                }
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }

        prefs.edit()
            .putBoolean("personal_seed_v2_done", true)
            .apply()

        return true
    }

    private fun normalize(value: String): String =
        value.trim().lowercase().replace(Regex("\\s+"), " ")

    private fun JSONObject.objects(key: String): List<JSONObject> {
        val array = optJSONArray(key) ?: return emptyList()
        return buildList {
            for (i in 0 until array.length()) {
                array.optJSONObject(i)?.let { add(it) }
            }
        }
    }
}
