package com.terraaltis.app

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.annotation.SuppressLint
import android.net.Uri
import android.os.Bundle
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import org.json.JSONObject

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileCb: ValueCallback<Array<Uri>>? = null
    private var trackReceiverRegistered = false

    private val trackReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != TrackRecorderService.ACTION_UPDATE || !::webView.isInitialized) return
            val track = intent.getStringExtra(TrackRecorderService.EXTRA_TRACK) ?: "[]"
            val km = intent.getDoubleExtra(TrackRecorderService.EXTRA_KM, 0.0)
            val ascent = intent.getDoubleExtra(TrackRecorderService.EXTRA_ASCENT, 0.0)
            val recording = intent.getBooleanExtra(TrackRecorderService.EXTRA_RECORDING, false)
            val error = intent.getStringExtra("error").orEmpty()
            val barometer = intent.getBooleanExtra("barometer", false)
            webView.evaluateJavascript(
                "TerraAltis.updateTrack(${JSONObject.quote(track)}, $km, $ascent, $recording, ${JSONObject.quote(error)}, $barometer)",
                null
            )
        }
    }

    override fun onStart() {
        super.onStart()
        if (!trackReceiverRegistered) {
            ContextCompat.registerReceiver(
                this, trackReceiver, IntentFilter(TrackRecorderService.ACTION_UPDATE),
                ContextCompat.RECEIVER_NOT_EXPORTED
            )
            trackReceiverRegistered = true
        }
    }

    override fun onStop() {
        if (trackReceiverRegistered) {
            unregisterReceiver(trackReceiver)
            trackReceiverRegistered = false
        }
        super.onStop()
    }

    // Selector de archivos (importar GPX desde <input type="file">)
    private val fileLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        fileCb?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        fileCb = null
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
                override fun onGeolocationPermissionsShowPrompt(o: String, cb: GeolocationPermissions.Callback) = cb.invoke(o, true, false)
                override fun onShowFileChooser(w: WebView, cb: ValueCallback<Array<Uri>>, p: FileChooserParams): Boolean {
                    fileCb?.onReceiveValue(null); fileCb = cb
                    fileLauncher.launch(p.createIntent()); return true
                }
            }
            addJavascriptInterface(WebAppInterface(this@MainActivity, this), "Android")
            loadUrl("file:///android_asset/www/index.html")
        }
        setContentView(webView)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // Primero cierra drawer/menús desde JS; si ya estaban cerrados, sale.
                webView.evaluateJavascript("TerraAltis.back()") { r -> if (r != "true") finish() }
            }
        })
    }
}
