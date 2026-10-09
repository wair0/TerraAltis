package com.terraaltis.app

import android.Manifest
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data))
        fileCb = null
    }
    private var pendingGpx: String? = null
    private val exportLauncher = registerForActivityResult(ActivityResultContracts.CreateDocument("application/gpx+xml")) { uri ->
        val payload = pendingGpx
        pendingGpx = null
        if (uri == null) {
            if (::webView.isInitialized) webView.evaluateJavascript("TerraAltis.setStatus('Exportación GPX cancelada','busy')", null)
        } else {
            try {
                val output = contentResolver.openOutputStream(uri) ?: error("No se pudo abrir el archivo")
                output.bufferedWriter(Charsets.UTF_8).use { it.write(payload.orEmpty()) }
                webView.evaluateJavascript("TerraAltis.setStatus('GPX guardado correctamente','ok')", null)
            } catch (e: Exception) {
                val message = JSONObject.quote("No se pudo guardar el GPX: ${e.message ?: "error de escritura"}")
                webView.evaluateJavascript("TerraAltis.setStatus($message,'busy')", null)
            }
        }
    }

    fun exportGpxDocument(filename: String, content: String) {
        pendingGpx = content
        runOnUiThread { exportLauncher.launch(filename.ifBlank { "TerraAltis.gpx" }) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.setGeolocationEnabled(true)
            webViewClient = WebViewClient()
            webChromeClient = object : WebChromeClient() {
                override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) = callback.invoke(origin, true, false)
                override fun onShowFileChooser(view: WebView, callback: ValueCallback<Array<Uri>>, params: FileChooserParams): Boolean {
                    fileCb?.onReceiveValue(null); fileCb = callback
                    fileLauncher.launch(params.createIntent()); return true
                }
            }
            addJavascriptInterface(WebAppInterface(this@MainActivity, this), "Android")
            loadUrl("file:///android_asset/www/index.html")
        }
        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("TerraAltis.back()") { result -> if (result != "true") finish() }
            }
        })
    }
}
