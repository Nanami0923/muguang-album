package com.muguang.album.playback

import com.muguang.album.core.PlaySchedule
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.ZoneId

data class Occurrence(
    val scheduleId: String,
    val revision: Int,
    val albumId: String,
    val start: Instant,
    val end: Instant,
) {
    val key: String get() = "$scheduleId:$revision:${start.toEpochMilli()}"
    fun contains(instant: Instant): Boolean = !instant.isBefore(start) && instant.isBefore(end)
}

/** Calendar calculations have no Android dependency. Monday occupies bit zero. */
object ScheduleCalculator {
    fun validate(schedule: PlaySchedule): String? = when {
        schedule.albumId.isBlank() -> "请选择要播放的相册"
        schedule.startMinute !in 0..1439 || schedule.endMinute !in 0..1439 -> "请输入有效时间"
        schedule.startMinute == schedule.endMinute -> "开始与结束时间不能相同"
        schedule.daysMask !in 1..127 -> "请至少选择一个重复星期"
        else -> null
    }

    fun current(
        schedules: List<PlaySchedule>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Occurrence? {
        val today = now.atZone(zone).toLocalDate()
        return schedules.asSequence().filter { it.enabled && validate(it) == null }
            .flatMap { schedule ->
                sequenceOf(today.minusDays(1), today).mapNotNull { occurrenceOn(schedule, it, zone) }
            }
            .filter { it.contains(now) }
            .sortedWith(compareBy<Occurrence> { it.start }.thenBy { it.scheduleId })
            .firstOrNull()
    }

    fun nextBoundary(
        schedules: List<PlaySchedule>,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
    ): Instant? {
        val today = now.atZone(zone).toLocalDate()
        return schedules.asSequence().filter { it.enabled && validate(it) == null }
            .flatMap { schedule ->
                (-1L..8L).asSequence().mapNotNull { occurrenceOn(schedule, today.plusDays(it), zone) }
            }
            .flatMap { sequenceOf(it.start, it.end) }
            .filter { it.isAfter(now) }
            .minOrNull()
    }

    /** Preview future starts, even when editing a disabled rule. */
    fun upcoming(
        schedule: PlaySchedule,
        now: Instant = Instant.now(),
        zone: ZoneId = ZoneId.systemDefault(),
        count: Int = 3,
    ): List<Occurrence> {
        if (validate(schedule) != null || count <= 0) return emptyList()
        val today = now.atZone(zone).toLocalDate()
        val days = count.toLong().coerceAtMost(100) * 7 + 8
        return (0L..days).asSequence()
            .mapNotNull { occurrenceOn(schedule, today.plusDays(it), zone) }
            .filter { !it.start.isBefore(now) }
            .take(count.coerceAtMost(100))
            .toList()
    }

    /** Reject weekly overlap, including Sunday crossing back into Monday. */
    fun conflict(candidate: PlaySchedule, existing: List<PlaySchedule>): PlaySchedule? {
        if (!candidate.enabled || validate(candidate) != null) return null
        val candidateWindows = weeklyWindows(candidate)
        return existing.firstOrNull { other ->
            other.id != candidate.id && other.enabled && validate(other) == null &&
                weeklyWindows(other).any { b ->
                    candidateWindows.any { a -> a.first < b.second && b.first < a.second }
                }
        }
    }

    private fun weeklyWindows(schedule: PlaySchedule): List<Pair<Int, Int>> = buildList {
        for (day in 0..6) {
            if (schedule.daysMask and (1 shl day) == 0) continue
            val start = day * 1440 + schedule.startMinute
            val end = day * 1440 + schedule.endMinute +
                if (schedule.endMinute < schedule.startMinute) 1440 else 0
            if (end <= 10080) add(start to end)
            else {
                add(start to 10080)
                add(0 to end - 10080)
            }
        }
    }

    private fun occurrenceOn(schedule: PlaySchedule, date: LocalDate, zone: ZoneId): Occurrence? {
        if (schedule.daysMask and (1 shl (date.dayOfWeek.value - 1)) == 0) return null
        val endDate = if (schedule.endMinute < schedule.startMinute) date.plusDays(1) else date
        val start = resolve(date, schedule.startMinute, zone, endBoundary = false)
        val end = resolve(endDate, schedule.endMinute, zone, endBoundary = true)
        if (!end.isAfter(start)) return null
        return Occurrence(schedule.id, schedule.revision, schedule.albumId, start, end)
    }

    private fun resolve(date: LocalDate, minute: Int, zone: ZoneId, endBoundary: Boolean): Instant {
        val local = LocalDateTime.of(date, LocalTime.of(minute / 60, minute % 60))
        val offsets = zone.rules.getValidOffsets(local)
        return when (offsets.size) {
            0 -> zone.rules.getTransition(local)!!.dateTimeAfter.atZone(zone).toInstant()
            1 -> local.toInstant(offsets.first())
            else -> local.toInstant(if (endBoundary) offsets.last() else offsets.first())
        }
    }
}
