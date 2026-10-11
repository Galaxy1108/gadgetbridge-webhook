package nodomain.freeyourgadget.gadgetbridge.widgets

import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * A value at an epoch second.
 */
data class TimedValue(val seconds: Long, val value: Float)

/**
 * Buckets samples into the series the tiles' mini charts draw.
 */
object WidgetHistory {
    const val HOURS_PER_DAY = 24
    private const val SECONDS_PER_HOUR = 3600L
    private const val DAYS_PER_WEEK = 7

    /**
     * The average per hour of the day starting at [dayStart] (epoch seconds), NaN for hours without samples.
     */
    fun hourlyAverages(samples: List<TimedValue>, dayStart: Long, hours: Int = HOURS_PER_DAY): FloatArray =
        bucketAverages(samples, dayStart, SECONDS_PER_HOUR, hours)

    /**
     * The average per [bucketSeconds] long bucket from [start] (epoch seconds), NaN for buckets without samples.
     */
    fun bucketAverages(samples: List<TimedValue>, start: Long, bucketSeconds: Long, buckets: Int): FloatArray {
        val sums = FloatArray(buckets)
        val counts = IntArray(buckets)
        for (sample in samples) {
            if (sample.seconds < start) continue
            val bucket = ((sample.seconds - start) / bucketSeconds).toInt()
            if (bucket < buckets) {
                sums[bucket] += sample.value
                counts[bucket]++
            }
        }
        return FloatArray(buckets) { if (counts[it] > 0) sums[it] / counts[it] else Float.NaN }
    }

    /**
     * Minutes per hour of the day starting at [dayStart] covered by [intervals] of epoch seconds.
     */
    fun minutesPerHour(intervals: List<Pair<Long, Long>>, dayStart: Long, hours: Int = HOURS_PER_DAY): FloatArray {
        val minutes = FloatArray(hours)
        for ((from, to) in intervals) {
            var start = maxOf(from, dayStart)
            val end = minOf(to, dayStart + hours * SECONDS_PER_HOUR)
            while (start < end) {
                val hour = ((start - dayStart) / SECONDS_PER_HOUR).toInt()
                val hourEnd = minOf(end, dayStart + (hour + 1) * SECONDS_PER_HOUR)
                minutes[hour] += (hourEnd - start) / 60f
                start = hourEnd
            }
        }
        return minutes
    }

    /**
     * Joins [samples] of ([epoch second], lane), each [sampleSeconds] long, into stretches of one lane. Samples
     * further than [maxGapSeconds] apart start a new stretch.
     */
    fun laneStretches(samples: List<Pair<Long, Int>>, sampleSeconds: Long, maxGapSeconds: Long): List<HypnogramSegment> {
        val result = mutableListOf<HypnogramSegment>()
        var current: HypnogramSegment? = null
        for ((time, lane) in samples.sortedBy { it.first }) {
            val open = current
            current = if (open != null && open.lane == lane && time - open.end <= maxGapSeconds) {
                open.copy(end = time + sampleSeconds)
            } else {
                open?.let { result += it }
                HypnogramSegment(time, time + sampleSeconds, lane)
            }
        }
        current?.let { result += it }
        return result
    }

    /**
     * The number of hours of the day starting at [dayStart] that have begun by [now], at most [hours].
     */
    fun hoursStarted(dayStart: Long, now: Long, hours: Int = HOURS_PER_DAY): Int =
        (((now - dayStart) / SECONDS_PER_HOUR).toInt() + 1).coerceIn(0, hours)

    /**
     * The latest value of each of the [days] days ending on [lastDay], oldest first, NaN for days without samples.
     */
    fun dailyLatest(samples: List<TimedValue>, lastDay: LocalDate, days: Int, zone: ZoneId): FloatArray =
        latestPerBucket(samples, lastDay, days, 1, zone)

    /**
     * The latest value of each of the [weeks] seven-day spans ending on [lastDay], oldest first, NaN for weeks
     * without samples.
     */
    fun weeklyLatest(samples: List<TimedValue>, lastDay: LocalDate, weeks: Int, zone: ZoneId): FloatArray =
        latestPerBucket(samples, lastDay, weeks, DAYS_PER_WEEK, zone)

    private fun latestPerBucket(samples: List<TimedValue>, lastDay: LocalDate, buckets: Int, daysPerBucket: Int, zone: ZoneId): FloatArray {
        val values = FloatArray(buckets) { Float.NaN }
        val times = LongArray(buckets) { Long.MIN_VALUE }
        for (sample in samples) {
            val day = Instant.ofEpochSecond(sample.seconds).atZone(zone).toLocalDate()
            val daysBack = ChronoUnit.DAYS.between(day, lastDay)
            if (daysBack < 0) continue
            val bucket = buckets - 1 - (daysBack / daysPerBucket).toInt()
            if (bucket < 0) continue
            if (sample.seconds >= times[bucket]) {
                times[bucket] = sample.seconds
                values[bucket] = sample.value
            }
        }
        return values
    }
}
