package com.cozyswitch.app.data

import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URL
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.UUID

/** A plug as the CozyLife account knows it: what's needed to reach it through the cloud. */
data class CloudPlug(
    val did: String,
    val key: String,
    val name: String,
    val relayHost: String,
    val relayPort: Int,
    val localIp: String?,
    val online: Boolean,
)

class CloudError(msg: String) : Exception(msg)

/**
 * The CozyLife account API: sign in once, read the device list. Field names and the odd
 * required values come from the reverse-engineered CozyLife Android app (see the
 * lacherogwu/homeassistant-cozylife project, which documents each one).
 */
class CozyAccount(private val base: String = "https://api-us.doiting.com") {
    companion object {
        const val USER_AGENT = "CozyLife/1.20.24 okhttp/4.9.0" // the API's bot filter rejects default agents

        fun parseDevices(body: String): List<CloudPlug> {
            val j = JSONObject(body)
            checkRet(j, "device list")
            val info = j.optJSONObject("info") ?: return emptyList()
            val out = mutableListOf<CloudPlug>()
            for (section in listOf("device_bind", "device_share")) {
                val list = info.optJSONObject(section)?.optJSONArray("list") ?: continue
                for (i in 0 until list.length()) {
                    val d = list.optJSONObject(i) ?: continue
                    val did = d.optString("device_id")
                    val key = d.optString("device_key")
                    if (did.isEmpty() || key.isEmpty()) continue
                    out += CloudPlug(
                        did = did,
                        key = key,
                        name = d.optString("device_name").ifEmpty { did },
                        relayHost = d.optString("ip"),
                        relayPort = d.optInt("port", 0),
                        localIp = d.optString("local_ip").ifEmpty { null },
                        online = d.optInt("is_online", 1) != 0,
                    )
                }
            }
            return out
        }

        fun parseStates(body: String): Map<String, Boolean?> {
            val j = JSONObject(body)
            checkRet(j, "status")
            val info = j.optJSONArray("info") ?: return emptyMap()
            val out = mutableMapOf<String, Boolean?>()
            for (i in 0 until info.length()) {
                val d = info.optJSONObject(i) ?: continue
                val did = d.optString("device_id")
                if (did.isEmpty()) continue
                val st = d.optJSONObject("state") ?: continue
                val online = st.opt("online").let { it == null || it == true || it.toString() == "1" || it.toString() == "true" }
                val v = st.opt("1")?.toString()?.toIntOrNull()
                if (online && v != null) out[did] = v > 0
            }
            return out
        }

        fun checkRet(j: JSONObject, what: String) {
            when (j.optString("ret")) {
                "1" -> return
                "1001" -> throw CloudError("Wrong password.")
                "2006" -> throw CloudError("No CozyLife account with that email.")
                else -> throw CloudError("CozyLife $what failed: ${j.optString("desc").ifEmpty { j.optString("ret") }}")
            }
        }

        private fun enc(s: String) = URLEncoder.encode(s, "UTF-8")
    }

    data class Session(val token: String, val uid: String)

    /** Returns a session token and the user id. The password is only sent here, never stored. */
    fun login(email: String, password: String): Session {
        val form = linkedMapOf(
            "mail" to email.trim(),
            "passwd" to password,
            "imei" to UUID.randomUUID().toString().replace("-", ""),
            "lang" to "en",
            "platform" to "android",
            // The server picks a relay from these and fails on 0,0. Rough Norway is fine.
            "lat" to "59.74",
            "lng" to "10.20",
            "package_name" to "app.cozylife.app",
            "package_version" to "1200240553",
            "user_term_version" to "1.0.0",
            "user_privacy_version" to "1.0.0",
        ).entries.joinToString("&") { "${it.key}=${enc(it.value)}" }
        val j = JSONObject(http("/api/app/user/login", form))
        checkRet(j, "sign-in")
        val info = j.optJSONObject("info")
        val token = info?.optString("token")?.ifEmpty { null }
            ?: throw CloudError("CozyLife signed you in but sent no session.")
        return Session(token, info!!.optString("uid"))
    }

    /**
     * On/off for many plugs in one request, from CozyLife's cloud (what the CozyLife app shows).
     * Plugs the cloud has no fresh state for are left out.
     */
    fun states(token: String, dids: List<String>): Map<String, Boolean?> {
        if (dids.isEmpty()) return emptyMap()
        val q = dids.joinToString("&") { "device_ids%5B%5D=${enc(it)}" }
        return parseStates(http("/api/app/v2/device/states?$q&token=${enc(token)}&platform=android&lang=en", null))
    }

    /** The account's user id (GET /api/app/user/profile, as the CozyLife app does), for older sign-ins that didn't keep it. */
    fun uid(token: String): String? {
        val j = JSONObject(http("/api/app/user/profile?token=${enc(token)}&platform=android&lang=en", null))
        checkRet(j, "profile")
        return j.optJSONObject("info")?.opt("uid")?.toString()?.ifEmpty { null }
    }

    fun devices(token: String): List<CloudPlug> =
        parseDevices(http("/api/v2/app/device_with_group/list?token=${enc(token)}&platform=android&lang=en", null))

