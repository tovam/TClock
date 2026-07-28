package org.fossify.clock.helpers

import org.fossify.clock.cl1.Cl1Armor
import org.fossify.clock.cl1.Cl1Description
import org.fossify.clock.cl1.provider.Cl1EventRef

/**
 * CL1 appends an armored machine block after the description visible to the user. Alarm markers
 * belong only to that user-controlled part.
 */
internal fun alarmPatternDescription(description: String): String {
    return when (val parsed = Cl1Armor.parse(description)) {
        is Cl1Description.None -> parsed.originalDescription
        is Cl1Description.Valid -> parsed.userDescription
        is Cl1Description.UnsupportedVersion -> parsed.userDescription
        is Cl1Description.Corrupt -> parsed.originalDescription
    }
}

internal val CalendarEventRecord.cl1EventRef: Cl1EventRef
    get() = Cl1EventRef(eventId = eventId, calendarId = calendarId)
