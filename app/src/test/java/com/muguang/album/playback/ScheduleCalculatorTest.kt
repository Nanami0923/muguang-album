package com.muguang.album.playback

import com.muguang.album.core.PlaySchedule
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class ScheduleCalculatorTest {
    private val shanghai = ZoneId.of("Asia/Shanghai")
    private fun rule(start: Int = 8 * 60, end: Int = 22 * 60, days: Int = 127, id: String = "a") =
        PlaySchedule(id, "家庭", "album", start, end, days)

    @Test fun startsInclusiveAndEndsExclusive() {
        val schedule = rule()
        assertNotNull(ScheduleCalculator.current(listOf(schedule), Instant.parse("2026-11-09T00:00:00Z"), shanghai))
        assertNull(ScheduleCalculator.current(listOf(schedule), Instant.parse("2026-11-09T14:00:00Z"), shanghai))
    }

    @Test fun earlyMorningBelongsToPreviousStartDay() {
        val fridayNight = rule(22 * 60, 7 * 60, 1 shl 4)
        val occurrence = ScheduleCalculator.current(listOf(fridayNight), Instant.parse("2026-11-13T17:00:00Z"), shanghai)
        assertNotNull(occurrence)
        assertEquals(Instant.parse("2026-11-13T14:00:00Z"), occurrence!!.start)
        assertEquals(Instant.parse("2026-11-13T23:00:00Z"), occurrence.end)
        assertNull(ScheduleCalculator.current(listOf(fridayNight), occurrence.end, shanghai))
    }

    @Test fun sundayOvernightConflictsWithMondayMorning() {
        val sunday = rule(22 * 60, 7 * 60, 1 shl 6)
        val monday = rule(6 * 60, 9 * 60, 1, "b")
        assertEquals(sunday, ScheduleCalculator.conflict(monday, listOf(sunday)))
        assertNull(ScheduleCalculator.conflict(monday.copy(startMinute = 7 * 60), listOf(sunday)))
    }

    @Test fun disabledRulesDoNotRunOrConflictAndEditingSameIdIsAllowed() {
        val schedule = rule()
        assertNull(ScheduleCalculator.current(listOf(schedule.copy(enabled = false)), Instant.parse("2026-11-09T01:00:00Z"), shanghai))
        assertNull(ScheduleCalculator.conflict(schedule, listOf(schedule)))
        assertNull(ScheduleCalculator.conflict(schedule.copy(id = "b", enabled = false), listOf(schedule)))
    }

    @Test fun nextBoundaryUsesActiveEndOrFutureStart() {
        val schedule = rule()
        assertEquals(Instant.parse("2026-11-09T14:00:00Z"), ScheduleCalculator.nextBoundary(listOf(schedule), Instant.parse("2026-11-09T03:00:00Z"), shanghai))
        assertEquals(Instant.parse("2026-11-10T00:00:00Z"), ScheduleCalculator.nextBoundary(listOf(schedule), Instant.parse("2026-11-09T15:00:00Z"), shanghai))
    }

    @Test fun invalidWindowsAndEmptyWeekAreRejected() {
        assertNotNull(ScheduleCalculator.validate(rule(480, 480)))
        assertNotNull(ScheduleCalculator.validate(rule(days = 0)))
        assertNotNull(ScheduleCalculator.validate(rule(start = 1440)))
        assertNull(ScheduleCalculator.validate(rule()))
    }

    @Test fun dstGapMovesToFirstValidTime() {
        val zone = ZoneId.of("America/New_York")
        val result = ScheduleCalculator.upcoming(rule(150, 240, 64), Instant.parse("2024-03-10T00:00:00Z"), zone, 1).single()
        assertEquals(Instant.parse("2024-03-10T07:00:00Z"), result.start)
        assertEquals(Instant.parse("2024-03-10T08:00:00Z"), result.end)
    }

    @Test fun dstRepeatedHourSelectsEarlyStartAndLateEnd() {
        val zone = ZoneId.of("America/New_York")
        val result = ScheduleCalculator.upcoming(rule(90, 105, 64), Instant.parse("2024-11-03T00:00:00Z"), zone, 1).single()
        assertEquals(Instant.parse("2024-11-03T05:30:00Z"), result.start)
        assertEquals(Instant.parse("2024-11-03T06:45:00Z"), result.end)
        assertEquals(result.key, ScheduleCalculator.current(listOf(rule(90, 105, 64)), Instant.parse("2024-11-03T06:35:00Z"), zone)!!.key)
    }

    @Test fun gapThatCollapsesWindowIsSkipped() {
        val zone = ZoneId.of("America/New_York")
        val result = ScheduleCalculator.upcoming(rule(130, 170, 64), Instant.parse("2024-03-10T00:00:00Z"), zone, 1).single()
        assertEquals(Instant.parse("2024-03-17T06:10:00Z"), result.start)
    }

    @Test fun changingZoneRecomputesAbsoluteBoundary() {
        val schedule = rule()
        val now = Instant.parse("2026-11-09T00:00:00Z")
        assertEquals(Instant.parse("2026-11-09T08:00:00Z"), ScheduleCalculator.upcoming(schedule, now, ZoneId.of("UTC"), 1).single().start)
        assertEquals(now, ScheduleCalculator.upcoming(schedule, now, shanghai, 1).single().start)
    }
}
