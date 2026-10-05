package org.isoron.uhabits.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.isoron.uhabits.core.commands.ArchiveHabitsCommand
import org.isoron.uhabits.core.commands.ChangeHabitColorCommand
import org.isoron.uhabits.core.commands.Command
import org.isoron.uhabits.core.commands.CreateHabitCommand
import org.isoron.uhabits.core.commands.CreateRepetitionCommand
import org.isoron.uhabits.core.commands.DeleteHabitsCommand
import org.isoron.uhabits.core.commands.EditHabitCommand
import org.isoron.uhabits.core.commands.UnarchiveHabitsCommand
import org.isoron.uhabits.core.models.Entry
import org.isoron.uhabits.core.models.Habit
import org.isoron.uhabits.core.models.HabitList
import org.json.JSONObject
import java.io.BufferedWriter
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.OutputStreamWriter
import java.util.concurrent.Executors

object BridgeArchiveStore {

    private const val PREFS =
        "bridge_archive_prefs"

    private const val KEY_EXPORT_URI =
        "export_uri"

    private const val KEY_DRIVE_SCHEMA_VERSION =
        "drive_schema_version"

    private const val FILE_NAME =
        "bridge_archive.jsonl"

    private const val CURRENT_SCHEMA_VERSION =
        3

    private val executor =
        Executors.newSingleThreadExecutor()

    fun hasExportTarget(
        context: Context
    ): Boolean {
        return context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .contains(KEY_EXPORT_URI)
    }

