package com.terraaltis.app

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.PriorityQueue
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/** Resultado: puntos [lat, lng, elevación], distancia (km) y desnivel positivo estimado (m). */
data class RouteResult(val points: JSONArray, val km: Double, val ascent: Double)

/**
 * Genera un trazado topográfico NUEVO entre dos waypoints. No consulta ni selecciona
 * caminos registrados: crea una cuadrícula de terreno, obtiene elevaciones de Open-Meteo
 * y usa Dijkstra sobre una cuadrícula más densa y ancha para adaptarse mejor al relieve,
 * penalizando la subida acumulada y las pendientes pronunciadas sin ignorar la distancia.
 *
 * Importante: el modelo GLO-90 es aproximado y esta cuadrícula no conoce caminos,
 * propiedades privadas, agua, vegetación ni obstáculos. No garantiza transitabilidad.
 */
object RoutingEngine {
    private const val URL_ELEVATION = "https://api.open-meteo.com/v1/elevation"
    private const val MAX_POINTS_PER_REQUEST = 100
    private const val ROWS = 21
    private const val MAX_COLUMNS = 100
    private const val TARGET_COLUMN_METERS = 75.0
    private const val ASCENT_COST_FACTOR = 8.0
    private const val DESCENT_COST_FACTOR = 0.15
    private const val STEEP_SLOPE_THRESHOLD = 0.08
    private const val STEEP_SLOPE_COST_FACTOR = 5.0

    private data class GridPoint(
        val lat: Double,
        val lng: Double,
        val east: Double,
        val north: Double
    )
    private data class QueueNode(val index: Int, val cost: Double)

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

        val meanLat = Math.toRadians((sLat + eLat) / 2.0)
        val endEast = (eLng - sLng) * 111320.0 * cos(meanLat)
        val endNorth = (eLat - sLat) * 111320.0
        val directDistance = hypot(endEast, endNorth)
        require(directDistance >= 10.0) {
            "Los puntos están demasiado cerca para crear una cuadrícula de terreno fiable"
        }

        val columns = (ceil(directDistance / TARGET_COLUMN_METERS).toInt() + 1)
            .coerceIn(2, MAX_COLUMNS)
        // Un corredor amplio permite bordear laderas fuertes; la anchura no implica
        // que el modelo conozca arroyos, barrancas verticales ni propiedades privadas.
        val corridorHalfWidth = (directDistance * 0.30).coerceIn(250.0, 2000.0)
        val alongEast = endEast / directDistance
        val alongNorth = endNorth / directDistance
        val crossEast = -alongNorth
        val crossNorth = alongEast
        val points = ArrayList<GridPoint>(columns * ROWS)

        for (column in 0 until columns) {
            val t = column.toDouble() / (columns - 1)
            val centerEast = endEast * t
            val centerNorth = endNorth * t
            for (row in 0 until ROWS) {
                val offset = (row - ROWS / 2).toDouble() / (ROWS / 2).toDouble() * corridorHalfWidth
                val east = centerEast + crossEast * offset
                val north = centerNorth + crossNorth * offset
                val lat = sLat + north / 111320.0
                val cosLat = cos(meanLat).let {
                    if (abs(it) < 0.01) if (it < 0) -0.01 else 0.01 else it
                }
                val lng = sLng + east / (111320.0 * cosLat)
                points.add(GridPoint(lat, lng, east, north))
            }
        }

