package com.terraaltis.app

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import org.json.JSONArray
import org.json.JSONObject
import kotlin.concurrent.thread

class WebAppInterface(private val context: Context, private val webView: WebView) {
    @JavascriptInterface
    fun onRouteWaypoints(waypointsJson: String) {
        thread(name = "TerraAltisRoute") {
            try {
                val array = JSONArray(waypointsJson)
                val points = (0 until array.length()).map { i ->
                    val item = array.getJSONObject(i)
                    RouteWaypoint(item.getDouble("lat"), item.getDouble("lng"))
                }
                val result = RoutingEngine.bestRouteThroughWaypoints(context.applicationContext, points)
                js("TerraAltis.drawRoute(${JSONObject.quote(result.points.toString())}, ${result.km}, ${result.ascent})")
            } catch (e: Exception) {
                Log.e("TerraAltis", "Falló el cálculo de ruta", e)
                val message = JSONObject.quote("No se pudo calcular la ruta: ${e.message ?: "error desconocido"}")
                js("TerraAltis.routeCalculationFailed($message)")
            }
        }
    }

    @JavascriptInterface
    fun onRouteEndpoints(startLat: Double, startLng: Double, endLat: Double, endLng: Double) {
        val payload = JSONArray()
            .put(JSONObject().put("lat", startLat).put("lng", startLng))
            .put(JSONObject().put("lat", endLat).put("lng", endLng))
        onRouteWaypoints(payload.toString())
    }

    @JavascriptInterface
    fun exportGpx(filename: String, content: String) {
        val activity = context as? MainActivity
        if (activity == null) { Log.e("TerraAltis", "No se pudo exportar GPX fuera de MainActivity"); return }
        activity.exportGpxDocument(filename, content)
    }
    private fun js(code: String) = webView.post { webView.evaluateJavascript(code, null) }
}
