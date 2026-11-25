package io.lightknight.omniread.engine.scraper

import android.webkit.CookieManager
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.ConcurrentHashMap

class WebCookieJar : CookieJar {
    private val memoryStore = ConcurrentHashMap<String, List<Cookie>>()

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        val existing = memoryStore[url.host].orEmpty().associateBy { it.name }.toMutableMap()
        cookies.forEach { existing[it.name] = it }
        memoryStore[url.host] = existing.values.toList()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val list = memoryStore[url.host].orEmpty().toMutableList()
        // Merge Android WebView cookies (e.g. after user completes Cloudflare verification in WebView)
        runCatching {
            val rawCookie = CookieManager.getInstance().getCookie(url.toString()).orEmpty()
            if (rawCookie.isNotBlank()) {
                val existingNames = list.map { it.name }.toSet()
                rawCookie.split(";").forEach { part ->
                    val kv = part.trim().split("=", limit = 2)
                    if (kv.size == 2 && kv[0].isNotBlank() && kv[0] !in existingNames) {
                        list += Cookie.Builder()
                            .name(kv[0].trim())
                            .value(kv[1].trim())
                            .domain(url.host)
                            .build()
                    }
                }
            }
        }
        return list
    }
}
