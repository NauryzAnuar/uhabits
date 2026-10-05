package org.isoron.uhabits.bridge

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import org.json.JSONArray
import org.json.JSONObject
import java.util.TimeZone
import java.util.UUID

class BridgeLifeRepository(
    context: Context
) {
    private val db: SQLiteDatabase =
        BridgeLifeDatabase
            .get(context)
            .writableDatabase

    fun exportJson(): JSONObject {
        return JSONObject().apply {
            put("schemaVersion", LIFE_MODEL_SCHEMA_VERSION)
            put("generatedAt", System.currentTimeMillis())
            put("lifeSpheres", tableToJson("life_spheres", "position ASC"))
            put("goals", tableToJson("goals", "priority DESC, created_at ASC"))
            put("goalHabits", tableToJson("goal_habits", "goal_uuid, position"))
            put("goalRoutines", tableToJson("goal_routines", "goal_uuid, position"))
            put("habitSpheres", tableToJson("habit_spheres", "habit_uuid, position"))
            put("habitPlanning", tableToJson("habit_planning", "priority DESC"))
            put("routines", tableToJson("routines", "created_at ASC"))
            put("routineSteps", tableToJson("routine_steps", "routine_uuid, position"))
            put("anchors", tableToJson("anchors", "created_at ASC"))
            put("schedules", tableToJson("schedules", "created_at ASC"))
            put("calendarSources", tableToJson("calendar_sources", "created_at ASC"))
            put("calendarBusyBlocks", calendarBlocksForExport())
            put("planItems", tableToJson("plan_items", "date ASC, planned_start_epoch_millis ASC"))
        }
    }

    fun createSphere(
        name: String,
        description: String = ""
    ): String {
        val uuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val values =
            ContentValues().apply {
                put("uuid", uuid)
                putNull("system_key")
                put("name", name)
                put("description", description)
                put("position", nextSpherePosition())
                put("archived", 0)
                put("is_default", 0)
                put("created_at", now)
                put("updated_at", now)
            }

        db.insertOrThrow("life_spheres", null, values)
        return uuid
    }

    fun createGoal(
        title: String,
        description: String = "",
        primarySphereUuid: String? = null
    ): String {
        val uuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val values =
            ContentValues().apply {
                put("uuid", uuid)
                put("title", title)
                put("description", description)
                put("status", "ACTIVE")
                putNullableText("primary_sphere_uuid", primarySphereUuid)
                putNull("start_date")
                putNull("target_date")
                putNull("metric_type")
                putNull("target_value")
                putNull("unit")
                put("priority", 0)
                put("created_at", now)
                put("updated_at", now)
            }

        db.insertOrThrow("goals", null, values)
        return uuid
    }

    fun createRoutine(
        name: String,
        description: String = ""
    ): String {
        val uuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val values =
            ContentValues().apply {
                put("uuid", uuid)
                put("name", name)
                put("description", description)
                put("enabled", 1)
                putNull("min_duration_minutes")
                putNull("typical_duration_minutes")
                putNull("max_duration_minutes")
                put("created_at", now)
                put("updated_at", now)
            }

        db.insertOrThrow("routines", null, values)
        return uuid
    }

    fun linkHabitToSphere(
        habitUuid: String,
        sphereUuid: String,
        relation: String = "PRIMARY",
        position: Int = 0
    ) {
        val values =
            ContentValues().apply {
                put("habit_uuid", habitUuid)
                put("sphere_uuid", sphereUuid)
                put("relation", relation)
                put("position", position)
            }

        db.insertWithOnConflict(
            "habit_spheres",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun setPrimarySphere(
        habitUuid: String,
        sphereUuid: String?
    ) {
        db.delete(
            "habit_spheres",
            "habit_uuid = ? AND relation = ?",
            arrayOf(habitUuid, "PRIMARY")
        )

        if (sphereUuid != null) {
            linkHabitToSphere(
                habitUuid = habitUuid,
                sphereUuid = sphereUuid,
                relation = "PRIMARY"
            )
        }
    }

    fun linkHabitToGoal(
        habitUuid: String,
        goalUuid: String,
        position: Int = 0,
        relation: String = "SUPPORT"
    ) {
        val values =
            ContentValues().apply {
                put("goal_uuid", goalUuid)
                put("habit_uuid", habitUuid)
                put("position", position)
                put("relation", relation)
            }

        db.insertWithOnConflict(
            "goal_habits",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun setPrimaryGoal(
        habitUuid: String,
        goalUuid: String?
    ) {
        db.delete(
            "goal_habits",
            "habit_uuid = ? AND relation = ?",
            arrayOf(habitUuid, "PRIMARY")
        )

        if (goalUuid != null) {
            linkHabitToGoal(
                habitUuid = habitUuid,
                goalUuid = goalUuid,
                position = 0,
                relation = "PRIMARY"
            )
        }
    }

    fun linkRoutineToGoal(
        routineUuid: String,
        goalUuid: String,
        position: Int = 0
    ) {
        val values =
            ContentValues().apply {
                put("goal_uuid", goalUuid)
                put("routine_uuid", routineUuid)
                put("position", position)
            }

        db.insertWithOnConflict(
            "goal_routines",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun setHabitPlanning(
        habitUuid: String,
        role: String = "CORE",
        priority: Int = 0,
        minDurationMinutes: Int? = null,
        typicalDurationMinutes: Int? = null,
        maxDurationMinutes: Int? = null,
        energyLevel: String? = null,
        planningNotes: String = ""
    ) {
        val now = System.currentTimeMillis()
        val existingCreatedAt =
            findHabitPlanningCreatedAt(habitUuid)
                ?: now

        val values =
            ContentValues().apply {
                put("habit_uuid", habitUuid)
                put("role", role)
                put("priority", priority)
                putNullableInt("min_duration_minutes", minDurationMinutes)
                putNullableInt("typical_duration_minutes", typicalDurationMinutes)
                putNullableInt("max_duration_minutes", maxDurationMinutes)
                putNullableText("energy_level", energyLevel)
                put("planning_notes", planningNotes)
                put("created_at", existingCreatedAt)
                put("updated_at", now)
            }

        db.insertWithOnConflict(
            "habit_planning",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun setHabitTypicalDuration(
        habitUuid: String,
        typicalDurationMinutes: Int?
    ) {
        val current =
            db.rawQuery(
                """
                SELECT role, priority, min_duration_minutes,
                       max_duration_minutes, energy_level,
                       planning_notes, created_at
                FROM habit_planning
                WHERE habit_uuid = ?
                """.trimIndent(),
                arrayOf(habitUuid)
            ).use { cursor ->
                if (!cursor.moveToFirst()) {
                    null
                } else {
                    HabitPlanningRow(
                        role = cursor.getString(0),
                        priority = cursor.getInt(1),
                        minDuration = cursor.intOrNull(2),
                        maxDuration = cursor.intOrNull(3),
                        energyLevel = cursor.stringOrNull(4),
                        planningNotes = cursor.getString(5),
                        createdAt = cursor.getLong(6)
                    )
                }
            }

        val now = System.currentTimeMillis()
        val values =
            ContentValues().apply {
                put("habit_uuid", habitUuid)
                put("role", current?.role ?: "CORE")
                put("priority", current?.priority ?: 0)
                putNullableInt("min_duration_minutes", current?.minDuration)
                putNullableInt("typical_duration_minutes", typicalDurationMinutes)
                putNullableInt("max_duration_minutes", current?.maxDuration)
                putNullableText("energy_level", current?.energyLevel)
                put("planning_notes", current?.planningNotes ?: "")
                put("created_at", current?.createdAt ?: now)
                put("updated_at", now)
            }

        db.insertWithOnConflict(
            "habit_planning",
            null,
            values,
            SQLiteDatabase.CONFLICT_REPLACE
        )
    }

    fun addRoutineHabit(
        routineUuid: String,
        habitUuid: String,
        position: Int,
        required: Boolean = true,
        isPrimary: Boolean = false,
        minDurationMinutes: Int? = null,
        typicalDurationMinutes: Int? = null,
        maxDurationMinutes: Int? = null,
        fixedUuid: String? = null
    ): String {
        val existing =
            findRoutineStep(
                routineUuid = routineUuid,
                habitUuid = habitUuid
            )

        if (existing != null) {
            val values =
                ContentValues().apply {
                    put("position", position)
                    put("required", if (required) 1 else 0)
                    put("is_primary", if (isPrimary) 1 else existing.isPrimary)
                    putNullableInt("min_duration_minutes", minDurationMinutes)
                    putNullableInt("typical_duration_minutes", typicalDurationMinutes)
                    putNullableInt("max_duration_minutes", maxDurationMinutes)
                    put("updated_at", System.currentTimeMillis())
                }

            db.update(
                "routine_steps",
                values,
                "uuid = ?",
                arrayOf(existing.uuid)
            )
            return existing.uuid
        }

        val uuid = fixedUuid ?: UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val values =
            ContentValues().apply {
                put("uuid", uuid)
                put("routine_uuid", routineUuid)
                put("target_type", "HABIT")
                put("target_uuid", habitUuid)
                put("position", position)
                put("required", if (required) 1 else 0)
                put("is_primary", if (isPrimary) 1 else 0)
                putNullableInt("min_duration_minutes", minDurationMinutes)
                putNullableInt("typical_duration_minutes", typicalDurationMinutes)
                putNullableInt("max_duration_minutes", maxDurationMinutes)
                put("created_at", now)
                put("updated_at", now)
            }

        db.insertOrThrow("routine_steps", null, values)
        return uuid
    }

    fun setPrimaryRoutine(
        habitUuid: String,
        routineUuid: String?
    ) {
        val clear =
            ContentValues().apply {
                put("is_primary", 0)
                put("updated_at", System.currentTimeMillis())
            }

        db.update(
            "routine_steps",
            clear,
            "target_type = ? AND target_uuid = ? AND is_primary = 1",
            arrayOf("HABIT", habitUuid)
        )

        if (routineUuid == null) {
            return
        }

        val existing =
            findRoutineStep(
                routineUuid = routineUuid,
                habitUuid = habitUuid
            )

        if (existing != null) {
            val values =
                ContentValues().apply {
                    put("is_primary", 1)
                    put("updated_at", System.currentTimeMillis())
                }

            db.update(
                "routine_steps",
                values,
                "uuid = ?",
                arrayOf(existing.uuid)
            )
        } else {
            addRoutineHabit(
                routineUuid = routineUuid,
                habitUuid = habitUuid,
                position = nextRoutinePosition(routineUuid),
                isPrimary = true
            )
        }
    }

    fun addAnchor(
        targetType: String,
        targetUuid: String,
        anchorType: String,
        sourceType: String? = null,
        sourceUuid: String? = null,
        timeMinuteOfDay: Int? = null,
        customText: String? = null
    ): String {
        val uuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val values =
            ContentValues().apply {
                put("uuid", uuid)
                put("target_type", targetType)
                put("target_uuid", targetUuid)
                put("anchor_type", anchorType)
                putNullableText("source_type", sourceType)
                putNullableText("source_uuid", sourceUuid)
                putNullableInt("time_minute_of_day", timeMinuteOfDay)
                putNullableText("custom_text", customText)
                put("enabled", 1)
                put("created_at", now)
                put("updated_at", now)
            }

        db.insertOrThrow("anchors", null, values)
        return uuid
    }

    fun addSchedule(
        targetType: String,
        targetUuid: String,
        recurrenceRule: String? = null,
        preferredStartMinute: Int? = null,
        preferredEndMinute: Int? = null,
        earliestStartMinute: Int? = null,
        latestEndMinute: Int? = null,
        flexibility: String = "FLEXIBLE"
    ): String {
        val uuid = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()

        val values =
            ContentValues().apply {
                put("uuid", uuid)
                put("target_type", targetType)
                put("target_uuid", targetUuid)
                putNullableText("recurrence_rule", recurrenceRule)
                put("timezone", TimeZone.getDefault().id)
                putNullableInt("preferred_start_minute", preferredStartMinute)
                putNullableInt("preferred_end_minute", preferredEndMinute)
                putNullableInt("earliest_start_minute", earliestStartMinute)
                putNullableInt("latest_end_minute", latestEndMinute)
                put("flexibility", flexibility)
                put("enabled", 1)
                put("created_at", now)
                put("updated_at", now)
            }

        db.insertOrThrow("schedules", null, values)
        return uuid
    }

    private fun nextSpherePosition(): Int {
        db.rawQuery(
            "SELECT COALESCE(MAX(position), -1) + 1 FROM life_spheres",
            null
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0)
            }
        }
        return 0
    }

    private fun nextRoutinePosition(
        routineUuid: String
    ): Int {
        db.rawQuery(
            """
            SELECT COALESCE(MAX(position), -1) + 1
            FROM routine_steps
            WHERE routine_uuid = ?
            """.trimIndent(),
            arrayOf(routineUuid)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getInt(0)
            }
        }
        return 0
    }

    private fun findHabitPlanningCreatedAt(
        habitUuid: String
    ): Long? {
        db.rawQuery(
            "SELECT created_at FROM habit_planning WHERE habit_uuid = ?",
            arrayOf(habitUuid)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    private fun findRoutineStep(
        routineUuid: String,
        habitUuid: String
    ): RoutineStepRow? {
        db.rawQuery(
            """
            SELECT uuid, is_primary
            FROM routine_steps
            WHERE routine_uuid = ?
              AND target_type = 'HABIT'
              AND target_uuid = ?
            LIMIT 1
            """.trimIndent(),
            arrayOf(routineUuid, habitUuid)
        ).use { cursor ->
            if (cursor.moveToFirst()) {
                return RoutineStepRow(
                    uuid = cursor.getString(0),
                    isPrimary = cursor.getInt(1)
                )
            }
        }
        return null
    }

    private fun calendarBlocksForExport(): JSONArray {
        val result = JSONArray()

        db.rawQuery(
            """
            SELECT b.uuid, b.source_uuid, b.start_epoch_millis,
                   b.end_epoch_millis, b.block_type, b.locked,
                   b.external_id, s.privacy_mode, b.title
            FROM calendar_blocks b
            LEFT JOIN calendar_sources s ON s.uuid = b.source_uuid
            ORDER BY b.start_epoch_millis ASC
            """.trimIndent(),
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val privacy = cursor.stringOrNull(7) ?: "FREE_BUSY_ONLY"
                result.put(
                    JSONObject().apply {
                        put("uuid", cursor.getString(0))
                        put("source_uuid", cursor.stringOrNull(1))
                        put("start_epoch_millis", cursor.getLong(2))
                        put("end_epoch_millis", cursor.getLong(3))
                        put("block_type", cursor.getString(4))
                        put("locked", cursor.getInt(5))
                        if (privacy == "FULL") {
                            put("external_id", cursor.stringOrNull(6))
                            put("title", cursor.stringOrNull(8))
                        } else {
                            put("external_id", JSONObject.NULL)
                            put("title", JSONObject.NULL)
                        }
                    }
                )
            }
        }

        return result
    }

    private fun tableToJson(
        table: String,
        orderBy: String? = null
    ): JSONArray {
        val result = JSONArray()

        db.query(
            table,
            null,
            null,
            null,
            null,
            null,
            orderBy
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result.put(cursorToJson(cursor))
            }
        }

        return result
    }

    private fun cursorToJson(
        cursor: Cursor
    ): JSONObject {
        val json = JSONObject()

        for (index in 0 until cursor.columnCount) {
            val key = cursor.getColumnName(index)

            when (cursor.getType(index)) {
                Cursor.FIELD_TYPE_NULL ->
                    json.put(key, JSONObject.NULL)

                Cursor.FIELD_TYPE_INTEGER ->
                    json.put(key, cursor.getLong(index))

                Cursor.FIELD_TYPE_FLOAT ->
                    json.put(key, cursor.getDouble(index))

                Cursor.FIELD_TYPE_STRING ->
                    json.put(key, cursor.getString(index))

                else ->
                    json.put(key, cursor.getString(index))
            }
        }

        return json
    }

    private fun ContentValues.putNullableInt(
        key: String,
        value: Int?
    ) {
        if (value == null) {
            putNull(key)
        } else {
            put(key, value)
        }
    }

    private fun ContentValues.putNullableText(
        key: String,
        value: String?
    ) {
        if (value == null) {
            putNull(key)
        } else {
            put(key, value)
        }
    }

    private fun Cursor.intOrNull(index: Int): Int? =
        if (isNull(index)) null else getInt(index)

    private fun Cursor.stringOrNull(index: Int): String? =
        if (isNull(index)) null else getString(index)

    private data class HabitPlanningRow(
        val role: String,
        val priority: Int,
        val minDuration: Int?,
        val maxDuration: Int?,
        val energyLevel: String?,
        val planningNotes: String,
        val createdAt: Long
    )

    private data class RoutineStepRow(
        val uuid: String,
        val isPrimary: Int
    )

    companion object {
        const val LIFE_MODEL_SCHEMA_VERSION = 3
    }
}
