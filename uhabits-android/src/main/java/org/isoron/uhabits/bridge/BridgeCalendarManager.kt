package org.isoron.uhabits.bridge

import android.Manifest
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import java.util.TimeZone
import java.util.UUID

/**
 * Provider-neutral bridge between Loop planning and Android's Calendar Provider.
 *
 * On a normal Android phone the Calendar Provider is already synchronized with
 * the Google account configured on the device, so Loop does not need to store a
 * Google password or OAuth token just to read/write the user's calendar.
 */
class BridgeCalendarManager(
    private val context: Context
) {
    private val resolver = context.contentResolver
    private val db = BridgeLifeDatabase.get(context).writableDatabase
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    data class CalendarRef(
        val id: Long,
        val displayName: String,
        val accountName: String,
        val accountType: String,
        val ownerAccount: String?,
        val isPrimary: Boolean
    )

    data class BusyBlock(
        val uuid: String,
        val title: String?,
        val startMillis: Long,
        val endMillis: Long,
        val externalId: String?
    )

    data class PlanItem(
        val uuid: String,
        val date: String,
        val targetType: String,
        val targetUuid: String,
        val startMillis: Long?,
        val endMillis: Long?,
        val status: String,
        val locked: Boolean,
        val source: String
    )

    fun hasCalendarPermission(): Boolean =
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_CALENDAR
        ) == PackageManager.PERMISSION_GRANTED &&
            ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.WRITE_CALENDAR
            ) == PackageManager.PERMISSION_GRANTED

    fun listWritableGoogleCalendars(): List<CalendarRef> =
        listGoogleCalendars(CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR)

    private fun listReadableGoogleCalendars(): List<CalendarRef> =
        listGoogleCalendars(CalendarContract.Calendars.CAL_ACCESS_READ)

    private fun listGoogleCalendars(minAccess: Int): List<CalendarRef> {
        if (!hasCalendarPermission()) return emptyList()

        val projection = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.OWNER_ACCOUNT,
            CalendarContract.Calendars.IS_PRIMARY,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Calendars.VISIBLE
        )

        val result = mutableListOf<CalendarRef>()

        resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            projection,
            null,
            null,
            "${CalendarContract.Calendars.ACCOUNT_NAME} ASC, " +
                "${CalendarContract.Calendars.IS_PRIMARY} DESC, " +
                "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val access = cursor.getInt(6)
                val visible = cursor.getInt(7) != 0
                val accountType = cursor.getString(3) ?: ""

                if (!visible) continue
                if (access < minAccess) continue
                if (accountType != GOOGLE_ACCOUNT_TYPE) continue

                result += CalendarRef(
                    id = cursor.getLong(0),
                    displayName = cursor.getString(1) ?: "Google Calendar",
                    accountName = cursor.getString(2) ?: "",
                    accountType = accountType,
                    ownerAccount = if (cursor.isNull(4)) null else cursor.getString(4),
                    isPrimary = cursor.getInt(5) != 0
                )
            }
        }

        return result
    }

    fun selectedCalendar(): CalendarRef? {
        if (!hasCalendarPermission()) return null

        val calendars = listWritableGoogleCalendars()
        if (calendars.isEmpty()) return null

        val selectedId = prefs.getLong(KEY_CALENDAR_ID, -1L)
        val selected = calendars.firstOrNull { it.id == selectedId }
        if (selected != null) return selected

        val fallback = calendars.firstOrNull { it.isPrimary } ?: calendars.first()
        selectCalendar(fallback)
        return fallback
    }

    fun selectCalendar(calendar: CalendarRef) {
        prefs.edit()
            .putLong(KEY_CALENDAR_ID, calendar.id)
            .putString(KEY_ACCOUNT_NAME, calendar.accountName)
            .putString(KEY_CALENDAR_NAME, calendar.displayName)
            .apply()

        ensureCalendarSource(calendar.accountName)
    }

    fun selectedAccountName(): String? =
        selectedCalendar()?.accountName
            ?: prefs.getString(KEY_ACCOUNT_NAME, null)

    fun selectedCalendarName(): String? =
        selectedCalendar()?.displayName
            ?: prefs.getString(KEY_CALENDAR_NAME, null)

    /**
     * Imports busy Google Calendar intervals into Loop's local planning DB.
     * Loop-created events are treated specially: moving them in Google Calendar
     * updates the corresponding internal plan item instead of double-counting it.
     */
    fun refreshFromGoogle(
        startMillis: Long,
        endMillis: Long
    ): Int {
        if (!hasCalendarPermission()) return 0

        val selected = selectedCalendar() ?: return 0
        val accountCalendars = listReadableGoogleCalendars()
            .filter { it.accountName == selected.accountName }

        if (accountCalendars.isEmpty()) return 0

        val calendarIds = accountCalendars.map { it.id }.toSet()
        val sourceUuid = ensureCalendarSource(selected.accountName)

        db.delete(
            "calendar_blocks",
            "source_uuid = ? AND start_epoch_millis < ? AND end_epoch_millis > ?",
            arrayOf(sourceUuid, endMillis.toString(), startMillis.toString())
        )

        val uriBuilder = CalendarContract.Instances.CONTENT_URI.buildUpon()
        ContentUris.appendId(uriBuilder, startMillis)
        ContentUris.appendId(uriBuilder, endMillis)

        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.CALENDAR_ID,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.AVAILABILITY
        )

        var imported = 0

        resolver.query(
            uriBuilder.build(),
            projection,
            null,
            null,
            "${CalendarContract.Instances.BEGIN} ASC"
        )?.use { cursor ->
            while (cursor.moveToNext()) {
                val eventId = cursor.getLong(0)
                val calendarId = cursor.getLong(1)
                if (calendarId !in calendarIds) continue

                val title = if (cursor.isNull(2)) null else cursor.getString(2)
                val description = if (cursor.isNull(3)) null else cursor.getString(3)
                val begin = cursor.getLong(4)
                val end = cursor.getLong(5)
                val availability = if (cursor.isNull(7)) {
                    CalendarContract.Events.AVAILABILITY_BUSY
                } else {
                    cursor.getInt(7)
                }

                val loopPlanUuid = extractLoopPlanUuid(description)
                if (loopPlanUuid != null) {
                    updatePlanFromGoogle(
                        planUuid = loopPlanUuid,
                        begin = begin,
                        end = end
                    )
                    continue
                }

                if (availability == CalendarContract.Events.AVAILABILITY_FREE) {
                    continue
                }

                val now = System.currentTimeMillis()
                val blockUuid = UUID.nameUUIDFromBytes(
                    "google:$eventId:$begin".toByteArray()
                ).toString()

                val values = ContentValues().apply {
                    put("uuid", blockUuid)
                    put("source_uuid", sourceUuid)
                    put("title", title ?: "Занято")
                    put("start_epoch_millis", begin)
                    put("end_epoch_millis", end)
                    putNull("recurrence_rule")
                    put("block_type", "GOOGLE_EVENT")
                    put("locked", 1)
                    put("external_id", eventId.toString())
                    put("created_at", now)
                    put("updated_at", now)
                }

                db.insertWithOnConflict(
                    "calendar_blocks",
                    null,
                    values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
                )
                imported++
            }
        }

        return imported
    }

    /**
     * Writes internal Loop plan items to the selected Google Calendar.
     * Existing Loop events are updated in place, so repeated syncs do not create duplicates.
     */
    fun pushPlanToGoogle(
        startMillis: Long,
        endMillis: Long,
        titleResolver: (targetType: String, targetUuid: String) -> String
    ): Int {
        if (!hasCalendarPermission()) return 0

        val selected = selectedCalendar() ?: return 0
        val items = queryPlanItemsByTime(startMillis, endMillis)
        var synced = 0

        for (item in items) {
            val begin = item.startMillis ?: continue
            val end = item.endMillis ?: continue
            if (end <= begin) continue

            val marker = "$LOOP_MARKER${item.uuid}"
            val existingEventId = findLoopEvent(selected.id, marker)
            val targetTitle = titleResolver(item.targetType, item.targetUuid)

            val values = ContentValues().apply {
                put(CalendarContract.Events.CALENDAR_ID, selected.id)
                put(CalendarContract.Events.TITLE, "Loop • $targetTitle")
                put(
                    CalendarContract.Events.DESCRIPTION,
                    "Запланировано Loop Bridge\n$marker"
                )
                put(CalendarContract.Events.DTSTART, begin)
                put(CalendarContract.Events.DTEND, end)
                put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
                put(CalendarContract.Events.ALL_DAY, 0)
                put(CalendarContract.Events.AVAILABILITY, CalendarContract.Events.AVAILABILITY_BUSY)
                put(CalendarContract.Events.STATUS, CalendarContract.Events.STATUS_CONFIRMED)
            }

            if (existingEventId == null) {
                resolver.insert(
                    CalendarContract.Events.CONTENT_URI,
                    values
                )
            } else {
                resolver.update(
                    ContentUris.withAppendedId(
                        CalendarContract.Events.CONTENT_URI,
                        existingEventId
                    ),
                    values,
                    null,
                    null
                )
            }
            synced++
        }

        return synced
    }

    fun busyBlocks(
        startMillis: Long,
        endMillis: Long
    ): List<BusyBlock> {
        val result = mutableListOf<BusyBlock>()

        db.rawQuery(
            """
            SELECT uuid, title, start_epoch_millis, end_epoch_millis, external_id
            FROM calendar_blocks
            WHERE start_epoch_millis < ?
              AND end_epoch_millis > ?
            ORDER BY start_epoch_millis ASC
            """.trimIndent(),
            arrayOf(endMillis.toString(), startMillis.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += BusyBlock(
                    uuid = cursor.getString(0),
                    title = if (cursor.isNull(1)) null else cursor.getString(1),
                    startMillis = cursor.getLong(2),
                    endMillis = cursor.getLong(3),
                    externalId = if (cursor.isNull(4)) null else cursor.getString(4)
                )
            }
        }

        return result
    }

    fun planItems(date: String): List<PlanItem> {
        val result = mutableListOf<PlanItem>()

        db.rawQuery(
            """
            SELECT uuid, date, target_type, target_uuid,
                   planned_start_epoch_millis, planned_end_epoch_millis,
                   status, locked, source
            FROM plan_items
            WHERE date = ?
            ORDER BY planned_start_epoch_millis ASC
            """.trimIndent(),
            arrayOf(date)
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PlanItem(
                    uuid = cursor.getString(0),
                    date = cursor.getString(1),
                    targetType = cursor.getString(2),
                    targetUuid = cursor.getString(3),
                    startMillis = if (cursor.isNull(4)) null else cursor.getLong(4),
                    endMillis = if (cursor.isNull(5)) null else cursor.getLong(5),
                    status = cursor.getString(6),
                    locked = cursor.getInt(7) != 0,
                    source = cursor.getString(8)
                )
            }
        }

        return result
    }

    fun replaceAutoPlan(
        date: String,
        items: List<PlanItem>
    ) {
        db.beginTransaction()
        try {
            db.delete(
                "plan_items",
                "date = ? AND source = ? AND locked = 0",
                arrayOf(date, "AUTO")
            )

            val now = System.currentTimeMillis()
            items.forEach { item ->
                val values = ContentValues().apply {
                    put("uuid", item.uuid)
                    put("date", item.date)
                    put("target_type", item.targetType)
                    put("target_uuid", item.targetUuid)
                    if (item.startMillis == null) putNull("planned_start_epoch_millis")
                    else put("planned_start_epoch_millis", item.startMillis)
                    if (item.endMillis == null) putNull("planned_end_epoch_millis")
                    else put("planned_end_epoch_millis", item.endMillis)
                    put("status", item.status)
                    put("locked", if (item.locked) 1 else 0)
                    put("source", item.source)
                    put("created_at", now)
                    put("updated_at", now)
                }

                db.insertWithOnConflict(
                    "plan_items",
                    null,
                    values,
                    android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
                )
            }

            db.setTransactionSuccessful()
        } finally {
            db.endTransaction()
        }
    }

    private fun queryPlanItemsByTime(
        startMillis: Long,
        endMillis: Long
    ): List<PlanItem> {
        val result = mutableListOf<PlanItem>()

        db.rawQuery(
            """
            SELECT uuid, date, target_type, target_uuid,
                   planned_start_epoch_millis, planned_end_epoch_millis,
                   status, locked, source
            FROM plan_items
            WHERE planned_start_epoch_millis IS NOT NULL
              AND planned_end_epoch_millis IS NOT NULL
              AND planned_start_epoch_millis < ?
              AND planned_end_epoch_millis > ?
            ORDER BY planned_start_epoch_millis ASC
            """.trimIndent(),
            arrayOf(endMillis.toString(), startMillis.toString())
        ).use { cursor ->
            while (cursor.moveToNext()) {
                result += PlanItem(
                    uuid = cursor.getString(0),
                    date = cursor.getString(1),
                    targetType = cursor.getString(2),
                    targetUuid = cursor.getString(3),
                    startMillis = cursor.getLong(4),
                    endMillis = cursor.getLong(5),
                    status = cursor.getString(6),
                    locked = cursor.getInt(7) != 0,
                    source = cursor.getString(8)
                )
            }
        }

        return result
    }

    private fun findLoopEvent(
        calendarId: Long,
        marker: String
    ): Long? {
        resolver.query(
            CalendarContract.Events.CONTENT_URI,
            arrayOf(CalendarContract.Events._ID),
            "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
                "${CalendarContract.Events.DELETED} = 0 AND " +
                "${CalendarContract.Events.DESCRIPTION} LIKE ?",
            arrayOf(calendarId.toString(), "%$marker%"),
            null
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                return cursor.getLong(0)
            }
        }
        return null
    }

    private fun updatePlanFromGoogle(
        planUuid: String,
        begin: Long,
        end: Long
    ) {
        val values = ContentValues().apply {
            put("planned_start_epoch_millis", begin)
            put("planned_end_epoch_millis", end)
            put("updated_at", System.currentTimeMillis())
        }
        db.update(
            "plan_items",
            values,
            "uuid = ?",
            arrayOf(planUuid)
        )
    }

    private fun extractLoopPlanUuid(description: String?): String? {
        if (description.isNullOrBlank()) return null
        val index = description.indexOf(LOOP_MARKER)
        if (index < 0) return null
        return description
            .substring(index + LOOP_MARKER.length)
            .lineSequence()
            .firstOrNull()
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun ensureCalendarSource(accountName: String): String {
        val uuid = UUID.nameUUIDFromBytes(
            "google-account:$accountName".toByteArray()
        ).toString()
        val now = System.currentTimeMillis()

        var createdAt = now
        db.rawQuery(
            "SELECT created_at FROM calendar_sources WHERE uuid = ?",
            arrayOf(uuid)
        ).use { cursor ->
            if (cursor.moveToFirst()) createdAt = cursor.getLong(0)
        }

        val values = ContentValues().apply {
            put("uuid", uuid)
            put("source_type", "GOOGLE_CALENDAR")
            put("name", "Google Calendar • $accountName")
            put("enabled", 1)
            put("privacy_mode", "FREE_BUSY_ONLY")
            put("external_ref", accountName)
            put("created_at", createdAt)
            put("updated_at", now)
        }

        db.insertWithOnConflict(
            "calendar_sources",
            null,
            values,
            android.database.sqlite.SQLiteDatabase.CONFLICT_REPLACE
        )

        return uuid
    }

    companion object {
        private const val PREFS = "bridge_calendar_prefs"
        private const val KEY_CALENDAR_ID = "selected_calendar_id"
        private const val KEY_ACCOUNT_NAME = "selected_account_name"
        private const val KEY_CALENDAR_NAME = "selected_calendar_name"
        private const val GOOGLE_ACCOUNT_TYPE = "com.google"
        private const val LOOP_MARKER = "LOOP_PLAN_UUID:"
    }
}