    fun setExportTarget(
        context: Context,
        uri: Uri
    ) {
        try {
            context.contentResolver
                .takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
        } catch (e: Exception) {
            Log.w(
                "LoopBridge",
                "Could not persist archive URI",
                e
            )
        }

        context
            .getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )
            .edit()
            .putString(
                KEY_EXPORT_URI,
                uri.toString()
            )
            .putInt(
                KEY_DRIVE_SCHEMA_VERSION,
                0
            )
            .apply()
    }

    fun save(
        context: Context,
        habitList: HabitList
    ) {
        executor.execute {
            try {
                context.openFileOutput(
                    FILE_NAME,
                    Context.MODE_PRIVATE
                ).use { output ->

                    BufferedWriter(
                        OutputStreamWriter(
                            output,
                            Charsets.UTF_8
                        )
                    ).use { writer ->

                        writer.write(
                            JSONObject().apply {
                                put(
                                    "recordType",
                                    "header"
                                )

                                put(
                                    "schemaVersion",
                                    CURRENT_SCHEMA_VERSION
                                )

                                put(
                                    "generatedAt",
                                    System.currentTimeMillis()
                                )

                                put(
                                    "bridge",
                                    BridgeSemantics.toJson()
                                )
                            }.toString()
                        )

                        writer.newLine()

                        habitList.forEach { habit ->
                            writeHabitBaseline(
                                writer,
                                habit
                            )

                            habit.originalEntries
                                .getKnown()
                                .forEach { entry ->

                                    writer.write(
                                        JSONObject().apply {
                                            put(
                                                "recordType",
                                                "entry"
                                            )

                                            put(
                                                "habitId",
                                                habit.id
                                            )

                                            put(
                                                "habitUuid",
                                                habit.uuid ?: ""
                                            )

                                            put(
                                                "date",
                                                entry.date
                                                    .toCSVString()
                                            )

                                            put(
                                                "value",
                                                entry.value
                                            )

                                            put(
                                                "status",
                                                entry.formattedValue
                                            )

                                            put(
                                                "notes",
                                                entry.notes
                                            )
                                        }.toString()
                                    )

                                    writer.newLine()
                                }
                        }
                    }
                }

                syncWholeFileToDrive(
                    context
                )

                context
                    .getSharedPreferences(
                        PREFS,
                        Context.MODE_PRIVATE
                    )
                    .edit()
                    .putInt(
                        KEY_DRIVE_SCHEMA_VERSION,
                        CURRENT_SCHEMA_VERSION
                    )
                    .apply()

                Log.i(
                    "LoopBridge",
                    "Archive created successfully"
                )
            } catch (e: Exception) {
                Log.e(
                    "LoopBridge",
                    "Archive creation failed",
                    e
                )
            }
        }
    }

    fun saveIfDue(
        context: Context,
        habitList: HabitList
    ) {
        val file =
            context.getFileStreamPath(
                FILE_NAME
            )

        val prefs =
            context.getSharedPreferences(
                PREFS,
                Context.MODE_PRIVATE
            )

        if (
            !file.exists() ||
            file.length() == 0L ||
            needsMigration(file)
        ) {
            save(
                context,
                habitList
            )

            return
        }

        if (
            prefs.getInt(
                KEY_DRIVE_SCHEMA_VERSION,
                0
            ) < CURRENT_SCHEMA_VERSION
        ) {
            executor.execute {
                try {
                    syncWholeFileToDrive(
                        context
                    )

                    prefs.edit()
                        .putInt(
                            KEY_DRIVE_SCHEMA_VERSION,
                            CURRENT_SCHEMA_VERSION
                        )
                        .apply()

                    Log.i(
                        "LoopBridge",
                        "Drive archive migrated"
                    )
                } catch (e: Exception) {
                    Log.e(
                        "LoopBridge",
                        "Drive migration failed",
                        e
                    )
                }
            }
        }
    }

    private fun needsMigration(
        file: File
    ): Boolean {
        return try {
            val firstLine =
                file.bufferedReader(
                    Charsets.UTF_8
                ).use {
                    it.readLine()
                }

            if (firstLine.isNullOrBlank()) {
                true
            } else {
                val header =
                    JSONObject(firstLine)

                header.optInt(
                    "schemaVersion",
                    0
                ) < CURRENT_SCHEMA_VERSION
            }
        } catch (e: Exception) {
            true
        }
    }

    fun appendCommand(
        context: Context,
        command: Command,
        habitList: HabitList
    ) {
        val lines =
            buildCommandLines(
                command,
                habitList
            )

        if (lines.isEmpty()) {
            return
        }

        executor.execute {
            try {
                val file =
                    context.getFileStreamPath(
                        FILE_NAME
                    )

                FileOutputStream(
                    file,
                    true
                ).use { output ->

                    BufferedWriter(
                        OutputStreamWriter(
                            output,
                            Charsets.UTF_8
                        )
                    ).use { writer ->

                        lines.forEach { line ->
                            writer.write(line)
                            writer.newLine()
                        }
                    }
                }

                appendToDrive(
                    context,
                    lines
                )

                Log.i(
                    "LoopBridge",
                    "Archive delta appended"
                )
            } catch (e: Exception) {
                Log.e(
                    "LoopBridge",
                    "Archive delta failed",
                    e
                )
            }
        }
    }

    private fun buildCommandLines(
        command: Command,
        habitList: HabitList
    ): List<String> {

        val now =
            System.currentTimeMillis()

        return when (command) {

            is CreateRepetitionCommand -> {
                val entry =
                    Entry(
                        command.date,
                        command.value,
                        command.notes
                    )

                listOf(
                    JSONObject().apply {
                        put(
                            "recordType",
                            "entry_upsert"
                        )

                        put(
                            "eventAt",
                            now
                        )

                        put(
                            "habitId",
                            command.habit.id
                        )

                        put(
                            "habitUuid",
                            command.habit.uuid ?: ""
                        )

                        put(
                            "date",
                            command.date
                                .toCSVString()
                        )

                        put(
                            "value",
                            command.value
                        )

                        put(
                            "status",
                            entry.formattedValue
                        )

                        put(
                            "notes",
                            command.notes
                        )
                    }.toString()
                )
            }

            is EditHabitCommand -> {
                val habit =
                    habitList.getById(
                        command.habitId
                    )

                if (habit == null) {
                    emptyList()
                } else {
                    listOf(
                        habitEvent(
                            habit,
                            now
                        )
                    )
                }
            }

            is CreateHabitCommand -> {
                val habit =
                    habitList.firstOrNull {
                        it.uuid ==
                                command.model.uuid
                    }

                if (habit == null) {
                    emptyList()
                } else {
                    listOf(
                        habitEvent(
                            habit,
                            now
                        )
                    )
                }
            }

            is ArchiveHabitsCommand -> {
                command.selected.map {
                    habitEvent(
                        it,
                        now
                    )
                }
            }

            is UnarchiveHabitsCommand -> {
                command.selected.map {
                    habitEvent(
                        it,
                        now
                    )
                }
            }

            is ChangeHabitColorCommand -> {
                command.selected.map {
                    habitEvent(
                        it,
                        now
                    )
                }
            }

            is DeleteHabitsCommand -> {
                command.selected.map { habit ->
                    JSONObject().apply {
                        put(
                            "recordType",
                            "habit_deleted"
                        )

                        put(
                            "eventAt",
                            now
                        )

                        put(
                            "habitId",
                            habit.id
                        )

                        put(
                            "habitUuid",
                            habit.uuid ?: ""
                        )
                    }.toString()
                }
            }

            else -> emptyList()
        }
    }

    private fun habitEvent(
        habit: Habit,
        eventAt: Long
    ): String {
        return JSONObject().apply {
            put(
                "recordType",
                "habit_upsert"
            )

            put(
                "eventAt",
                eventAt
            )

            put(
                "id",
                habit.id
            )

            put(
                "uuid",
                habit.uuid ?: ""
            )

            put(
                "name",
                habit.name
            )

            put(
                "description",
                habit.description
            )

            put(
                "question",
                habit.question
            )

            put(
                "archived",
                habit.isArchived
            )

            put(
                "habitType",
                habit.type.name
            )

            put(
                "frequencyNumerator",
                habit.frequency.numerator
            )

            put(
                "frequencyDenominator",
                habit.frequency.denominator
            )

            put(
                "targetType",
                habit.targetType.name
            )

            put(
                "targetValue",
                habit.targetValue
            )

            put(
                "unit",
                habit.unit
            )
        }.toString()
    }

    private fun writeHabitBaseline(
        writer: BufferedWriter,
        habit: Habit
    ) {
        writer.write(
            JSONObject().apply {
                put(
                    "recordType",
                    "habit"
                )

                put(
                    "id",
                    habit.id
                )

                put(
                    "uuid",
                    habit.uuid ?: ""
                )

                put(
                    "name",
                    habit.name
                )

                put(
                    "description",
                    habit.description
                )

                put(
                    "question",
                    habit.question
                )

                put(
                    "archived",
                    habit.isArchived
                )

                put(
                    "habitType",
                    habit.type.name
                )

                put(
                    "frequencyNumerator",
                    habit.frequency.numerator
                )

                put(
                    "frequencyDenominator",
                    habit.frequency.denominator
                )

                put(
                    "targetType",
                    habit.targetType.name
                )

                put(
                    "targetValue",
                    habit.targetValue
                )

                put(
                    "unit",
                    habit.unit
                )
            }.toString()
        )

        writer.newLine()
    }

    private fun appendToDrive(
        context: Context,
        lines: List<String>
    ) {
        val uriString =
            context
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )
                .getString(
                    KEY_EXPORT_URI,
                    null
                )
                ?: return

        try {
            context.contentResolver
                .openOutputStream(
                    Uri.parse(uriString),
                    "wa"
                )
                ?.use { output ->

                    BufferedWriter(
                        OutputStreamWriter(
                            output,
                            Charsets.UTF_8
                        )
                    ).use { writer ->

                        lines.forEach { line ->
                            writer.write(line)
                            writer.newLine()
                        }
                    }
                }
        } catch (e: Exception) {
            Log.w(
                "LoopBridge",
                "Drive append unsupported, full sync used",
                e
            )

            syncWholeFileToDrive(
                context
            )
        }
    }

    private fun syncWholeFileToDrive(
        context: Context
    ) {
        val uriString =
            context
                .getSharedPreferences(
                    PREFS,
                    Context.MODE_PRIVATE
                )
                .getString(
                    KEY_EXPORT_URI,
                    null
                )
                ?: return

        val file =
            context.getFileStreamPath(
                FILE_NAME
            )

        context.contentResolver
            .openOutputStream(
                Uri.parse(uriString),
                "wt"
            )
            ?.use { output ->

                FileInputStream(file)
                    .use { input ->
                        input.copyTo(output)
                    }
            }
    }
}