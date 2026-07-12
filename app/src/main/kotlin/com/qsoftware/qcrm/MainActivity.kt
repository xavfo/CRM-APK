package com.qsoftware.qcrm

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
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.widget.Toast
import android.view.ViewGroup
import android.view.WindowInsets
import android.widget.FrameLayout
import android.webkit.*
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import java.util.ArrayList
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var db: OfflineDb
    lateinit var webView: WebView
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
        val data = if (result.resultCode == RESULT_OK) result.data else null
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
                // Show native toast notification
                Toast.makeText(this@MainActivity, "Conexión restaurada", Toast.LENGTH_SHORT).show()
                // Notify the web application when connection is restored
                webView.evaluateJavascript(
                    "if (typeof onNetworkStatusChanged === 'function') { onNetworkStatusChanged(true); }",
                    null
                )
                // Trigger sync for any pending offline data
                triggerOfflineSync()
            }
        }

        override fun onLost(network: Network) {
            runOnUiThread {
                webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                // Show native toast notification
                Toast.makeText(this@MainActivity, "Sin conexión - Modo offline", Toast.LENGTH_LONG).show()
                // Notify the web application when connection is lost
                webView.evaluateJavascript(
                    "if (typeof onNetworkStatusChanged === 'function') { onNetworkStatusChanged(false); }",
                    null
                )
            }
        }
    }
private fun configureWebViewCache() {
    File(applicationContext.cacheDir, "webview_cache").let {
        if (!it.exists()) it.mkdirs()
    }

    webView.settings.apply {
        domStorageEnabled = true
    }

    CookieManager.getInstance().run {
        setAcceptThirdPartyCookies(webView, true)
        acceptCookie()
        flush()
    }
}
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        db = OfflineDb(this)
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

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

                override fun onPageFinished(view: WebView?, url: String?) {
                    super.onPageFinished(view, url)
                    // Inject offline queue management script into every loaded page
                    try {
                        val js = assets.open("offline-queue.js")
                            .bufferedReader()
                            .use { it.readText() }
                        evaluateJavascript(js, null)
                    } catch (e: Exception) {
                        // offline-queue.js may not exist yet
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
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, networkCallback)

        configureWebViewCache()

        // Root FrameLayout wraps the WebView to consume insets on the container
        // instead of the WebView itself, preventing HTML content from overlapping
        // the status bar and navigation bar.
        val rootLayout = FrameLayout(this).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        }
        rootLayout.addView(
            webView,
            ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT
            )
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            // Android 11+: use WindowInsetsController API
            window.setDecorFitsSystemWindows(false)
            rootLayout.setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(
                    WindowInsets.Type.systemBars() or
                    WindowInsets.Type.displayCutout()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        } else {
            // Pre-Android 11: use Compat APIs
            WindowCompat.setDecorFitsSystemWindows(window, false)
            ViewCompat.setOnApplyWindowInsetsListener(rootLayout) { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or
                    WindowInsetsCompat.Type.displayCutout()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        setContentView(rootLayout)
        webView.loadUrl("https://neo.qsoftware.biz/crm/")
    }

    fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
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

    /**
     * Triggers synchronization of offline data when connection is restored.
     * Notifies the web app via JavaScript to handle sync of cached data.
     */
    private fun triggerOfflineSync() {
        webView.evaluateJavascript(
            "if (typeof onSyncTriggered === 'function') { onSyncTriggered(); }",
            null
        )
    }

    override fun onDestroy() {
        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
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
        fun showToast(message: String) {
            activity.runOnUiThread {
                Toast.makeText(activity, message, Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun isOnline(): Boolean {
            val cm = activity.getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
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
                activity.startTracking(intervalMs)
            }
        }

        @JavascriptInterface
        fun stopTracking() {
            activity.runOnUiThread {
                activity.stopTracking()
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
            activity.runOnUiThread {
                val connected = activity.isNetworkAvailable()
                activity.webView.settings.cacheMode = if (connected) {
                    WebSettings.LOAD_DEFAULT
                } else {
                    WebSettings.LOAD_CACHE_ELSE_NETWORK
                }
            }
        }

        /**
         * Triggers manual sync from JavaScript. Use this when the web app
         * wants to explicitly sync all pending offline data.
         */
        @JavascriptInterface
        fun triggerSync() {
            activity.runOnUiThread {
                activity.triggerOfflineSync()
            }
        }

        /**
         * Returns whether there is pending data to sync.
         */
        @JavascriptInterface
        fun hasPendingSync(): Boolean {
            return db.getPendingSyncCount() > 0
        }

        /**
         * Get count of pending sync items
         */
        @JavascriptInterface
        fun getPendingSyncCount(): Int {
            return db.getPendingSyncCount()
        }

        /**
         * Queue data for sync when back online
         */
        @JavascriptInterface
        fun queueForSync(action: String, key: String, data: String) {
            db.queueForSync(action, key, data)
        }

        /**
         * Get all pending sync items as JSON array
         */
        @JavascriptInterface
        fun getPendingSyncItems(): String {
            val items = db.getPendingSyncItems()
            val list = items.map { item ->
                """{"id":${item.id},"action":"${item.action}","key":"${item.key}","data":${item.data},"timestamp":${item.timestamp}}"""
            }
            return "[${list.joinToString(",")}]"
        }

        /**
         * Mark sync item as completed
         */
        @JavascriptInterface
        fun markSynced(id: Long) {
            db.markSynced(id)
        }

        /**
         * Mark all sync items as completed
         */
        @JavascriptInterface
        fun markAllSynced() {
            db.markAllSynced()
        }
    }
}
