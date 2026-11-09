package com.cozyswitch.app.data

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.time.LocalTime
import java.time.ZonedDateTime
import java.util.UUID

/**
 * "At 07:30 on weekdays, turn ON the lamp and the heater."
 * [days] holds 1 (Monday) … 7 (Sunday); empty means "once", after which it switches itself off.
 */
data class Automation(
    val id: String = UUID.randomUUID().toString(),
    val name: String = "",
    val on: Boolean = true,
    val hour: Int = 7,
    val minute: Int = 0,
    val days: Set<Int> = (1..7).toSet(),
    val plugs: Set<String> = emptySet(),
    val enabled: Boolean = true,
) {
    val time: String get() = "%02d:%02d".format(hour, minute)

    /** The next moment this should run, strictly after [now]; null if it never will. */
    fun next(now: ZonedDateTime): ZonedDateTime? {
        if (!enabled || plugs.isEmpty()) return null
        var t = now.with(LocalTime.of(hour, minute)).withSecond(0).withNano(0)
        if (!t.isAfter(now)) t = t.plusDays(1)
        if (days.isEmpty()) return t
        repeat(7) {
            if (t.dayOfWeek.value in days) return t
            t = t.plusDays(1)
        }
        return null
    }

    fun daysText(): String = when {
        days.isEmpty() -> "Once"
        days.size == 7 -> "Every day"
        days == setOf(1, 2, 3, 4, 5) -> "Weekdays"
        days == setOf(6, 7) -> "Weekends"
        else -> days.sorted().joinToString(" ") { DAY_SHORT[it - 1] }
    }

    companion object {
        val DAY_SHORT = listOf("Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun")
    }
}

object Automations {
    private const val PREFS = "automations"
    private const val KEY = "list"

    val listeners = mutableListOf<() -> Unit>()

    @Synchronized
    fun load(ctx: Context): List<Automation> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, null) ?: return emptyList()
        return try {
            val a = JSONArray(raw)
            (0 until a.length()).map { i -> fromJson(a.getJSONObject(i)) }
        } catch (_: Exception) {
            emptyList()
        }
    }

    @Synchronized
    fun save(ctx: Context, list: List<Automation>) {
        val a = JSONArray()
        list.forEach { a.put(toJson(it)) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY, a.toString()).apply()
        listeners.forEach { it() }
    }

    fun get(ctx: Context, id: String) = load(ctx).firstOrNull { it.id == id }

    fun put(ctx: Context, a: Automation) {
        val l = load(ctx)
        save(ctx, if (l.any { it.id == a.id }) l.map { if (it.id == a.id) a else it } else l + a)
    }

    fun delete(ctx: Context, id: String) = save(ctx, load(ctx).filterNot { it.id == id })

    /** Drops a removed plug from every automation. */
    fun forgetPlug(ctx: Context, did: String) =
        save(ctx, load(ctx).map { it.copy(plugs = it.plugs - did) })

    fun toJson(a: Automation): JSONObject = JSONObject()
        .put("id", a.id).put("name", a.name).put("on", a.on)
        .put("hour", a.hour).put("minute", a.minute)
        .put("days", JSONArray(a.days.sorted()))
        .put("plugs", JSONArray(a.plugs.toList()))
        .put("enabled", a.enabled)

    fun fromJson(o: JSONObject): Automation {
        val days = o.optJSONArray("days")
        val plugs = o.optJSONArray("plugs")
        return Automation(
            id = o.getString("id"),
            name = o.optString("name"),
            on = o.optBoolean("on", true),
            hour = o.optInt("hour", 7),
            minute = o.optInt("minute", 0),
            days = if (days == null) emptySet() else (0 until days.length()).map { days.getInt(it) }.toSet(),
            plugs = if (plugs == null) emptySet() else (0 until plugs.length()).map { plugs.getString(it) }.toSet(),
            enabled = o.optBoolean("enabled", true),
        )
    }
}
