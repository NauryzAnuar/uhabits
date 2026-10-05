package org.isoron.uhabits.bridge

import org.json.JSONObject

object BridgeSemantics {

    fun toJson(): JSONObject {
        return JSONObject().apply {

            put(
                "format",
                "Loop Bridge"
            )

            put(
                "statusMeaning",
                JSONObject().apply {

                    put(
                        "YES_MANUAL",
                        "User explicitly marked the habit as completed."
                    )

                    put(
                        "YES_AUTO",
                        "Loop derived this state automatically from the habit frequency. It is not a manual completion."
                    )

                    put(
                        "NO",
                        "Habit was explicitly not completed."
                    )

                    put(
                        "UNKNOWN",
                        "No completion was recorded. For this user's adherence analysis, treat this as a missed or untracked day."
                    )
                }
            )

            put(
                "readingRules",
                JSONObject().apply {

                    put(
                        "unknownCountsAsMissed",
                        true
                    )

                    put(
                        "latestRecordWins",
                        true
                    )

                    put(
                        "entryIdentity",
                        "habitUuid + date"
                    )

                    put(
                        "habitIdentity",
                        "uuid"
                    )

                    put(
                        "entryUpsertMeaning",
                        "For the same habit and date, the newest entry_upsert represents the current state."
                    )

                    put(
                        "habitUpsertMeaning",
                        "The newest habit_upsert represents the current habit metadata."
                    )

                    put(
                        "habitDeletedMeaning",
                        "habit_deleted means the habit was deleted."
                    )

                    put(
                        "notesMeaning",
                        "notes contains the user's text note for that entry."
                    )
                }
            )

            put(
                "lifeModelMeaning",
                JSONObject().apply {

                    put(
                        "lifeSpheres",
                        "Areas of life. Default spheres are only templates; users may create, rename or archive their own."
                    )

                    put(
                        "goals",
                        "Desired outcomes or directions. A goal is not the same thing as a habit."
                    )

                    put(
                        "habitSpheres",
                        "Links habits to one or more life spheres."
                    )

                    put(
                        "habitPlanning",
                        "Planning metadata such as duration, priority and role."
                    )

                    put(
                        "routines",
                        "User-defined sequences or contexts. Morning, evening and work routines are examples, not fixed types."
                    )

                    put(
                        "routineSteps",
                        "Ordered members of a routine."
                    )

                    put(
                        "anchors",
                        "Context cues that can trigger a habit or routine, such as time, another habit, an event or a custom cue."
                    )

                    put(
                        "schedules",
                        "Expected timing and recurrence rules. Routine and schedule are separate concepts."
                    )

                    put(
                        "calendarPrivacy",
                        "Calendar event details must not be exposed to AI until the selected privacy mode is enforced."
                    )
                }
            )
        }
    }
}