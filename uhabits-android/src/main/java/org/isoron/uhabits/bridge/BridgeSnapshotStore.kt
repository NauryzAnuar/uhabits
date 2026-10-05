package org.isoron.uhabits.bridge

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.Executors

object BridgeSnapshotStore {

    private const val PREFS =
        "bridge_prefs"

    private const val KEY_EXPORT_URI =
        "export_uri"

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
                "Could not persist URI permission",
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
            .apply()
    }

    fun save(
        context: Context,
        snapshot: BridgeSnapshot
    ) {
        val habitsJson =
            JSONArray()

        snapshot.habits.forEach { habit ->

            val historyJson =
                JSONArray()

            habit.history.forEach { entry ->

                historyJson.put(
                    JSONObject().apply {

                        put(
                            "date",
                            entry.date
                        )

                        put(
                            "value",
                            entry.value
                        )

                        put(
                            "status",
                            entry.status
                        )

                        put(
                            "notes",
                            entry.notes
                        )
                    }
                )
            }

            habitsJson.put(
                JSONObject().apply {

                    put(
                        "id",
                        habit.id
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
                        habit.archived
                    )

                    put(
                        "habitType",
                        habit.habitType
                    )

                    put(
                        "isNumerical",
                        habit.isNumerical
                    )

                    put(
                        "frequencyNumerator",
                        habit.frequencyNumerator
                    )

                    put(
                        "frequencyDenominator",
                        habit.frequencyDenominator
                    )

                    put(
                        "targetType",
                        habit.targetType
                    )

                    put(
                        "targetValue",
                        habit.targetValue
                    )

                    put(
                        "unit",
                        habit.unit
                    )

                    put(
                        "enteredToday",
                        habit.enteredToday
                    )

                    put(
                        "completedToday",
                        habit.completedToday
                    )

                    put(
                        "manualCompletedToday",
                        habit.manualCompletedToday
                    )

                    put(
                        "history",
                        historyJson
                    )
                }
            )
        }

        /*
         * Opening this repository automatically
         * creates loop_bridge.db on first run and
         * seeds the 10 default life spheres.
         */
        val lifeModel =
            BridgeLifeRepository(
                context
            ).exportJson()

        val json =
            JSONObject().apply {

                put(
                    "schemaVersion",
                    snapshot.schemaVersion
                )

                put(
                    "generatedAt",
                    snapshot.generatedAt
                )

                put(
                    "bridge",
                    BridgeSemantics.toJson()
                )

                put(
                    "lifeModel",
                    lifeModel
                )

                put(
                    "habits",
                    habitsJson
                )
            }.toString(2)

        context.openFileOutput(
            "bridge_snapshot.json",
            Context.MODE_PRIVATE
        ).use {
            it.write(
                json.toByteArray(
                    Charsets.UTF_8
                )
            )
        }

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

        if (uriString != null) {
            executor.execute {
                try {
                    context.contentResolver
                        .openOutputStream(
                            Uri.parse(
                                uriString
                            ),
                            "wt"
                        )
                        ?.use {
                            it.write(
                                json.toByteArray(
                                    Charsets.UTF_8
                                )
                            )
                        }
                } catch (e: Exception) {
                    Log.e(
                        "LoopBridge",
                        "Google Drive export failed",
                        e
                    )
                }
            }
        }
    }
}