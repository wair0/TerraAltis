package com.terraaltis.app

import android.content.Context
import android.util.Log
import android.webkit.JavascriptInterface
import android.webkit.WebView
import android.content.Intent
import androidx.core.content.ContextCompat
import org.json.JSONObject
import kotlin.concurrent.thread

/** Puente entre la UI web (Leaflet) y la lógica nativa. En JS: window.Android.* */
class WebAppInterface(private val context: Context, private val webView: WebView) {

    @JavascriptInterface
    fun startTrackRecording() {
        ContextCompat.startForegroundService(
            context,
            Intent(context, TrackRecorderService::class.java).setAction(TrackRecorderService.ACTION_START)
        )
    }

    @JavascriptInterface
    fun stopTrackRecording() {
        context.startService(
            Intent(context, TrackRecorderService::class.java).setAction(TrackRecorderService.ACTION_STOP)
        )
    }

    @JavascriptInterface
    fun onRouteEndpoints(startLat: Double, startLng: Double, endLat: Double, endLng: Double) {
        Log.d("TerraAltis", "Start=($startLat,$startLng) End=($endLat,$endLng)")
        thread {
            try {
                val r = RoutingEngine.bestRoute(startLat, startLng, endLat, endLng)
                js("TerraAltis.drawRoute(${r.points}, ${r.km}, ${r.ascent})")
            } catch (e: Exception) {
                Log.e("TerraAltis", "Ruta fallida", e)
                js("TerraAltis.setStatus(${JSONObject.quote("No se pudo calcular la ruta: ${e.message}")}, 'busy')")
            }
        }
    }

    private fun js(code: String) = webView.post { webView.evaluateJavascript(code, null) }
}
