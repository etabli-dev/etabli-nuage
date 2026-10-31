package com.raban.etabli.probe.engine

import org.junit.Assert.*
import org.junit.Test

class UsageProfileTest {
    @Test fun countsEveryOverlappedMinuteAndIncludesObservedInactiveDates() {
        val profile = UsageProfileParser.parse("2026-10-28 08:00:59 com.example OPEN\n2026-10-28 08:01:01 com.example CLOSE\n2026-10-29 09:00:00 com.example CLOSE")
        assertEquals(2, profile.totalDays)
        assertEquals(0.5, profile.perMinute[480], 0.0)
        assertEquals(0.5, profile.perMinute[481], 0.0)
        assertEquals(0.0, profile.perMinute[482], 0.0)
        assertEquals(0.0, profile.probability(480, java.util.Calendar.SUNDAY, MaskMode.Worktime), 0.0)
        assertEquals(0.5, profile.probability(480, java.util.Calendar.SUNDAY, MaskMode.OffHours), 0.0)
    }
    @Test fun rejectsImpossibleDatesAndUnboundedIntervals() {
        val invalid = UsageProfileParser.parse("2026-02-30 08:00:00 com.example OPEN")
        assertEquals(0, invalid.totalRecords)
        assertEquals(1, invalid.skippedLines)
        assertThrows(IllegalArgumentException::class.java) {
            UsageProfileParser.parse("2020-01-01 08:00:00 com.example OPEN\n2026-01-01 08:00:00 com.example CLOSE")
        }
        assertThrows(IllegalArgumentException::class.java) { UsageProfile(perMinute = doubleArrayOf(1.0)) }
        assertThrows(IllegalArgumentException::class.java) { UsageProfile(perMinute = DoubleArray(1440) { Double.NaN }) }
        val zero = UsageProfileParser.parse("2026-10-28 08:00:59 com.example OPEN\n2026-10-28 08:00:59 com.example CLOSE")
        assertTrue(zero.perMinute.all { it == 0.0 })
    }
}
