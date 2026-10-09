package com.terraaltis.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.abs
import kotlin.math.ceil

/** Resultado: puntos [lat, lng, ele], distancia (km) y desnivel positivo estimado (m). */
data class RouteResult(val points: JSONArray, val km: Double, val ascent: Double)

/**
 * Calcula rutas de senderismo con el servidor público OSRM de FOSSGIS y estima
 * el desnivel con el modelo digital de elevación de Open-Meteo (GLO-90).
 * No necesita API key. El desnivel es una estimación por la resolución de 90 m del DEM.
 */
object RoutingEngine {
    private const val URL_OSRM = "https://routing.openstreetmap.de/routed-foot/route/v1/foot"
    private const val URL_ELEVATION = "https://api.open-meteo.com/v1/elevation"
    private const val MAX_ELEVATION_POINTS = 100

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

        val coordinates = "${sLng},${sLat};${eLng},${eLat}"
        val routeUrl = URL(
            "$URL_OSRM/$coordinates?alternatives=3&overview=full&geometries=geojson&steps=false"
        )
        val response = getJson(routeUrl, "OSRM")
        if (response.optString("code") != "Ok") {
            error(response.optString("message").takeIf { it.isNotBlank() }
                ?: "El servicio de rutas no encontró un camino para esos puntos")
        }

        val routes = response.optJSONArray("routes")
            ?: error("El servicio de rutas devolvió una respuesta sin rutas")
        if (routes.length() == 0) error("No se encontró una ruta peatonal entre esos puntos")

        data class Candidate(val points: JSONArray, val km: Double, val ascent: Double)
        val candidates = (0 until routes.length()).mapNotNull { index ->
            val route = routes.optJSONObject(index) ?: return@mapNotNull null
            val geometry = route.optJSONObject("geometry")?.optJSONArray("coordinates")
                ?: return@mapNotNull null
            if (geometry.length() < 2) return@mapNotNull null

            val sampled = sampleCoordinates(geometry, MAX_ELEVATION_POINTS)
            val elevations = getElevations(sampled)
            val outputPoints = JSONArray()
            for (i in 0 until sampled.length()) {
                val c = sampled.getJSONArray(i)
                outputPoints.put(
                    JSONArray()
                        .put(c.getDouble(1))
                        .put(c.getDouble(0))
                        .put(elevations[i])
                )
            }
            Candidate(
                outputPoints,
                route.optJSONObject("distance")?.let { it.optDouble("value", Double.NaN) }
                    ?.takeIf { it.isFinite() } ?: route.optDouble("distance") / 1000.0,
                ascentOf(elevations)
            )
        }

        if (candidates.isEmpty()) error("El servicio devolvió rutas sin geometría utilizable")
        val best = candidates.minByOrNull { it.ascent }!!
        val distanceKm = if (best.km > 0.0) best.km else {
            // OSRM expresa la distancia en metros.
            val route = routes.optJSONObject(0)!!
            route.optDouble("distance") / 1000.0
        }
        return RouteResult(best.points, distanceKm, best.ascent)
    }

    /** Reduce cada geometría a un máximo de 100 puntos para consultar elevaciones por lotes. */
    private fun sampleCoordinates(coordinates: JSONArray, maxPoints: Int): JSONArray {
        if (coordinates.length() <= maxPoints) return coordinates
        val sampled = JSONArray()
        val last = coordinates.length() - 1
        val stride = ceil(last.toDouble() / (maxPoints - 1)).toInt().coerceAtLeast(1)
        var i = 0
        while (i < last) {
            sampled.put(coordinates.getJSONArray(i))
            i += stride
        }
        sampled.put(coordinates.getJSONArray(last))
        return sampled
    }

    /** La API acepta hasta 100 coordenadas por solicitud y no requiere clave para uso libre. */
    private fun getElevations(coordinates: JSONArray): List<Double> {
        val latitudes = (0 until coordinates.length()).joinToString(",") { i ->
            coordinates.getJSONArray(i).getDouble(1).toString()
        }
        val longitudes = (0 until coordinates.length()).joinToString(",") { i ->
            coordinates.getJSONArray(i).getDouble(0).toString()
        }
        val endpoint = URL("$URL_ELEVATION?latitude=$latitudes&longitude=$longitudes")
        val response = getJson(endpoint, "Open-Meteo Elevation")
        val values = response.optJSONArray("elevation")
            ?: error("Open-Meteo no devolvió datos de elevación")
        if (values.length() != coordinates.length()) {
            error("Open-Meteo devolvió una cantidad inesperada de elevaciones")
        }
        return (0 until values.length()).map { i ->
            if (values.isNull(i)) error("No hay datos de elevación para parte de esta ruta")
            values.getDouble(i)
        }
    }

    /** Suma ascensos de al menos 3 m para reducir el ruido del modelo de elevación. */
    private fun ascentOf(elevations: List<Double>): Double {
        if (elevations.size < 2) return 0.0
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

    private fun getJson(endpoint: URL, serviceName: String): JSONObject {
        val connection = endpoint.openConnection() as HttpURLConnection
        try {
            connection.requestMethod = "GET"
            connection.connectTimeout = 15000
            connection.readTimeout = 30000
            connection.setRequestProperty("Accept", "application/json")
            connection.setRequestProperty("User-Agent", "TerraAltis/1.0 (Android hiking route planner)")
            val code = connection.responseCode
            val stream = if (code in 200..299) connection.inputStream else connection.errorStream
            val text = stream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() }.orEmpty()
            if (code !in 200..299) {
                error("$serviceName HTTP $code" + text.takeIf { it.isNotBlank() }?.let { ": $it" }.orEmpty())
            }
            return JSONObject(text)
        } finally {
            connection.disconnect()
        }
    }
}
