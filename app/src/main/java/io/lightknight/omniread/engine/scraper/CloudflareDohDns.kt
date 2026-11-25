package io.lightknight.omniread.engine.scraper

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.InetAddress
import java.net.URLEncoder
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/**
 * Resolves hostnames via Cloudflare DNS-over-HTTPS (1.1.1.1) with an in-memory TTL cache
 * to bypass ISP DNS blocks, falling back cleanly to system DNS if DoH is unavailable.
 */
class CloudflareDohDns : Dns {
    private data class CachedEntry(val addresses: List<InetAddress>, val expiresAtMs: Long)

    private val cache = ConcurrentHashMap<String, CachedEntry>()
    private val dohClient = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(5, TimeUnit.SECONDS)
        .build()

    override fun lookup(hostname: String): List<InetAddress> {
        // Never intercept raw IP literals or localhost
        if (hostname == "1.1.1.1" || hostname == "localhost" || hostname.all { it.isDigit() || it == '.' }) {
            return Dns.SYSTEM.lookup(hostname)
        }

        val now = System.currentTimeMillis()
        cache[hostname]?.let { cached ->
            if (cached.expiresAtMs > now && cached.addresses.isNotEmpty()) {
                return cached.addresses
            }
        }

        val dohResult = runCatching {
            val encoded = URLEncoder.encode(hostname, "UTF-8")
            val req = Request.Builder()
                .url("https://1.1.1.1/dns-query?name=$encoded&type=A")
                .header("Accept", "application/dns-json")
                .build()
            dohClient.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) return@runCatching emptyList<InetAddress>()
                val body = resp.body?.string().orEmpty()
                parseDohAddresses(body).map { ip -> InetAddress.getByName(ip) }
            }
        }.getOrDefault(emptyList())

        if (dohResult.isNotEmpty()) {
            cache[hostname] = CachedEntry(dohResult, now + CACHE_TTL_MS)
            return dohResult
        }

        return Dns.SYSTEM.lookup(hostname)
    }

    companion object {
        private const val CACHE_TTL_MS = 5 * 60 * 1000L
        private val json = Json { ignoreUnknownKeys = true }

        fun parseDohAddresses(jsonBody: String): List<String> {
            if (jsonBody.isBlank()) return emptyList()
            return runCatching {
                val root = json.parseToJsonElement(jsonBody).jsonObject
                val answers = root["Answer"]?.jsonArray ?: return emptyList()
                answers.mapNotNull { elem ->
                    val obj = elem.jsonObject
                    val type = obj["type"]?.jsonPrimitive?.intOrNull
                    val data = obj["data"]?.jsonPrimitive?.content?.trim().orEmpty()
                    if (type == 1 && data.isNotBlank()) data else null
                }
            }.getOrDefault(emptyList())
        }
    }
}
