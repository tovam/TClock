@file:Suppress("MagicNumber")

package org.fossify.clock.cl1.provider

import android.Manifest
import android.content.ContentProviderOperation
import android.content.ContentResolver
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.OperationApplicationException
import android.database.Cursor
import android.os.RemoteException
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import org.fossify.clock.cl1.Cl1CanonicalEventCodec
import org.fossify.clock.cl1.Cl1Email
import org.fossify.clock.cl1.Cl1EmailException
import org.fossify.clock.cl1.Cl1IncompatibleException

class AndroidCalendarContractAdapter(
    context: Context,
) : Cl1CalendarAdapter {
    private val appContext = context.applicationContext
    private val resolver: ContentResolver
        get() = appContext.contentResolver

    override fun listCalendars(): List<Cl1CalendarDescriptor> {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            return emptyList()
        }
        val cursor = resolver.query(
            CalendarContract.Calendars.CONTENT_URI,
            CALENDAR_PROJECTION,
            null,
            null,
            "${CalendarContract.Calendars.CALENDAR_DISPLAY_NAME} COLLATE NOCASE"
        ) ?: return emptyList()
        return cursor.use {
            buildList {
                while (cursor.moveToNext()) {
                    add(cursor.readCalendar())
                }
            }
        }
    }

    override fun listEvents(
        beginMillis: Long,
        endMillis: Long,
    ): List<Cl1EventSnapshot> {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            return emptyList()
        }
        require(beginMillis <= endMillis)
        val selection = "${CalendarContract.Events.DTSTART} <= ? AND (" +
            "${CalendarContract.Events.DTEND} IS NULL OR " +
            "${CalendarContract.Events.DTEND} >= ?)"
        val cursor = resolver.query(
            CalendarContract.Events.CONTENT_URI,
            EVENT_PROJECTION,
            selection,
            arrayOf(endMillis.toString(), beginMillis.toString()),
            "${CalendarContract.Events.DTSTART} ASC"
        ) ?: return emptyList()
        return cursor.use {
            buildList {
                while (cursor.moveToNext()) {
                    cursor.readEvent()?.let(::add)
                }
            }
        }
    }

    override fun readEvent(ref: Cl1EventRef): Cl1EventSnapshot? {
        if (!hasPermission(Manifest.permission.READ_CALENDAR)) {
            return null
        }
        return querySingleEvent(
            "${CalendarContract.Events._ID} = ? AND " +
                "${CalendarContract.Events.CALENDAR_ID} = ?",
            arrayOf(ref.eventId.toString(), ref.calendarId.toString())
        )
    }

    override fun createEvent(
        calendar: Cl1CalendarDescriptor,
        createToken: String,
        value: Cl1EventWrite,
    ): Cl1CreateResult {
        val ineligible = writeIneligibility(calendar, value)
        if (ineligible != null) {
            return Cl1CreateResult.Ineligible(ineligible)
        }
        val uid = createUid(createToken)
        findByCreateUid(calendar.ref.calendarId, uid)?.let {
            return verifyCreatedEvent(it, uid, value, existing = true)
        }

        val values = try {
            value.toContentValues(calendar.ref.calendarId, uid)
        } catch (exception: Cl1CalendarIncompatibleException) {
            return Cl1CreateResult.Ineligible(exception.field)
        }
        val operations = arrayListOf(
            ContentProviderOperation.newAssertQuery(CalendarContract.Events.CONTENT_URI)
                .withSelection(
                    "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
                        "${CalendarContract.Events.UID_2445} = ?",
                    arrayOf(calendar.ref.calendarId.toString(), uid)
                )
                .withExpectedCount(0)
                .build(),
            ContentProviderOperation.newInsert(CalendarContract.Events.CONTENT_URI)
                .withValues(values)
                .build()
        )
        return try {
            val results = resolver.applyBatch(CalendarContract.AUTHORITY, operations)
            val createdId = results.getOrNull(1)?.uri?.let(ContentUris::parseId)
            val created = createdId?.let {
                readEvent(Cl1EventRef(it, calendar.ref.calendarId))
            } ?: findByCreateUid(calendar.ref.calendarId, uid)
            if (created == null) {
                Cl1CreateResult.Failed("createdEventMissing")
            } else {
                verifyCreatedEvent(created, uid, value, existing = false)
            }
        } catch (_: OperationApplicationException) {
            resolveCreateFailure(calendar.ref.calendarId, uid, value)
        } catch (_: RemoteException) {
            resolveCreateFailure(calendar.ref.calendarId, uid, value)
        } catch (_: SecurityException) {
            Cl1CreateResult.Ineligible("writePermission")
        } catch (_: IllegalArgumentException) {
            Cl1CreateResult.Ineligible("providerCreate")
        }
    }

    override fun updateEvent(
        expected: Cl1EventSnapshot,
        value: Cl1EventWrite,
    ): Cl1MutationResult {
        val ineligible = writeIneligibility(expected.calendar, value)
        if (ineligible != null) {
            return Cl1MutationResult.Ineligible(ineligible)
        }
        val values = try {
            value.toContentValues(expected.ref.calendarId, uid = null)
        } catch (exception: Cl1CalendarIncompatibleException) {
            return Cl1MutationResult.Ineligible(exception.field)
        }
        values.remove(CalendarContract.Events.CALENDAR_ID)
        val precondition = EventPrecondition.from(expected)
        return try {
            when (resolver.update(
                CalendarContract.Events.CONTENT_URI,
                values,
                precondition.selection,
                precondition.arguments
            )) {
                1 -> {
                    val updated = readEvent(expected.ref)
                        ?: return Cl1MutationResult.Failed("updatedEventMissing")
                    verifyMutation(updated, value)
                }

                0 -> classifyPreconditionFailure(expected.ref)
                else -> Cl1MutationResult.Failed("multipleEventsUpdated")
            }
        } catch (_: SecurityException) {
            Cl1MutationResult.Ineligible("writePermission")
        } catch (_: IllegalArgumentException) {
            Cl1MutationResult.Ineligible("providerUpdate")
        }
    }

    override fun deleteEvent(expected: Cl1EventSnapshot): Cl1MutationResult {
        if (!expected.calendar.capabilities.contains(
                Cl1CalendarCapability.CONDITIONAL_DELETE
            )
        ) {
            return Cl1MutationResult.Ineligible("conditionalDelete")
        }
        val precondition = EventPrecondition.from(expected)
        return try {
            when (resolver.delete(
                CalendarContract.Events.CONTENT_URI,
                precondition.selection,
                precondition.arguments
            )) {
                1 -> {
                    if (readEvent(expected.ref) == null) {
                        Cl1MutationResult.Applied(event = null)
                    } else {
                        Cl1MutationResult.Failed("deletedEventStillVisible")
                    }
                }

                0 -> classifyPreconditionFailure(expected.ref)
                else -> Cl1MutationResult.Failed("multipleEventsDeleted")
            }
        } catch (_: SecurityException) {
            Cl1MutationResult.Ineligible("writePermission")
        } catch (_: IllegalArgumentException) {
            Cl1MutationResult.Ineligible("providerDelete")
        }
    }

    private fun resolveCreateFailure(
        calendarId: Long,
        uid: String,
        value: Cl1EventWrite,
    ): Cl1CreateResult {
        val existing = findByCreateUid(calendarId, uid)
            ?: return Cl1CreateResult.Failed("atomicCreateFailed")
        return verifyCreatedEvent(existing, uid, value, existing = true)
    }

    private fun verifyCreatedEvent(
        event: Cl1EventSnapshot,
        uid: String,
        value: Cl1EventWrite,
        existing: Boolean,
    ): Cl1CreateResult {
        if (event.uid2445 != uid) {
            return Cl1CreateResult.Ineligible("createTokenNotPreserved", event)
        }
        val mismatch = mismatchField(event, value)
        if (mismatch != null) {
            return Cl1CreateResult.Ineligible(mismatch, event)
        }
        return if (existing) {
            Cl1CreateResult.Existing(event)
        } else {
            Cl1CreateResult.Created(event)
        }
    }

    private fun verifyMutation(
        event: Cl1EventSnapshot,
        value: Cl1EventWrite,
    ): Cl1MutationResult {
        val mismatch = mismatchField(event, value)
        return if (mismatch == null) {
            Cl1MutationResult.Applied(event)
        } else {
            Cl1MutationResult.Ineligible(mismatch)
        }
    }

    private fun mismatchField(
        event: Cl1EventSnapshot,
        value: Cl1EventWrite,
    ): String? {
        if (event.description != value.description) {
            return "description"
        }
        val actual = try {
            event.canonicalEvent()
        } catch (exception: Cl1CalendarIncompatibleException) {
            return exception.field
        } catch (exception: Cl1IncompatibleException) {
            return exception.field
        }
        if (
            !Cl1CanonicalEventCodec.encode(actual).contentEquals(
                Cl1CanonicalEventCodec.encode(value.canonicalEvent)
            )
        ) {
            return canonicalMismatchField(actual, value.canonicalEvent)
        }
        return null
    }

    private fun canonicalMismatchField(
        actual: org.fossify.clock.cl1.Cl1CanonicalEvent,
        expected: org.fossify.clock.cl1.Cl1CanonicalEvent,
    ): String {
        return when {
            actual.title != expected.title -> "title"
            actual.startUnixSeconds != expected.startUnixSeconds -> "start"
            actual.endUnixSeconds != expected.endUnixSeconds -> "end"
            actual.startIanaTimeZone != expected.startIanaTimeZone -> "startTimeZone"
            actual.endIanaTimeZone != expected.endIanaTimeZone -> "endTimeZone"
            actual.location != expected.location -> "location"
            actual.userDescription != expected.userDescription -> "description"
            actual.userUrl != expected.userUrl -> "userUrl"
            else -> "canonicalEvent"
        }
    }

    private fun classifyPreconditionFailure(ref: Cl1EventRef): Cl1MutationResult {
        return if (readEvent(ref) == null) {
            Cl1MutationResult.Missing
        } else {
            Cl1MutationResult.PreconditionFailed
        }
    }

    private fun writeIneligibility(
        calendar: Cl1CalendarDescriptor,
        value: Cl1EventWrite,
    ): String? {
        if (!calendar.supportsCompleteRelations) {
            return "calendarCapabilities"
        }
        if (!hasPermission(Manifest.permission.WRITE_CALENDAR)) {
            return "writePermission"
        }
        if (value.canonicalEvent.userUrl.isNotEmpty()) {
            return "userUrl"
        }
        if (value.canonicalEvent.startIanaTimeZone.isEmpty()) {
            return "startTimeZone"
        }
        return null
    }

    private fun findByCreateUid(
        calendarId: Long,
        uid: String,
    ): Cl1EventSnapshot? {
        return querySingleEvent(
            "${CalendarContract.Events.CALENDAR_ID} = ? AND " +
                "${CalendarContract.Events.UID_2445} = ?",
            arrayOf(calendarId.toString(), uid)
        )
    }

    private fun querySingleEvent(
        selection: String,
        arguments: Array<String>,
    ): Cl1EventSnapshot? {
        val cursor = resolver.query(
            CalendarContract.Events.CONTENT_URI,
            EVENT_PROJECTION,
            selection,
            arguments,
            null
        ) ?: return null
        return cursor.use {
            if (!cursor.moveToFirst()) {
                null
            } else {
                val event = cursor.readEvent()
                if (cursor.moveToNext()) null else event
            }
        }
    }

    private fun Cursor.readCalendar(): Cl1CalendarDescriptor {
        return createCalendarDescriptor(
            calendarId = getLong(column(CalendarContract.Calendars._ID)),
            displayName = string(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME),
            color = nullableInt(CalendarContract.Calendars.CALENDAR_COLOR),
            accountName = string(CalendarContract.Calendars.ACCOUNT_NAME),
            accountType = string(CalendarContract.Calendars.ACCOUNT_TYPE),
            visible = int(CalendarContract.Calendars.VISIBLE) == 1,
            accessLevel = int(CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL)
        )
    }

    private fun Cursor.readEvent(): Cl1EventSnapshot? {
        val calendarId = getLong(column(CalendarContract.Events.CALENDAR_ID))
        val deleted = nullableInt(CalendarContract.Events.DELETED) == 1
        if (deleted) {
            return null
        }
        val calendar = createCalendarDescriptor(
            calendarId = calendarId,
            displayName = string(CalendarContract.Events.CALENDAR_DISPLAY_NAME),
            color = nullableInt(CalendarContract.Events.DISPLAY_COLOR),
            accountName = string(CalendarContract.Events.ACCOUNT_NAME),
            accountType = string(CalendarContract.Events.ACCOUNT_TYPE),
            visible = int(CalendarContract.Events.VISIBLE) == 1,
            accessLevel = int(CalendarContract.Events.CALENDAR_ACCESS_LEVEL)
        )
        return Cl1EventSnapshot(
            ref = Cl1EventRef(
                eventId = getLong(column(CalendarContract.Events._ID)),
                calendarId = calendarId
            ),
            calendar = calendar,
            title = nullableString(CalendarContract.Events.TITLE),
            startMillis = getLong(column(CalendarContract.Events.DTSTART)),
            endMillis = nullableLong(CalendarContract.Events.DTEND),
            startTimeZone = nullableString(CalendarContract.Events.EVENT_TIMEZONE),
            endTimeZone = nullableString(CalendarContract.Events.EVENT_END_TIMEZONE),
            location = nullableString(CalendarContract.Events.EVENT_LOCATION),
            description = nullableString(CalendarContract.Events.DESCRIPTION).orEmpty(),
            userUrl = null,
            uid2445 = nullableString(CalendarContract.Events.UID_2445),
            allDay = int(CalendarContract.Events.ALL_DAY) == 1,
            recurrenceRule = nullableString(CalendarContract.Events.RRULE),
            recurrenceDate = nullableString(CalendarContract.Events.RDATE),
            exceptionRule = nullableString(CalendarContract.Events.EXRULE),
            exceptionDate = nullableString(CalendarContract.Events.EXDATE),
            originalEventId = nullableLong(CalendarContract.Events.ORIGINAL_ID),
            rawStatus = nullableInt(CalendarContract.Events.STATUS),
            recurring = hasRecurrence(),
            canceled = nullableInt(CalendarContract.Events.STATUS) ==
                CalendarContract.Events.STATUS_CANCELED,
            deleted = false
        )
    }

    private fun Cursor.hasRecurrence(): Boolean {
        return !nullableString(CalendarContract.Events.RRULE).isNullOrEmpty() ||
            !nullableString(CalendarContract.Events.RDATE).isNullOrEmpty() ||
            !nullableString(CalendarContract.Events.EXRULE).isNullOrEmpty() ||
            !nullableString(CalendarContract.Events.EXDATE).isNullOrEmpty() ||
            nullableLong(CalendarContract.Events.ORIGINAL_ID) != null
    }

    private fun createCalendarDescriptor(
        calendarId: Long,
        displayName: String,
        color: Int?,
        accountName: String,
        accountType: String,
        visible: Boolean,
        accessLevel: Int,
    ): Cl1CalendarDescriptor {
        val email = try {
            Cl1Email.canonicalize(accountName, AndroidCl1DomainToAscii)
        } catch (_: Cl1EmailException) {
            null
        }
        val canWrite = hasPermission(Manifest.permission.WRITE_CALENDAR) &&
            accessLevel >= CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR &&
            email != null
        val capabilities = buildSet {
            add(Cl1CalendarCapability.READ)
            if (canWrite) {
                add(Cl1CalendarCapability.WRITE)
                add(Cl1CalendarCapability.PRESERVE_DESCRIPTION)
                add(Cl1CalendarCapability.IDEMPOTENT_CREATE)
                add(Cl1CalendarCapability.CONDITIONAL_UPDATE)
                add(Cl1CalendarCapability.CONDITIONAL_DELETE)
            }
        }
        return Cl1CalendarDescriptor(
            ref = Cl1CalendarRef(calendarId),
            displayName = displayName,
            color = color,
            accountName = accountName,
            accountType = accountType,
            canonicalAccountEmail = email,
            visible = visible,
            accessLevel = accessLevel,
            capabilities = capabilities
        )
    }

    private fun Cl1EventWrite.toContentValues(
        calendarId: Long,
        uid: String?,
    ): ContentValues {
        if (canonicalEvent.userUrl.isNotEmpty()) {
            throw Cl1CalendarIncompatibleException("userUrl")
        }
        val startMillis = secondsToMillis(canonicalEvent.startUnixSeconds, "start")
        val endMillis = secondsToMillis(canonicalEvent.endUnixSeconds, "end")
        if (canonicalEvent.startIanaTimeZone.isEmpty()) {
            throw Cl1CalendarIncompatibleException("startTimeZone")
        }
        return ContentValues().apply {
            put(CalendarContract.Events.CALENDAR_ID, calendarId)
            put(CalendarContract.Events.TITLE, canonicalEvent.title)
            put(CalendarContract.Events.DTSTART, startMillis)
            put(CalendarContract.Events.DTEND, endMillis)
            put(CalendarContract.Events.EVENT_TIMEZONE, canonicalEvent.startIanaTimeZone)
            putNullable(
                CalendarContract.Events.EVENT_END_TIMEZONE,
                canonicalEvent.endIanaTimeZone
            )
            put(CalendarContract.Events.EVENT_LOCATION, canonicalEvent.location)
            put(CalendarContract.Events.DESCRIPTION, description)
            put(CalendarContract.Events.ALL_DAY, 0)
            uid?.let { put(CalendarContract.Events.UID_2445, it) }
        }
    }

    private fun secondsToMillis(value: Long, field: String): Long {
        return try {
            Math.multiplyExact(value, MILLIS_PER_SECOND)
        } catch (_: ArithmeticException) {
            throw Cl1CalendarIncompatibleException(field)
        }
    }

    private fun ContentValues.putNullable(key: String, value: String) {
        if (value.isEmpty()) {
            putNull(key)
        } else {
            put(key, value)
        }
    }

    private fun hasPermission(permission: String): Boolean {
        return ContextCompat.checkSelfPermission(appContext, permission) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED
    }

    private fun Cursor.column(name: String): Int = getColumnIndexOrThrow(name)

    private fun Cursor.string(name: String): String {
        val index = column(name)
        return if (isNull(index)) "" else getString(index)
    }

    private fun Cursor.nullableString(name: String): String? {
        val index = column(name)
        return if (isNull(index)) null else getString(index)
    }

    private fun Cursor.int(name: String): Int = getInt(column(name))

    private fun Cursor.nullableInt(name: String): Int? {
        val index = column(name)
        return if (isNull(index)) null else getInt(index)
    }

    private fun Cursor.nullableLong(name: String): Long? {
        val index = column(name)
        return if (isNull(index)) null else getLong(index)
    }

    private data class EventPrecondition(
        val selection: String,
        val arguments: Array<String>,
    ) {
        companion object {
            fun from(event: Cl1EventSnapshot): EventPrecondition {
                val builder = SelectionBuilder()
                builder.equal(CalendarContract.Events._ID, event.ref.eventId)
                builder.equal(CalendarContract.Events.CALENDAR_ID, event.ref.calendarId)
                builder.nullable(CalendarContract.Events.TITLE, event.title)
                builder.equal(CalendarContract.Events.DTSTART, event.startMillis)
                builder.nullable(CalendarContract.Events.DTEND, event.endMillis)
                builder.nullable(
                    CalendarContract.Events.EVENT_TIMEZONE,
                    event.startTimeZone
                )
                builder.nullable(
                    CalendarContract.Events.EVENT_END_TIMEZONE,
                    event.endTimeZone
                )
                builder.nullable(CalendarContract.Events.EVENT_LOCATION, event.location)
                builder.nullable(CalendarContract.Events.DESCRIPTION, event.description)
                builder.equal(CalendarContract.Events.ALL_DAY, if (event.allDay) 1 else 0)
                builder.nullable(CalendarContract.Events.UID_2445, event.uid2445)
                builder.nullable(
                    CalendarContract.Events.RRULE,
                    event.recurrenceRule
                )
                builder.nullable(
                    CalendarContract.Events.RDATE,
                    event.recurrenceDate
                )
                builder.nullable(
                    CalendarContract.Events.EXRULE,
                    event.exceptionRule
                )
                builder.nullable(
                    CalendarContract.Events.EXDATE,
                    event.exceptionDate
                )
                builder.nullable(
                    CalendarContract.Events.ORIGINAL_ID,
                    event.originalEventId
                )
                builder.nullable(CalendarContract.Events.STATUS, event.rawStatus?.toLong())
                builder.literal(
                    "(${CalendarContract.Events.DELETED} IS NULL OR " +
                        "${CalendarContract.Events.DELETED} = 0)"
                )
                return builder.build()
            }
        }
    }

    private class SelectionBuilder {
        private val clauses = ArrayList<String>()
        private val arguments = ArrayList<String>()

        fun equal(column: String, value: Long) {
            clauses.add("$column = ?")
            arguments.add(value.toString())
        }

        fun equal(column: String, value: Int) {
            clauses.add("$column = ?")
            arguments.add(value.toString())
        }

        fun nullable(column: String, value: String?) {
            if (value == null) {
                clauses.add("$column IS NULL")
            } else {
                clauses.add("$column = ?")
                arguments.add(value)
            }
        }

        fun nullable(column: String, value: Long?) {
            if (value == null) {
                clauses.add("$column IS NULL")
            } else {
                equal(column, value)
            }
        }

        fun literal(value: String) {
            clauses.add(value)
        }

        fun build(): EventPrecondition {
            return EventPrecondition(
                selection = clauses.joinToString(" AND "),
                arguments = arguments.toTypedArray()
            )
        }
    }

    private companion object {
        const val MILLIS_PER_SECOND = 1_000L
        const val UID_PREFIX = "cl1-"

        fun createUid(createToken: String): String = UID_PREFIX + createToken

        val CALENDAR_PROJECTION = arrayOf(
            CalendarContract.Calendars._ID,
            CalendarContract.Calendars.ACCOUNT_NAME,
            CalendarContract.Calendars.ACCOUNT_TYPE,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Calendars.CALENDAR_COLOR,
            CalendarContract.Calendars.VISIBLE,
            CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL
        )

        val EVENT_PROJECTION = arrayOf(
            CalendarContract.Events._ID,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Events.ACCOUNT_NAME,
            CalendarContract.Events.ACCOUNT_TYPE,
            CalendarContract.Events.CALENDAR_DISPLAY_NAME,
            CalendarContract.Events.DISPLAY_COLOR,
            CalendarContract.Events.VISIBLE,
            CalendarContract.Events.CALENDAR_ACCESS_LEVEL,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DTSTART,
            CalendarContract.Events.DTEND,
            CalendarContract.Events.EVENT_TIMEZONE,
            CalendarContract.Events.EVENT_END_TIMEZONE,
            CalendarContract.Events.EVENT_LOCATION,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.UID_2445,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.RRULE,
            CalendarContract.Events.RDATE,
            CalendarContract.Events.EXRULE,
            CalendarContract.Events.EXDATE,
            CalendarContract.Events.ORIGINAL_ID,
            CalendarContract.Events.STATUS,
            CalendarContract.Events.DELETED
        )
    }
}
