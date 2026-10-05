package org.isoron.uhabits.bridge

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

class BridgeLifeDatabase private constructor(
    context: Context
) : SQLiteOpenHelper(
    context.applicationContext,
    DATABASE_NAME,
    null,
    DATABASE_VERSION
) {

    override fun onCreate(db: SQLiteDatabase) {
        db.beginTransaction()

        try {
            db.execSQL(
                """
                CREATE TABLE life_spheres (
                    uuid TEXT PRIMARY KEY,
                    system_key TEXT UNIQUE,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    position INTEGER NOT NULL DEFAULT 0,
                    archived INTEGER NOT NULL DEFAULT 0,
                    is_default INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE goals (
                    uuid TEXT PRIMARY KEY,
                    title TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    status TEXT NOT NULL DEFAULT 'ACTIVE',
                    primary_sphere_uuid TEXT,
                    start_date TEXT,
                    target_date TEXT,
                    metric_type TEXT,
                    target_value REAL,
                    unit TEXT,
                    priority INTEGER NOT NULL DEFAULT 0,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE goal_habits (
                    goal_uuid TEXT NOT NULL,
                    habit_uuid TEXT NOT NULL,
                    position INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (
                        goal_uuid,
                        habit_uuid
                    )
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE goal_routines (
                    goal_uuid TEXT NOT NULL,
                    routine_uuid TEXT NOT NULL,
                    position INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (
                        goal_uuid,
                        routine_uuid
                    )
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE habit_spheres (
                    habit_uuid TEXT NOT NULL,
                    sphere_uuid TEXT NOT NULL,
                    relation TEXT NOT NULL DEFAULT 'PRIMARY',
                    position INTEGER NOT NULL DEFAULT 0,
                    PRIMARY KEY (
                        habit_uuid,
                        sphere_uuid
                    )
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE habit_planning (
                    habit_uuid TEXT PRIMARY KEY,
                    role TEXT NOT NULL DEFAULT 'CORE',
                    priority INTEGER NOT NULL DEFAULT 0,
                    min_duration_minutes INTEGER,
                    typical_duration_minutes INTEGER,
                    max_duration_minutes INTEGER,
                    energy_level TEXT,
                    planning_notes TEXT NOT NULL DEFAULT '',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE routines (
                    uuid TEXT PRIMARY KEY,
                    name TEXT NOT NULL,
                    description TEXT NOT NULL DEFAULT '',
                    enabled INTEGER NOT NULL DEFAULT 1,
                    min_duration_minutes INTEGER,
                    typical_duration_minutes INTEGER,
                    max_duration_minutes INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE routine_steps (
                    uuid TEXT PRIMARY KEY,
                    routine_uuid TEXT NOT NULL,
                    target_type TEXT NOT NULL,
                    target_uuid TEXT NOT NULL,
                    position INTEGER NOT NULL DEFAULT 0,
                    required INTEGER NOT NULL DEFAULT 1,
                    min_duration_minutes INTEGER,
                    typical_duration_minutes INTEGER,
                    max_duration_minutes INTEGER,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE anchors (
                    uuid TEXT PRIMARY KEY,
                    target_type TEXT NOT NULL,
                    target_uuid TEXT NOT NULL,
                    anchor_type TEXT NOT NULL,
                    source_type TEXT,
                    source_uuid TEXT,
                    time_minute_of_day INTEGER,
                    custom_text TEXT,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE schedules (
                    uuid TEXT PRIMARY KEY,
                    target_type TEXT NOT NULL,
                    target_uuid TEXT NOT NULL,
                    recurrence_rule TEXT,
                    timezone TEXT,
                    preferred_start_minute INTEGER,
                    preferred_end_minute INTEGER,
                    earliest_start_minute INTEGER,
                    latest_end_minute INTEGER,
                    flexibility TEXT NOT NULL DEFAULT 'FLEXIBLE',
                    enabled INTEGER NOT NULL DEFAULT 1,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )

            /*
             * Calendar is included in the foundation now,
             * but we will not connect Google/Outlook yet.
             */
            db.execSQL(
                """
                CREATE TABLE calendar_sources (
                    uuid TEXT PRIMARY KEY,
                    source_type TEXT NOT NULL,
                    name TEXT NOT NULL,
                    enabled INTEGER NOT NULL DEFAULT 1,
                    privacy_mode TEXT NOT NULL DEFAULT 'FREE_BUSY_ONLY',
                    external_ref TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE calendar_blocks (
                    uuid TEXT PRIMARY KEY,
                    source_uuid TEXT,
                    title TEXT,
                    start_epoch_millis INTEGER NOT NULL,
                    end_epoch_millis INTEGER NOT NULL,
                    recurrence_rule TEXT,
                    block_type TEXT NOT NULL DEFAULT 'EVENT',
                    locked INTEGER NOT NULL DEFAULT 0,
                    external_id TEXT,
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE TABLE plan_items (
                    uuid TEXT PRIMARY KEY,
                    date TEXT NOT NULL,
                    target_type TEXT NOT NULL,
                    target_uuid TEXT NOT NULL,
                    planned_start_epoch_millis INTEGER,
                    planned_end_epoch_millis INTEGER,
                    status TEXT NOT NULL DEFAULT 'PLANNED',
                    locked INTEGER NOT NULL DEFAULT 0,
                    source TEXT NOT NULL DEFAULT 'INTERNAL',
                    created_at INTEGER NOT NULL,
                    updated_at INTEGER NOT NULL
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE INDEX
                idx_habit_spheres_habit
                ON habit_spheres(habit_uuid)
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE INDEX
                idx_goal_habits_habit
                ON goal_habits(habit_uuid)
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE INDEX
                idx_routine_steps_routine
                ON routine_steps(routine_uuid, position)
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE INDEX
                idx_anchors_target
                ON anchors(target_type, target_uuid)
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE INDEX
                idx_schedules_target
                ON schedules(target_type, target_uuid)
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE INDEX
                idx_calendar_blocks_time
                ON calendar_blocks(
                    start_epoch_millis,
                    end_epoch_millis
                )
                """.trimIndent()
            )

            db.execSQL(
                """
                CREATE INDEX
                idx_plan_items_date
                ON plan_items(date)
                """.trimIndent()
            )

            seedDefaultSpheres(db)

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    override fun onUpgrade(
        db: SQLiteDatabase,
        oldVersion: Int,
        newVersion: Int
    ) {
        /*
         * Bridge DB starts at version 1.
         * Future Bridge migrations will be added here.
         */
    }

    private fun seedDefaultSpheres(
        db: SQLiteDatabase
    ) {
        val now =
            System.currentTimeMillis()

        val defaults =
            listOf(
                DefaultSphere(
                    uuid =
                        "00000000-0000-4000-8000-000000000001",
                    key = "health",
                    name = "Здоровье"
                ),
                DefaultSphere(
                    uuid =
                        "00000000-0000-4000-8000-000000000002",
                    key = "emotional_wellbeing",
                    name =
                        "Эмоциональное и психологическое благополучие"
                ),
                DefaultSphere(
                    uuid =
                        "00000000-0000-4000-8000-000000000003",
                    key = "relationships",
                    name = "Отношения и семья"
                ),
                DefaultSphere(
                    uuid =
                        "00000000-0000-4000-8000-000000000004",
                    key = "work_career",
                    name = "Работа и карьера"
                ),
                DefaultSphere(
                    uuid =
                        "00000000-0000-4000-8000-000000000005",
                    key = "learning",
                    name =
                        "Саморазвитие и обучение"
                ),
                DefaultSphere(
                    uuid =
                        "00000000-0000-4000-8000-000000000006",
                    key = "finances",
                    name =
                        "Финансы и материальная устойчивость"
                ),
                DefaultSphere(
                    uuid =
                        "00000000-0000-4000-8000-000000000007",
                    key = "home_environment",
                    name = "Дом и окружение"
                ),
                DefaultSphere(
                    uuid =
                        "00000000-0000-4000-8000-000000000008",
                    key = "leisure",
                    name =
                        "Отдых, досуг и хобби"
                ),
                DefaultSphere(
                    uuid =
                        "00000000-0000-4000-8000-000000000009",
                    key = "spirituality",
                    name =
                        "Духовность, смысл и ценности"
                ),
                DefaultSphere(
                    uuid =
                        "00000000-0000-4000-8000-000000000010",
                    key = "community",
                    name = "Сообщество и вклад"
                )
            )

        defaults.forEachIndexed {
                index,
                sphere ->

            val values =
                ContentValues().apply {
                    put("uuid", sphere.uuid)
                    put("system_key", sphere.key)
                    put("name", sphere.name)
                    put("description", "")
                    put("position", index)
                    put("archived", 0)
                    put("is_default", 1)
                    put("created_at", now)
                    put("updated_at", now)
                }

            db.insertWithOnConflict(
                "life_spheres",
                null,
                values,
                SQLiteDatabase.CONFLICT_IGNORE
            )
        }
    }

    private data class DefaultSphere(
        val uuid: String,
        val key: String,
        val name: String
    )

    companion object {

        private const val DATABASE_NAME =
            "loop_bridge.db"

        private const val DATABASE_VERSION =
            1

        @Volatile
        private var instance:
                BridgeLifeDatabase? = null

        fun get(
            context: Context
        ): BridgeLifeDatabase {
            return instance
                ?: synchronized(this) {
                    instance
                        ?: BridgeLifeDatabase(
                            context
                        ).also {
                            instance = it
                        }
                }
        }
    }
}