        val elevations = getElevations(points)
        val path = shortestLowAscentPath(points, elevations, columns)
        val output = JSONArray()
        val pathElevations = ArrayList<Double>(path.size)
        var routeMeters = 0.0
        for (i in path.indices) {
            val pointIndex = path[i]
            val point = points[pointIndex]
            val elevation = elevations[pointIndex]
            output.put(JSONArray().put(point.lat).put(point.lng).put(elevation))
            pathElevations.add(elevation)
            if (i > 0) {
                val previous = points[path[i - 1]]
                routeMeters += hypot(point.east - previous.east, point.north - previous.north)
            }
        }
        return RouteResult(output, routeMeters / 1000.0, ascentOf(pathElevations))
    }

    private fun shortestLowAscentPath(
        points: List<GridPoint>,
        elevations: List<Double>,
        columns: Int
    ): List<Int> {
        val count = points.size
        val start = ROWS / 2
        val end = (columns - 1) * ROWS + ROWS / 2
        val costs = DoubleArray(count) { Double.POSITIVE_INFINITY }
        val previous = IntArray(count) { -1 }
        val queue = PriorityQueue<QueueNode>(compareBy { it.cost })
        costs[start] = 0.0
        queue.add(QueueNode(start, 0.0))

        while (queue.isNotEmpty()) {
            val current = queue.poll()
            if (current.cost != costs[current.index]) continue
            if (current.index == end) break
            val column = current.index / ROWS
            val row = current.index % ROWS
            for (dc in -1..1) {
                for (dr in -1..1) {
                    if (dc == 0 && dr == 0) continue
                    val nextColumn = column + dc
                    val nextRow = row + dr
                    if (nextColumn !in 0 until columns || nextRow !in 0 until ROWS) continue
                    val next = nextColumn * ROWS + nextRow
                    val a = points[current.index]
                    val b = points[next]
                    val horizontal = hypot(b.east - a.east, b.north - a.north)
                    val delta = elevations[next] - elevations[current.index]
                    val ascentPenalty = max(delta, 0.0) * ASCENT_COST_FACTOR
                    val descentPenalty = max(-delta, 0.0) * DESCENT_COST_FACTOR
                    // El DEM permite estimar la pendiente entre muestras. Se penalizan
                    // las subidas y bajadas fuertes para favorecer rodeos más suaves.
                    val grade = abs(delta) / horizontal.coerceAtLeast(1.0)
                    val steepPenalty = max(grade - STEEP_SLOPE_THRESHOLD, 0.0) *
                        horizontal * STEEP_SLOPE_COST_FACTOR
                    val candidate = current.cost + horizontal + ascentPenalty + descentPenalty + steepPenalty
                    if (candidate < costs[next]) {
                        costs[next] = candidate
                        previous[next] = current.index
                        queue.add(QueueNode(next, candidate))
                    }
                }
            }
        }

        if (!costs[end].isFinite()) error("No se pudo conectar el inicio con el destino en la cuadrícula")
        val path = ArrayList<Int>()
        var cursor = end
        while (cursor != -1) {
            path.add(cursor)
            if (cursor == start) break
            cursor = previous[cursor]
        }
        if (path.lastOrNull() != start) error("No se pudo reconstruir el trazado calculado")
        path.reverse()
        return path
    }

    /** Consulta elevaciones en lotes para respetar el límite de coordenadas por petición. */
    private fun getElevations(points: List<GridPoint>): List<Double> {
        val result = ArrayList<Double>(points.size)
        for (batch in points.chunked(MAX_POINTS_PER_REQUEST)) {
            val latitudes = batch.joinToString(",") { it.lat.toString() }
            val longitudes = batch.joinToString(",") { it.lng.toString() }
            val endpoint = URL("$URL_ELEVATION?latitude=$latitudes&longitude=$longitudes")
            val response = getJson(endpoint, "Open-Meteo Elevation")
            val values = response.optJSONArray("elevation")
                ?: error("Open-Meteo no devolvió datos de elevación")
            if (values.length() != batch.size) {
                error("Open-Meteo devolvió una cantidad inesperada de elevaciones")
            }
            for (i in 0 until values.length()) {
                if (values.isNull(i)) error("No hay datos de elevación para parte del terreno")
                result.add(values.getDouble(i))
            }
        }
        return result
    }

    /** Suma ascensos de al menos 3 m para reducir ruido en el modelo digital de elevación. */
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
            connection.setRequestProperty("User-Agent", "TerraAltis/1.0 (Android terrain route planner)")
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
