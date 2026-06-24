// Copyright 2026 R. Heller
// SPDX-License-Identifier: Apache-2.0

package com.raban.etabli.probe.engine

import android.content.Context
import java.io.File
import java.util.Calendar
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.text.SimpleDateFormat
import org.json.JSONArray
import org.json.JSONObject

// Smartphone-use → per-minute-of-day probability profile.
//
// Mirrors the iOS UsageProfile.swift in EtabliProbe exactly: tolerant TXT
// log parser, OPEN/CLOSE pairing, per-(date, minute-of-day) activity
// counting, profile = (days active at minute m) / total distinct days.
//
// The output is a 1440-element Double array of probabilities in [0,1].

enum class MaskMode(val label: String) {
    None("off"),
    Worktime("worktime only"),
    OffHours("off-hours only");
    companion object {
        fun fromRaw(s: String?): MaskMode = when (s) {
            "worktime" -> Worktime
            "offhours" -> OffHours
            else -> None
        }
    }
    fun toRaw(): String = when (this) {
        None -> "none"; Worktime -> "worktime"; OffHours -> "offhours"
    }
}

data class UsageProfile(
    val perMinute: DoubleArray = DoubleArray(1440),
    val totalRecords: Int = 0,
    val totalDays: Int = 0,
    val skippedLines: Int = 0,
    val sourceName: String? = null,
) {
    val isEmpty: Boolean
        get() = totalDays == 0 || perMinute.all { it == 0.0 }

    val peakMinute: Int
        get() = (0 until 1440).maxByOrNull { perMinute[it] } ?: 0

    val mean: Double
        get() = perMinute.average()

    /** Probability at minute-of-day, with the supplied mask applied. */
    fun probability(minuteOfDay: Int, weekday: Int, mask: MaskMode): Double {
        val m = ((minuteOfDay % 1440) + 1440) % 1440
        val base = perMinute[m]
        return when (mask) {
            MaskMode.None     -> base
            MaskMode.Worktime -> if (isWorktime(m, weekday)) base else 0.0
            MaskMode.OffHours -> if (isWorktime(m, weekday)) 0.0 else base
        }
    }

    fun toJson(): String {
        val arr = JSONArray()
        for (v in perMinute) arr.put(v)
        return JSONObject().apply {
            put("perMinute", arr)
            put("totalRecords", totalRecords)
            put("totalDays", totalDays)
            put("skippedLines", skippedLines)
            put("sourceName", sourceName ?: JSONObject.NULL)
        }.toString()
    }

    companion object {
        /** Calendar weekday: SUNDAY=1..SATURDAY=7, same as Calendar.DAY_OF_WEEK. */
        fun isWorktime(minuteOfDay: Int, weekday: Int): Boolean {
            val isWeekday = weekday in Calendar.MONDAY..Calendar.FRIDAY
            val hour = minuteOfDay / 60
            return isWeekday && hour in 8..17
        }
        fun hhmm(minute: Int): String = "%02d:%02d".format(minute / 60, minute % 60)

        fun fromJson(json: String): UsageProfile {
            val obj = JSONObject(json)
            val arr = obj.getJSONArray("perMinute")
            val pm = DoubleArray(1440)
            val n = minOf(1440, arr.length())
            for (i in 0 until n) pm[i] = arr.getDouble(i)
            return UsageProfile(
                perMinute = pm,
                totalRecords = obj.optInt("totalRecords"),
                totalDays = obj.optInt("totalDays"),
                skippedLines = obj.optInt("skippedLines"),
                sourceName = obj.optString("sourceName").takeIf { it != "null" && it.isNotEmpty() },
            )
        }
    }
}

// ── Parser ─────────────────────────────────────────────────────────────

object UsageProfileParser {

