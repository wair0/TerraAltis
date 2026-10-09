package com.terraaltis.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs

/** Resultado: puntos [lat, lng, ele], distancia (km) y desnivel positivo (m). */
data class RouteResult(val points: JSONArray, val km: Double, val ascent: Double)

/**
 * Calcula alternativas de senderismo y selecciona la que tiene menor desnivel positivo.
 * El D+ se calcula a partir de las elevaciones de la geometría ([lng, lat, ele]):
 * no se presupone que ORS incluya una propiedad "ascent" en properties.
 */
object RoutingEngine {
    private const val URL_ORS =
        "https://api.heigit.org/openrouteservice/v2/directions/foot-hiking/geojson"

    fun bestRoute(sLat: Double, sLng: Double, eLat: Double, eLng: Double): RouteResult {
        require(listOf(sLat, sLng, eLat, eLng).all { it.isFinite() }) {
            "Las coordenadas de inicio y destino no son válidas"
        }
        require(sLat in -90.0..90.0 && eLat in -90.0..90.0 &&
                sLng in -180.0..180.0 && eLng in -180.0..180.0) {
            "Las coordenadas están fuera de rango"
        }
        require(abs(sLat - eLat) > 0.000001 || abs(sLng - eLng) > 0.000001) {
            "El inicio y el destino deben ser distintos"
        }
        require(Config.ORS_API_KEY.isNotBlank() && !Config.ORS_API_KEY.startsWith("TU_")) {
            "Falta configurar ORS_API_KEY en Config.kt o en el secreto de GitHub"
        }

        val coords = JSONArray()
            .put(JSONArray().put(sLng).put(sLat))
            .put(JSONArray().put(eLng).put(eLat))
        val base = JSONObject().put("coordinates", coords).put("elevation", true)
        val withAlt = JSONObject(base.toString()).put(
            "alternative_routes",
            JSONObject().put("target_count", 3).put("share_factor", 0.6).put("weight_factor", 1.6)
        )

        // Si ORS no admite alternativas para este trayecto, pedimos una ruta normal.
        val response = try {
            post(withAlt)
        } catch (e: Exception) {
            try {
                post(base)
            } catch (fallback: Exception) {
                fallback.addSuppressed(e)
                throw fallback
            }
        }

        val features = response.optJSONArray("features")
            ?: error(apiError(response, "OpenRouteService no devolvió rutas"))
        if (features.length() == 0) error(apiError(response, "OpenRouteService no encontró una ruta"))

        data class Candidate(val feature: JSONObject, val ascent: Double)
        val candidates = (0 until features.length()).mapNotNull { i ->
            val feature = features.optJSONObject(i) ?: return@mapNotNull null
            val coordinates = feature.optJSONObject("geometry")?.optJSONArray("coordinates")
                ?: return@mapNotNull null
            val gain = ascentOf(coordinates) ?: return@mapNotNull null
            Candidate(feature, gain)
        }
        if (candidates.isEmpty()) {
            error("La API devolvió rutas sin elevación. Verificá la conexión y volvé a intentar.")
        }
        val best = candidates.minByOrNull { it.ascent }!!
        val f = best.feature
        val line = f.getJSONObject("geometry").getJSONArray("coordinates")
        val pts = JSONArray()
        for (i in 0 until line.length()) {
            val c = line.getJSONArray(i)
            pts.put(
                JSONArray()
                    .put(c.getDouble(1))
                    .put(c.getDouble(0))
                    .put(if (c.length() > 2 && !c.isNull(2)) c.getDouble(2) else JSONObject.NULL)
            )
        }
        val km = f.getJSONObject("properties").getJSONObject("summary").getDouble("distance") / 1000.0
        return RouteResult(pts, km, best.ascent)
    }

    /** D+ con filtro de ruido de 3 m, coherente con el perfil mostrado en la UI. */
    private fun ascentOf(coordinates: JSONArray): Double? {
        if (coordinates.length() < 2) return null
        val elevations = (0 until coordinates.length()).mapNotNull { i ->
            val point = coordinates.optJSONArray(i) ?: return@mapNotNull null
            if (point.length() < 3 || point.isNull(2)) null else point.optDouble(2).takeIf { it.isFinite() }
        }
        if (elevations.size < 2) return null
        var gain = 0.0
        var reference = elevations.first()
        for (elevation in elevations.drop(1)) {
            val delta = elevation - reference
            if (delta >= 3.0) {
                gain += delta
                reference = elevation
            } else if (delta <= -3.0) {
                reference = elevation
            }
        }
        return gain
    }

    private fun apiError(response: JSONObject, fallback: String): String {
        val error = response.optJSONObject("error")
        return error?.optString("message")?.takeIf { it.isNotBlank() } ?: fallback
    }

    private fun post(body: JSONObject): JSONObject {
        val connection = URL(URL_ORS).openConnection() as HttpURLConnection
        connection.requestMethod = "POST"
        connection.connectTimeout = 15000
        connection.readTimeout = 30000
        connection.doOutput = true
        connection.setRequestProperty("Authorization", Config.ORS_API_KEY.trim())
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        connection.setRequestProperty("Accept", "application/geo+json, application/json")
        connection.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }

        val code = connection.responseCode
        val stream = if (code in 200..299) connection.inputStream else connection.errorStream
        val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
        if (code !in 200..299) {
            val detail = try {
                JSONObject(text).optJSONObject("error")?.optString("message")
            } catch (_: Exception) { null }
            error("OpenRouteService HTTP $code" + (detail?.takeIf { it.isNotBlank() }?.let { ": " + it } ?: ""))
        }
        return JSONObject(text)
    }
}
