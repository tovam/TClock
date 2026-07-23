package org.fossify.clock.helpers

import android.annotation.SuppressLint
import android.content.Context
import android.provider.CalendarContract
import android.util.Log
import org.fossify.clock.R
import org.fossify.clock.extensions.dbHelper

internal object CalendarDiagnosticsRepository {
    @Suppress("TooGenericExceptionCaught")
    fun load(
        context: Context,
        capturedAtMillis: Long = System.currentTimeMillis(),
    ): CalendarDiagnosticsSnapshot {
        val window = CalendarAlarmWindow.rangeAt(capturedAtMillis)
        val alarms = context.dbHelper.getCalendarAlarms()
        if (!CalendarAlarmSync.hasCalendarPermission(context)) {
            return CalendarDiagnosticsBuilder.build(
                capturedAtMillis = capturedAtMillis,
                window = window,
                providerState = CalendarDiagnosticsProviderState.PERMISSION_MISSING,
                records = emptyList(),
                alarms = alarms,
                untitledEventLabel = context.getString(R.string.calendar_untitled_event)
            )
        }

        val records = try {
            readVisibleInstances(
                context = context,
                queryBeginMillis = window.queryBeginMillis,
                queryEndMillis = window.queryEndMillis
            )
        } catch (exception: SecurityException) {
            Log.e(TAG, "Calendar permission was lost while loading diagnostics", exception)
            val state = if (CalendarAlarmSync.hasCalendarPermission(context)) {
                CalendarDiagnosticsProviderState.PROVIDER_ERROR
            } else {
                CalendarDiagnosticsProviderState.PERMISSION_MISSING
            }
            return CalendarDiagnosticsBuilder.build(
                capturedAtMillis = capturedAtMillis,
                window = window,
                providerState = state,
                records = emptyList(),
                alarms = alarms,
                untitledEventLabel = context.getString(R.string.calendar_untitled_event)
            )
        } catch (exception: Exception) {
            Log.e(TAG, "Calendar diagnostics query failed", exception)
            return CalendarDiagnosticsBuilder.build(
                capturedAtMillis = capturedAtMillis,
                window = window,
                providerState = CalendarDiagnosticsProviderState.PROVIDER_ERROR,
                records = emptyList(),
                alarms = alarms,
                untitledEventLabel = context.getString(R.string.calendar_untitled_event)
            )
        }

        return CalendarDiagnosticsBuilder.build(
            capturedAtMillis = capturedAtMillis,
            window = window,
            providerState = CalendarDiagnosticsProviderState.AVAILABLE,
            records = records,
            alarms = alarms,
            untitledEventLabel = context.getString(R.string.calendar_untitled_event)
        )
    }

    @SuppressLint("MissingPermission")
    fun readVisibleInstances(
        context: Context,
        queryBeginMillis: Long,
        queryEndMillis: Long,
    ): List<CalendarEventRecord> {
        val projection = arrayOf(
            CalendarContract.Instances.EVENT_ID,
            CalendarContract.Instances.BEGIN,
            CalendarContract.Instances.END,
            CalendarContract.Events.CALENDAR_ID,
            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
            CalendarContract.Events.DISPLAY_COLOR,
            CalendarContract.Events.TITLE,
            CalendarContract.Events.DESCRIPTION,
            CalendarContract.Events.ALL_DAY,
            CalendarContract.Events.STATUS
        )
        val cursor = CalendarContract.Instances.query(
            context.contentResolver,
            projection,
            queryBeginMillis,
            queryEndMillis
        ) ?: throw IllegalStateException("Calendar provider returned no cursor")

        return cursor.use {
            val eventIdIndex = cursor.getColumnIndexOrThrow(
                CalendarContract.Instances.EVENT_ID
            )
            val beginIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.BEGIN)
            val endIndex = cursor.getColumnIndexOrThrow(CalendarContract.Instances.END)
            val calendarIdIndex = cursor.getColumnIndexOrThrow(
                CalendarContract.Events.CALENDAR_ID
            )
            val calendarNameIndex = cursor.getColumnIndexOrThrow(
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME
            )
            val displayColorIndex = cursor.getColumnIndexOrThrow(
                CalendarContract.Events.DISPLAY_COLOR
            )
            val titleIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.TITLE)
            val descriptionIndex = cursor.getColumnIndexOrThrow(
                CalendarContract.Events.DESCRIPTION
            )
            val allDayIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.ALL_DAY)
            val statusIndex = cursor.getColumnIndexOrThrow(CalendarContract.Events.STATUS)

            buildList {
                while (cursor.moveToNext()) {
                    add(
                        CalendarEventRecord(
                            eventId = cursor.getLong(eventIdIndex),
                            calendarId = cursor.getLong(calendarIdIndex),
                            calendarDisplayName = cursor.getNullableString(calendarNameIndex),
                            displayColor = if (cursor.isNull(displayColorIndex)) {
                                null
                            } else {
                                cursor.getInt(displayColorIndex)
                            },
                            title = if (cursor.isNull(titleIndex)) {
                                null
                            } else {
                                cursor.getString(titleIndex)
                            },
                            description = cursor.getNullableString(descriptionIndex),
                            beginMillis = cursor.getLong(beginIndex),
                            endMillis = cursor.getLong(endIndex),
                            isAllDay = cursor.getInt(allDayIndex) == 1,
                            isCanceled = !cursor.isNull(statusIndex) &&
                                cursor.getInt(statusIndex) ==
                                CalendarContract.Events.STATUS_CANCELED
                        )
                    )
                }
            }
        }
    }

    private fun android.database.Cursor.getNullableString(columnIndex: Int): String {
        return if (isNull(columnIndex)) "" else getString(columnIndex)
    }

    private const val TAG = "CalendarDiagnostics"
}
