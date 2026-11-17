package com.fitzy.torio.net

import java.net.InetAddress
import java.net.UnknownHostException
import kotlinx.serialization.json.*
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request

/** Uses the phone's DNS first; if the name won't resolve, asks Cloudflare over HTTPS instead. */
class DohFallbackDns : Dns {
    private val doh = OkHttpClient()

    override fun lookup(hostname: String): List<InetAddress> = try {
        Dns.SYSTEM.lookup(hostname)
    } catch (e: UnknownHostException) {
        val ips = runCatching {
            val req = Request.Builder().url("https://1.1.1.1/dns-query?name=$hostname&type=A")
                .header("accept", "application/dns-json").build()
            doh.newCall(req).execute().use { r ->
                val answers = Json.parseToJsonElement(r.body?.string().orEmpty()).jsonObject["Answer"] as? JsonArray
                answers.orEmpty().mapNotNull { (it as? JsonObject)?.get("data")?.jsonPrimitive?.contentOrNull }
                    .filter { it.matches(Regex("""\d+\.\d+\.\d+\.\d+""")) }
                    .map { InetAddress.getByName(it) }
            }
        }.getOrDefault(emptyList())
        ips.ifEmpty { throw e }
    }
}