    private fun http(path: String, form: String?): String {
        val c = URL(base + path).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = 10_000; c.readTimeout = 15_000
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            if (form != null) {
                c.requestMethod = "POST"
                c.doOutput = true
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
                c.outputStream.use { it.write(form.toByteArray()) }
            }
            val code = c.responseCode
            if (code != 200) throw CloudError("CozyLife answered with error $code.")
            return c.inputStream.bufferedReader().use { it.readText() }
        } catch (e: CloudError) {
            throw e
        } catch (e: Exception) {
            throw CloudError("Couldn't reach CozyLife. Check your internet.")
        } finally {
            c.disconnect()
        }
    }
}

/**
 * CozyLife's cloud relay, the way the CozyLife app switches plugs from anywhere. The plug
 * keeps a connection open to the relay; we connect too and pass it the same JSON message
 * the plug understands locally, wrapped in a line like:
 *
 *   cmd=subscribe&topic=device_<id>&device_id=<id>&device_key=<key>
 *   cmd=publish&topic=control_<id>&device_id=<id>&device_key=<key>&message=<url-encoded JSON>
 *
 * We must subscribe to the plug's report topic first, or its answer has nowhere to go.
 */
class CozyRelay(private val timeoutMs: Int = 6000) {
    /** The plug's raw reply to the last command (for the connection log), or null. */
    @Volatile var lastRaw: String? = null

    /** Whether the plug is on, or null if it didn't answer. Throws CloudError if the plug is offline. */
    fun query(p: CloudPlug): Boolean? =
        exchange(p, CozyClient.CMD_QUERY, JSONObject().put("attr", JSONArray().put(0)))

    fun set(p: CloudPlug, on: Boolean): Boolean? = exchange(
        p, CozyClient.CMD_SET,
        JSONObject().put("attr", JSONArray().put(1)).put("data", JSONObject().put("1", if (on) 255 else 0))
    )

    private fun line(p: CloudPlug, vararg fields: Pair<String, String>): ByteArray {
        val all = fields.toList() + listOf("device_id" to p.did, "device_key" to p.key)
        return (all.joinToString("&") { "${it.first}=${it.second}" } + "\r\n").toByteArray()
    }

    private fun exchange(p: CloudPlug, cmd: Int, msg: JSONObject): Boolean? {
        if (p.relayHost.isEmpty() || p.relayPort == 0) return null
        val sn = CozyClient.nextSn()
        val frame = JSONObject().put("pv", 0).put("cmd", cmd).put("sn", sn).put("msg", msg).toString()
        val deadline = System.currentTimeMillis() + timeoutMs
        return try {
            Socket().use { s ->
                s.connect(InetSocketAddress(p.relayHost, p.relayPort), 5000)
                s.soTimeout = 1000
                val out = s.getOutputStream()
                out.write(line(p, "cmd" to "subscribe", "topic" to "device_${p.did}"))
                out.write(line(p, "cmd" to "publish", "topic" to "control_${p.did}",
                    "message" to URLEncoder.encode(frame, "UTF-8").replace("+", "%20")))
                out.flush()
                val lines = LineReader(s.getInputStream())
                while (System.currentTimeMillis() < deadline) {
                    val l = try { lines.next() } catch (_: SocketTimeoutException) { continue } ?: break
                    val f = parseLine(l)
                    if (f["cmd"] == "publish" && f["num"] == "0") {
                        throw CloudError("The plug isn't connected to CozyLife right now.")
                    }
                    if (f["topic"] != "device_${p.did}") continue
                    val raw = f["message"] ?: continue
                    val text = try { URLDecoder.decode(raw.replace("+", "%2B"), "UTF-8") } catch (_: Exception) { continue }
                    val reply = try { JSONObject(text) } catch (_: Exception) { continue }
                    if (reply.optString("sn") != sn) continue
                    lastRaw = text.take(200)
                    val data = reply.optJSONObject("msg")?.optJSONObject("data")
                    return@use if (data != null && data.has("1")) data.optInt("1") > 0 else null
                }
                null
            }
        } catch (e: CloudError) {
            throw e
        } catch (_: Exception) {
            null
        }
    }

    companion object {
        /** Splits key=value&… without form-decoding, so a '+' in the JSON survives. */
        fun parseLine(l: String): Map<String, String> = l.split("&").mapNotNull {
            val i = it.indexOf('=')
            if (i <= 0) null else it.substring(0, i) to it.substring(i + 1)
        }.toMap()
    }

    /** CRLF lines, kept whole even when TCP splits or joins them. */
    private class LineReader(private val input: InputStream) {
        private val sb = StringBuilder()
        fun next(): String? {
            while (true) {
                val b = input.read()
                if (b < 0) return if (sb.isNotEmpty()) sb.toString().also { sb.setLength(0) } else null
                if (b == '\n'.code) {
                    val s = sb.toString().trimEnd('\r'); sb.setLength(0)
                    if (s.isNotEmpty()) return s
                } else sb.append(b.toChar())
            }
        }
    }
}
