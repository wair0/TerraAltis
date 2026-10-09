package com.terraaltis.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

/** Resultado: puntos [lat, lng, ele], distancia (km) y desnivel positivo (m). */
data class RouteResult(val points: JSONArray, val km: Double, val ascent: Double)

/**
 * Motor de rutas: pide a OpenRouteService (perfil senderismo, con elevación) varias
 * alternativas y se queda con la de MENOR desnivel positivo acumulado.
 * Idea tomada de OpenTopoMapViewer, que usa ORS para rutas y perfil de elevación.
 * Reemplazable más adelante por un motor propio con DEM (SRTM).
 *
 * URL actualizada a api.heigit.org (api.openrouteservice.org fue deprecada en 2026).
 */
object RoutingEngine {
    private const val URL_ORS = "https://api.heigit.org/openrouteservice/v2/directions/foot-hiking/geojson"

    fun bestRoute(sLat: Double, sLng: Double, eLat: Double, eLng: Double): RouteResult {
        val coords = JSONArray()
            .put(JSONArray().put(sLng).put(sLat))
            .put(JSONArray().put(eLng).put(eLat))
        val base = JSONObject().put("coordinates", coords).put("elevation", true)

        // 1) con alternativas (ORS las limita a rutas cortas, < ~100 km)
        val withAlt = JSONObject(base.toString()).put(
            "alternative_routes",
            JSONObject().put("target_count", 3).put("share_factor", 0.6).put("weight_factor", 1.6)
        )
        val resp = try { post(withAlt) } catch (e: Exception) { post(base) }  // 2) sin alternativas

        val feats = resp.getJSONArray("features")
        var best: JSONObject? = null
        for (i in 0 until feats.length()) {
            val f = feats.getJSONObject(i)
            if (best == null || ascentOf(f) < ascentOf(best)) best = f
        }
        val f = best ?: error("Sin rutas")
        val line = f.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = JSONArray()
        for (i in 0 until line.length()) {           // ORS: [lng, lat, ele] -> [lat, lng, ele]
            val c = line.getJSONArray(i)
            pts.put(JSONArray().put(c.getDouble(1)).put(c.getDouble(0)).put(if (c.length() > 2) c.getDouble(2) else 0.0))
        }
        val km = f.getJSONObject("properties").getJSONObject("summary").getDouble("distance") / 1000.0
        return RouteResult(pts, km, ascentOf(f))
    }

    private fun ascentOf(f: JSONObject) = f.getJSONObject("properties").optDouble("ascent", Double.MAX_VALUE)

    private fun post(body: JSONObject): JSONObject {
        val c = URL(URL_ORS).openConnection() as HttpURLConnection
        c.requestMethod = "POST"; c.connectTimeout = 15000; c.readTimeout = 30000; c.doOutput = true
        c.setRequestProperty("Authorization", Config.ORS_API_KEY)
        c.setRequestProperty("Content-Type", "application/json")
        c.outputStream.use { it.write(body.toString().toByteArray()) }
        if (c.responseCode != 200) error("ORS HTTP ${c.responseCode}")
        return JSONObject(c.inputStream.bufferedReader().readText())
    }
}
