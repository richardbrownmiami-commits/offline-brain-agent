package com.cue.offlinebrain.net

import android.util.Xml
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import org.xmlpull.v1.XmlPullParser
import java.io.IOException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit

class PublicSkills {
    private val client = OkHttpClient.Builder()
        .connectTimeout(8, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(12, TimeUnit.SECONDS).build()

    private fun get(url: String, accept: String = "application/json"): String {
        val request = Request.Builder().url(url).header("User-Agent", "OfflineBrainAgent/1.0 (Android; user-requested lookup)")
            .header("Accept", accept).build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Service returned HTTP ${response.code}")
            return response.body?.string() ?: throw IOException("Service returned an empty response")
        }
    }

    fun weather(city: String): String {
        val name = enc(city)
        val geo = JSONObject(get("https://geocoding-api.open-meteo.com/v1/search?name=$name&count=1&language=en&format=json"))
        val places = geo.optJSONArray("results")
        if (places == null || places.length() == 0) return "I couldn't find a place called $city. Try adding a country name."
        val place = places.getJSONObject(0)
        val label = listOf(place.optString("name"), place.optString("admin1"), place.optString("country")).filter { it.isNotBlank() }.distinct().joinToString(", ")
        val forecast = JSONObject(get("https://api.open-meteo.com/v1/forecast?latitude=${place.getDouble("latitude")}&longitude=${place.getDouble("longitude")}&current=temperature_2m,relative_humidity_2m,apparent_temperature,weather_code&timezone=auto"))
        val current = forecast.getJSONObject("current")
        return "Weather in $label: ${current.optString("temperature_2m")}°C, feels like ${current.optString("apparent_temperature")}°C, humidity ${current.optString("relative_humidity_2m")}%. Conditions: ${weatherCode(current.optInt("weather_code"))}."
    }

    fun headlines(feedUrl: String, count: Int = 6): String {
        val xml = get(feedUrl, "application/rss+xml, application/atom+xml, application/xml, text/xml")
        val parser = Xml.newPullParser().apply { setInput(xml.reader()) }
        val titles = mutableListOf<String>()
        var event = parser.eventType
        var inTitle = false
        while (event != XmlPullParser.END_DOCUMENT && titles.size < count) {
            when (event) {
                XmlPullParser.START_TAG -> if (parser.name.equals("title", true)) inTitle = true
                XmlPullParser.TEXT -> if (inTitle) parser.text?.trim()?.takeIf { it.isNotBlank() }?.let(titles::add)
                XmlPullParser.END_TAG -> if (parser.name.equals("title", true)) inTitle = false
            }
            event = parser.next()
        }
        val clean = titles.drop(1).distinct().take(count)
        return if (clean.isEmpty()) "The news feed had no readable headlines." else "Latest headlines:\n" + clean.mapIndexed { i, t -> "${i + 1}. $t" }.joinToString("\n")
    }

    fun wikipedia(query: String): String {
        val slug = URLEncoder.encode(query.trim().replace(' ', '_'), StandardCharsets.UTF_8.name()).replace("+", "%20")
        val json = JSONObject(get("https://en.wikipedia.org/api/rest_v1/page/summary/$slug"))
        val extract = json.optString("extract")
        if (extract.isBlank()) return "I couldn't find a Wikipedia summary for “$query”."
        val link = json.optJSONObject("content_urls")?.optJSONObject("desktop")?.optString("page").orEmpty()
        return extract.take(2200) + if (link.isNotBlank()) "\n\nSource: $link" else ""
    }

    fun currency(from: String, to: String, amount: Double): String {
        val base = from.uppercase().take(3); val target = to.uppercase().take(3)
        val json = JSONObject(get("https://api.frankfurter.app/latest?from=$base&to=$target"))
        val rate = json.getJSONObject("rates").getDouble(target)
        return "${"%.2f".format(amount)} $base ≈ ${"%.2f".format(amount * rate)} $target (rate: $rate; date: ${json.optString("date")}). Source: frankfurter.app"
    }

    fun customGet(url: String, headers: Map<String, String> = emptyMap(), jsonPath: String = ""): String {
        val builder = Request.Builder().url(url).get().header("User-Agent", "OfflineBrainAgent/1.0")
        headers.forEach { (k, v) -> if (k.isNotBlank()) builder.header(k, v) }
        client.newCall(builder.build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Custom service returned HTTP ${response.code}")
            val body = response.body?.string().orEmpty()
            if (jsonPath.isBlank()) return body.take(4000)
            var node = JSONObject(body)
            for (part in jsonPath.split('.').filter { it.isNotBlank() }) node = node.getJSONObject(part)
            return node.toString().take(4000)
        }
    }

    private fun enc(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8.name())
    private fun weatherCode(code: Int) = when (code) {
        0 -> "clear sky"; 1, 2 -> "mostly clear"; 3 -> "overcast"; 45, 48 -> "fog"; 51, 53, 55 -> "drizzle";
        61, 63, 65 -> "rain"; 71, 73, 75 -> "snow"; 80, 81, 82 -> "rain showers"; 95, 96, 99 -> "thunderstorm"; else -> "variable"
    }
}
