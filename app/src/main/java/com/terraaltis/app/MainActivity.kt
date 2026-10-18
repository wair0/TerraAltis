package com.terraaltis.app

import android.Manifest
import android.content.Intent
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
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
    private var pendingSharedGpx: Pair<Uri, String>? = null
    private var webPageReady = false
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
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        receiveSharedGpx(intent)
    }

    private fun receiveSharedGpx(incoming: Intent?) {
        if (incoming?.action != Intent.ACTION_SEND) return
        val uri = if (android.os.Build.VERSION.SDK_INT >= 33) {
            incoming.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java)
        } else {
            @Suppress("DEPRECATION")
            incoming.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri
        } ?: return
        var filename = uri.lastPathSegment ?: "Ruta compartida.gpx"
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) {
                    val index = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (index >= 0) filename = cursor.getString(index) ?: filename
                }
            }
        } catch (_: Exception) { }
        pendingSharedGpx = uri to filename
        deliverSharedGpx()
    }

    private fun deliverSharedGpx() {
        if (!webPageReady || pendingSharedGpx == null || !::webView.isInitialized) return
        val (uri, filename) = pendingSharedGpx ?: return
        pendingSharedGpx = null
        try {
            val bytes = contentResolver.openInputStream(uri)?.use { it.readBytes() }
                ?: error("No se pudo leer el archivo compartido")
            if (bytes.size > 15 * 1024 * 1024) error("El GPX supera el límite de 15 MB")
            val xml = bytes.toString(Charsets.UTF_8)
            val safeXml = JSONObject.quote(xml)
            val safeName = JSONObject.quote(filename)
            webView.evaluateJavascript("MapManager.loadGpx($safeXml, $safeName)", null)
        } catch (e: Exception) {
            val message = JSONObject.quote("No se pudo importar el GPX compartido: ${e.message ?: "error de lectura"}")
            webView.evaluateJavascript("TerraAltis.setStatus($message,'busy')", null)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ActivityCompat.requestPermissions(this,
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), 1)

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.allowFileAccess = true
            settings.setGeolocationEnabled(true)
            webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    webPageReady = true
                    deliverSharedGpx()
                }
                override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest): Boolean {
                    val uri = request.url
                    if (!request.isForMainFrame || (uri.scheme != "https" && uri.scheme != "http")) return false
                    return try {
                        startActivity(Intent(Intent.ACTION_VIEW, uri))
                        true
                    } catch (_: Exception) {
                        false
                    }
                }
            }
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
        receiveSharedGpx(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                webView.evaluateJavascript("TerraAltis.back()") { result -> if (result != "true") finish() }
            }
        })
    }
}
