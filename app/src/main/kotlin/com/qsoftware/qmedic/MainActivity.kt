package com.qsoftware.qmedic

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Bundle
import android.os.Looper
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import java.util.ArrayList

class MainActivity : AppCompatActivity() {

    private lateinit var db: OfflineDb
    private lateinit var webView: WebView
    private var locationManager: LocationManager? = null
    
    private var isTracking = false
    private var trackingIntervalMs = 300000L // Default: 5 minutes
    private var lastKnownLocation: Location? = null

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingLocationCallback: GeolocationPermissions.Callback? = null
    private var pendingLocationOrigin: String? = null

    // Activity Result Launcher for file upload selection in WebView
    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = if (result.resultCode == Activity.RESULT_OK) result.data else null
        val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
        fileUploadCallback?.onReceiveValue(results)
        fileUploadCallback = null
    }

    // Activity Result Launcher for location permissions
    private val locationPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] == true
        val coarseGranted = permissions[Manifest.permission.ACCESS_COARSE_LOCATION] == true
        val granted = fineGranted || coarseGranted
        
        pendingLocationCallback?.let { callback ->
            callback.invoke(pendingLocationOrigin, granted, false)
            pendingLocationCallback = null
            pendingLocationOrigin = null
        }
        
        if (granted) {
            startLocationUpdates()
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastKnownLocation = location
            if (isTracking) {
                db.logGPS(location.latitude, location.longitude, location.accuracy, location.time)
            }
        }
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
        override fun onProviderEnabled(provider: String) {}
        override fun onProviderDisabled(provider: String) {}
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                // Notify the web application when connection is restored
                webView.evaluateJavascript(
                    "if (typeof onNetworkStatusChanged === 'function') { onNetworkStatusChanged(true); }",
                    null
                )
            }
        }

        override fun onLost(network: Network) {
            runOnUiThread {
                webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                // Notify the web application when connection is lost
                webView.evaluateJavascript(
                    "if (typeof onNetworkStatusChanged === 'function') { onNetworkStatusChanged(false); }",
                    null
                )
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        db = OfflineDb(this)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // Modern back button and gesture navigation support
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (webView.canGoBack()) {
                    webView.goBack()
                } else {
                    isEnabled = false
                    onBackPressedDispatcher.onBackPressed()
                }
            }
        })

        webView = WebView(this).apply {
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            settings.databaseEnabled = true
            settings.setGeolocationEnabled(true)
            
            // Allow files and storage uploads inside WebView
            settings.allowFileAccess = true
            settings.allowContentAccess = true
            
            // Intelligent caching strategy
            settings.cacheMode = if (isNetworkAvailable()) {
                WebSettings.LOAD_DEFAULT
            } else {
                WebSettings.LOAD_CACHE_ELSE_NETWORK
            }

            WebView.setWebContentsDebuggingEnabled(true)
            
            // Expose the custom Android interface to javascript
            addJavascriptInterface(Bridge(this@MainActivity, db), "AndroidBridge")
            
            webViewClient = object : WebViewClient() {
                override fun shouldOverrideUrlLoading(
                    view: WebView?,
                    request: WebResourceRequest?
                ) = false

                override fun onReceivedError(
                    view: WebView?,
                    request: WebResourceRequest?,
                    error: WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        loadUrl("file:///android_asset/offline.html")
                    }
                }
            }

            webChromeClient = object : WebChromeClient() {
                // Support HTML5 Geolocation API
                override fun onGeolocationPermissionsShowPrompt(
                    origin: String?,
                    callback: GeolocationPermissions.Callback?
                ) {
                    if (hasLocationPermission()) {
                        callback?.invoke(origin, true, false)
                    } else {
                        pendingLocationCallback = callback
                        pendingLocationOrigin = origin
                        requestLocationPermissionsNatively()
                    }
                }

                // Support native File Chooser (for photos, attachments, signatures)
                override fun onShowFileChooser(
                    webView: WebView?,
                    filePathCallback: ValueCallback<Array<Uri>>?,
                    fileChooserParams: FileChooserParams?
                ): Boolean {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = filePathCallback
                    return try {
                        val intent = fileChooserParams?.createIntent()
                        fileChooserLauncher.launch(intent)
                        true
                    } catch (e: Exception) {
                        fileUploadCallback?.onReceiveValue(null)
                        fileUploadCallback = null
                        false
                    }
                }
            }
        }

        // Start collecting location if permission is already granted
        if (hasLocationPermission()) {
            startLocationUpdates()
        }

        // Register Network Callback to dynamically toggle cache and notify webpage
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, networkCallback)

        configureWebViewCache()

        setContentView(webView)
        webView.loadUrl("https://neo.qsoftware.biz/crm/")
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = cm.activeNetwork ?: return false
        val cap = cm.getNetworkCapabilities(network) ?: return false
        return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    fun hasLocationPermission(): Boolean {
        val fine = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
        val coarse = ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION)
        return fine == PackageManager.PERMISSION_GRANTED || coarse == PackageManager.PERMISSION_GRANTED
    }

    fun requestLocationPermissionsNatively() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (!hasLocationPermission()) return
        
        val providers = locationManager?.getProviders(true) ?: emptyList()
        if (providers.contains(LocationManager.GPS_PROVIDER)) {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                trackingIntervalMs,
                10f, // 10 meters distance change minimum
                locationListener,
                Looper.getMainLooper()
            )
        }
        if (providers.contains(LocationManager.NETWORK_PROVIDER)) {
            locationManager?.requestLocationUpdates(
                LocationManager.NETWORK_PROVIDER,
                trackingIntervalMs,
                10f,
                locationListener,
                Looper.getMainLooper()
            )
        }
        // Save initial coordinate fallback
        lastKnownLocation = getFreshLocation()
    }

    @SuppressLint("MissingPermission")
    fun getFreshLocation(): Location? {
        if (!hasLocationPermission()) return null
        val gpsLoc = locationManager?.getLastKnownLocation(LocationManager.GPS_PROVIDER)
        val netLoc = locationManager?.getLastKnownLocation(LocationManager.NETWORK_PROVIDER)
        
        var bestLocation = gpsLoc
        if (netLoc != null) {
            if (bestLocation == null || netLoc.time > bestLocation.time) {
                bestLocation = netLoc
            }
        }
        return bestLocation
    }

    fun getLocation(): String {
        val location = lastKnownLocation ?: return "{}"
        return "{" +
                "\"latitude\": ${location.latitude}," +
                "\"longitude\": ${location.longitude}," +
                "\"accuracy\": ${location.accuracy}," +
                "\"timestamp\": ${location.time}" +
                "}"
    }

    fun startTracking(intervalMs: Long) {
        trackingIntervalMs = intervalMs
        isTracking = true
        startLocationUpdates()
    }

    fun stopTracking() {
        isTracking = false
        locationManager?.removeUpdates(locationListener)
    }

    override fun onDestroy() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        cm.unregisterNetworkCallback(networkCallback)
        locationManager?.removeUpdates(locationListener)
        super.onDestroy()
    }

    // JavaScript Bridge API implementations
    private class Bridge(
        private val activity: MainActivity,
        private val db: OfflineDb
    ) {
        @JavascriptInterface
        fun isOnline(): Boolean {
            val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = cm.activeNetwork ?: return false
            val cap = cm.getNetworkCapabilities(network) ?: return false
            return cap.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        }

        @JavascriptInterface
        fun storeOffline(key: String, json: String) = db.insert(key, json)

        @JavascriptInterface
        fun fetchOffline(key: String): String? = db.get(key)

        @JavascriptInterface
        fun hasLocationPermission(): Boolean {
            return activity.hasLocationPermission()
        }

        @JavascriptInterface
        fun requestLocationPermission() {
            activity.runOnUiThread {
                activity.requestLocationPermissionsNatively()
            }
        }

        @JavascriptInterface
        fun getLocation(): String {
            val loc = activity.getFreshLocation()
            return if (loc != null) {
                "{\"latitude\":${loc.latitude},\"longitude\":${loc.longitude},\"accuracy\":${loc.accuracy},\"timestamp\":${loc.time}}"
            } else {
                "{\"error\":\"location_not_available\"}"
            }
        }

        @JavascriptInterface
        fun startTracking(intervalMs: Long) {
            activity.runOnUiThread {
                activity.startGPSLogging(intervalMs)
            }
        }

        @JavascriptInterface
        fun stopTracking() {
            activity.runOnUiThread {
                activity.stopGPSLogging()
            }
        }

        @JavascriptInterface
        fun getTrackingLogs(): String {
            return db.getGPSLogsJson()
        }

        @JavascriptInterface
        fun clearTrackingLogs() {
            db.clearGPSLogs()
        }

        @JavascriptInterface
        fun configureWebViewCache() {
            val cm = activity.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()

            cm.registerNetworkCallback(request, object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    runOnUiThread {
                        webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                        webView.evaluateJavascript(
                            "if (typeof onNetworkStatusChanged === 'function') { onNetworkStatusChanged(true); }",
                            null
                        )
                    }
                }

                override fun onLost(network: Network) {
                    runOnUiThread {
                        webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                        webView.evaluateJavascript(
                            "if (typeof onNetworkStatusChanged === 'function') { onNetworkStatusChanged(false); }",
                            null
                        )
                    }
                }
            })
        }
    }
}