    /// Parse a TXT log and aggregate to a UsageProfile.
    fun parse(text: String, sourceName: String? = null): UsageProfile {
        data class Rec(val date: Long, val key: String, val event: Event)
        val records = mutableListOf<Rec>()
        var skipped = 0
        for (rawLine in text.split('\n', '\r')) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("#")) continue
            val parsed = parseLine(line)
            if (parsed == null) { skipped++; continue }
            records += Rec(parsed.first, parsed.second, parsed.third)
        }
        records.sortBy { it.date }

        // Pair OPEN/CLOSE → intervals.
        val openMap = mutableMapOf<String, Long>()
        val sessions = mutableListOf<Pair<Long, Long>>()
        for (r in records) {
            when (r.event) {
                Event.Open -> openMap[r.key] = r.date
                Event.Close -> {
                    val opened = openMap.remove(r.key)
                    if (opened != null && opened <= r.date) sessions += opened to r.date
                }
            }
        }
        // Lonely OPENs → 1-minute sessions.
        for ((_, opened) in openMap) sessions += opened to (opened + 60_000)

        // Build per-day per-minute activity set.
        val cal = Calendar.getInstance()
        val perDay = mutableMapOf<Long, HashSet<Int>>()
        val days = mutableSetOf<Long>()
        for ((start, end) in sessions) {
            var cur = start
            while (cur < end) {
                cal.timeInMillis = cur
                val day = cal.startOfDayMillis()
                val minute = cal.get(Calendar.HOUR_OF_DAY) * 60 + cal.get(Calendar.MINUTE)
                perDay.getOrPut(day) { HashSet() }.add(minute)
                days += day
                cur += 60_000
            }
        }

        val totalDays = days.size
        val profile = DoubleArray(1440)
        if (totalDays > 0) {
            for ((_, mins) in perDay) for (m in mins) if (m in 0..1439) profile[m] += 1.0
            for (i in 0 until 1440) profile[i] /= totalDays.toDouble()
        }
        return UsageProfile(
            perMinute = profile,
            totalRecords = records.size,
            totalDays = totalDays,
            skippedLines = skipped,
            sourceName = sourceName,
        )
    }

    enum class Event { Open, Close }

    private val openWord  = Regex("(^|\\W)(open|opened|launch|launched|start|started|foreground|fg|resume)(\\W|$)", RegexOption.IGNORE_CASE)
    private val closeWord = Regex("(^|\\W)(close|closed|stop|stopped|background|bg|pause|exit)(\\W|$)", RegexOption.IGNORE_CASE)
    private val keyRe     = Regex("[A-Za-z_][A-Za-z0-9_]*(\\.[A-Za-z_][A-Za-z0-9_]*)+")
    private val isoRe     = Regex("\\d{4}-\\d{2}-\\d{2}[T ]\\d{2}:\\d{2}(:\\d{2}(\\.\\d+)?)?(Z|[+-]\\d{2}:?\\d{2})?")
    private val euRe      = Regex("\\d{2}\\.\\d{2}\\.\\d{4} \\d{2}:\\d{2}(:\\d{2})?")
    private val slashRe   = Regex("\\d{2}/\\d{2}/\\d{4} \\d{1,2}:\\d{2}(:\\d{2})?")
    private val ms13      = Regex("(?<![0-9])([0-9]{13})(?![0-9])")
    private val sec10     = Regex("(?<![0-9])([0-9]{10})(?![0-9])")

    private val isoFormatters: List<SimpleDateFormat> by lazy {
        listOf(
            "yyyy-MM-dd'T'HH:mm:ssXXX",
            "yyyy-MM-dd'T'HH:mm:ss.SSSXXX",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'",
            "yyyy-MM-dd'T'HH:mm:ss",
            "yyyy-MM-dd HH:mm:ss",
            "yyyy-MM-dd HH:mm",
            "dd.MM.yyyy HH:mm:ss",
            "dd.MM.yyyy HH:mm",
            "dd/MM/yyyy HH:mm:ss",
            "dd/MM/yyyy HH:mm",
            "MM/dd/yyyy HH:mm:ss",
            "MM/dd/yyyy HH:mm",
        ).map { fmt ->
            SimpleDateFormat(fmt, Locale.US).apply {
                timeZone = if (fmt.contains("X") || fmt.contains("'Z'"))
                    TimeZone.getTimeZone("UTC")
                else TimeZone.getDefault()
                isLenient = false
            }
        }
    }

    fun parseLine(s: String): Triple<Long, String, Event>? {
        val event = when {
            openWord.containsMatchIn(s)  -> Event.Open
            closeWord.containsMatchIn(s) -> Event.Close
            else -> return null
        }
        val date = scanTimestamp(s) ?: return null
        val key = keyRe.find(s)?.value ?: "_any"
        return Triple(date, key, event)
    }

    private fun scanTimestamp(s: String): Long? {
        val candidates = mutableListOf<String>()
        isoRe.findAll(s).forEach { candidates += it.value }
        euRe.findAll(s).forEach { candidates += it.value }
        slashRe.findAll(s).forEach { candidates += it.value }
        for (c in candidates) {
            for (fmt in isoFormatters) {
                try {
                    val d = fmt.parse(c) ?: continue
                    return d.time
                } catch (_: Throwable) { /* try next */ }
            }
        }
        ms13.find(s)?.value?.toLongOrNull()?.let { return it }
        sec10.find(s)?.value?.toLongOrNull()?.let { return it * 1000 }
        return null
    }
}

private fun Calendar.startOfDayMillis(): Long {
    val c = (clone() as Calendar)
    c.set(Calendar.HOUR_OF_DAY, 0)
    c.set(Calendar.MINUTE, 0)
    c.set(Calendar.SECOND, 0)
    c.set(Calendar.MILLISECOND, 0)
    return c.timeInMillis
}

// ── Persistence ────────────────────────────────────────────────────────

class UsageProfileStore(context: Context) {
    private val file = File(context.filesDir, "probe_profile.json")
    fun save(p: UsageProfile) { file.writeText(p.toJson(), Charsets.UTF_8) }
    fun load(): UsageProfile? = runCatching {
        if (!file.exists()) null else UsageProfile.fromJson(file.readText(Charsets.UTF_8))
    }.getOrNull()
    fun clear() { if (file.exists()) file.delete() }
}
