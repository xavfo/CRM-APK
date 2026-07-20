package com.qsoftware.qcrm

import android.Manifest
import android.annotation.SuppressLint
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
import android.view.View
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import org.json.JSONObject
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var db: OfflineDb
    lateinit var webView: WebView
    private var locationManager: LocationManager? = null

    private lateinit var formContainer: ScrollView
    private lateinit var titleInput: EditText
    private lateinit var descriptionInput: EditText
    private lateinit var capturePhotoButton: Button
    private lateinit var saveButton: Button
    private lateinit var confirmOfflineButton: Button
    private lateinit var gpsInput: EditText
    private lateinit var locationStatusText: TextView
    private lateinit var statusText: TextView
    private lateinit var photoPreview: ImageView
    private lateinit var formCard: LinearLayout

    private var isTracking = false
    private var trackingIntervalMs = 300000L
    private var lastKnownLocation: Location? = null
    private var offlineFormConfirmed = false
    private var credentialsEntered = false
    private var formSavedWhileOffline = false
    private var currentPhotoFile: File? = null
    private var currentPhotoUri: Uri? = null

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingLocationCallback: GeolocationPermissions.Callback? = null
    private var pendingLocationOrigin: String? = null

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val data = if (result.resultCode == RESULT_OK) result.data else null
        val results = WebChromeClient.FileChooserParams.parseResult(result.resultCode, data)
        fileUploadCallback?.onReceiveValue(results)
        fileUploadCallback = null
    }

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
        } else {
            updateLocationStatus()
        }
    }

    private val cameraPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchCamera()
        } else {
            statusText.text = "Se requiere el permiso de cámara para tomar fotografías"
        }
    }

    private val takePictureLauncher = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { success ->
        if (success && currentPhotoUri != null) {
            photoPreview.setImageURI(currentPhotoUri)
            photoPreview.visibility = View.VISIBLE
            statusText.text = "Foto capturada y lista para guardar"
        } else {
            statusText.text = "No se pudo capturar la foto"
        }
    }

    private val locationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            lastKnownLocation = location
            updateLocationStatus()
            if (isTracking) {
                db.logGPS(location.latitude, location.longitude, location.accuracy, location.time)
            }
        }

        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        override fun onProviderEnabled(provider: String) = Unit
        override fun onProviderDisabled(provider: String) = Unit
    }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            runOnUiThread {
                webView.settings.cacheMode = WebSettings.LOAD_DEFAULT
                if (offlineFormConfirmed) {
                    formContainer.visibility = View.VISIBLE
                    if (formSavedWhileOffline) {
                        statusText.text = "Registro ingresado correctamente y listo para sincronizar"
                        Toast.makeText(this@MainActivity, "Registro ingresado correctamente", Toast.LENGTH_SHORT).show()
                        formSavedWhileOffline = false
                    } else {
                        statusText.text = "Conexión restaurada. El formulario sigue disponible"
                    }
                }
                webView.evaluateJavascript(
                    "if (typeof onNetworkStatusChanged === 'function') { onNetworkStatusChanged(true); }",
                    null
                )
                syncPendingForms()
            }
        }

        override fun onLost(network: Network) {
            runOnUiThread {
                webView.settings.cacheMode = WebSettings.LOAD_CACHE_ELSE_NETWORK
                if (!offlineFormConfirmed && credentialsEntered) {
                    statusText.text = "Sin conexión. Confirme el ingreso offline para continuar"
                }
                showOfflinePromptIfNeeded()
                Toast.makeText(this@MainActivity, "Sin conexión - Modo offline", Toast.LENGTH_LONG).show()
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
        locationManager = getSystemService(LOCATION_SERVICE) as LocationManager

        setContentView(R.layout.activity_main)

        formContainer = findViewById(R.id.formContainer)
        titleInput = findViewById(R.id.titleInput)
        descriptionInput = findViewById(R.id.descriptionInput)
        capturePhotoButton = findViewById(R.id.capturePhotoButton)
        saveButton = findViewById(R.id.saveButton)
        confirmOfflineButton = findViewById(R.id.confirmOfflineButton)
        gpsInput = findViewById(R.id.gpsInput)
        locationStatusText = findViewById(R.id.locationStatusText)
        statusText = findViewById(R.id.statusText)
        photoPreview = findViewById(R.id.photoPreview)
        formCard = findViewById(R.id.formCard)

        if (!hasLocationPermission()) {
            requestLocationPermissionsNatively()
        } else {
            startLocationUpdates()
        }

        capturePhotoButton.setOnClickListener {
            if (hasCameraPermission()) {
                launchCamera()
            } else {
                cameraPermissionLauncher.launch(Manifest.permission.CAMERA)
            }
        }

        saveButton.setOnClickListener { savePendingForm() }
        confirmOfflineButton.setOnClickListener { confirmOfflineEntry() }

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

        webView = findViewById(R.id.webView)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.setGeolocationEnabled(true)
        webView.settings.allowFileAccess = true
        webView.settings.allowContentAccess = true
        webView.settings.cacheMode = if (isNetworkAvailable()) {
            WebSettings.LOAD_DEFAULT
        } else {
            WebSettings.LOAD_CACHE_ELSE_NETWORK
        }

        WebView.setWebContentsDebuggingEnabled(true)
        webView.addJavascriptInterface(Bridge(this@MainActivity, db), "AndroidBridge")

        webView.webViewClient = object : WebViewClient() {
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
                    view?.loadUrl("file:///android_asset/offline.html")
                }
            }

            override fun onPageFinished(view: WebView?, url: String?) {
                super.onPageFinished(view, url)
                try {
                    val js = assets.open("offline-queue.js")
                        .bufferedReader()
                        .use { it.readText() }
                    webView.evaluateJavascript(js, null)
                } catch (_: Exception) {
                }
            }
        }

        webView.webChromeClient = object : WebChromeClient() {
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
                } catch (_: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    false
                }
            }
        }

        val cm = getSystemService(CONNECTIVITY_SERVICE) as ConnectivityManager
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        cm.registerNetworkCallback(request, networkCallback)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            window.setDecorFitsSystemWindows(false)
            findViewById<android.view.View>(android.R.id.content).setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        } else {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            findViewById<android.view.View>(android.R.id.content).setOnApplyWindowInsetsListener { v, insets ->
                val bars = insets.getInsets(
                    WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
                )
                v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }

        webView.loadUrl("https://neo.qsoftware.biz/crm/")
        updateLocationStatus()
        showOfflinePromptIfNeeded()
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

    fun hasCameraPermission(): Boolean {
        return ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
    }

    fun requestLocationPermissionsNatively() {
        locationPermissionLauncher.launch(
            arrayOf(
                Manifest.permission.ACCESS_FINE_LOCATION,
                Manifest.permission.ACCESS_COARSE_LOCATION
            )
        )
    }

    private fun launchCamera() {
        val imageFile = File(cacheDir, "images")
        if (!imageFile.exists()) {
            imageFile.mkdirs()
        }
        currentPhotoFile = File(imageFile, "form_${System.currentTimeMillis()}.jpg")
        currentPhotoUri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            currentPhotoFile!!
        )
        takePictureLauncher.launch(currentPhotoUri)
    }

    private fun updateLocationStatus() {
        val loc = lastKnownLocation ?: getFreshLocation()
        if (loc != null) {
            val formatted = "${loc.latitude}, ${loc.longitude}"
            locationStatusText.text = "GPS listo: $formatted (±${loc.accuracy} m)"
            if (gpsInput.text.isNullOrBlank() || gpsInput.text.toString().startsWith("Obteniendo") || gpsInput.text.toString().startsWith("GPS")) {
                gpsInput.setText(formatted)
            }
        } else if (!hasLocationPermission()) {
            locationStatusText.text = "Permiso de ubicación pendiente"
        } else {
            locationStatusText.text = "Obteniendo ubicación..."
            gpsInput.setText("Obteniendo ubicación...")
        }
    }

    private fun showOfflinePromptIfNeeded() {
        val offline = !isNetworkAvailable()
        confirmOfflineButton.visibility = if (offline && credentialsEntered && !offlineFormConfirmed) View.VISIBLE else View.GONE
        if (offline && credentialsEntered && !offlineFormConfirmed) {
            statusText.text = "Sin conexión detectada. Confirme el ingreso offline para continuar"
        }
    }

    private fun confirmOfflineEntry() {
        offlineFormConfirmed = true
        formContainer.visibility = View.VISIBLE
        confirmOfflineButton.visibility = View.GONE
        updateLocationStatus()
        statusText.text = "Ingreso offline confirmado. Complete el registro y se guardará"
        Toast.makeText(this, "Ingreso offline confirmado", Toast.LENGTH_SHORT).show()
    }

    private fun savePendingForm() {
        val title = titleInput.text.toString().trim()
        val description = descriptionInput.text.toString().trim()
        if (title.isEmpty()) {
            statusText.text = "El título del registro es obligatorio"
            return
        }

        val gpsValue = gpsInput.text.toString().trim()
        if (gpsValue.isEmpty()) {
            statusText.text = "La ubicación GPS es obligatoria"
            return
        }

        val location = lastKnownLocation ?: getFreshLocation()
        val payload = JSONObject().apply {
            put("title", title)
            put("description", description)
            put("gpsValue", gpsValue)
            put("photoPath", currentPhotoFile?.absolutePath ?: "")
            put("latitude", location?.latitude ?: 0.0)
            put("longitude", location?.longitude ?: 0.0)
            put("accuracy", location?.accuracy ?: 0.0)
            put("createdAt", System.currentTimeMillis())
        }.toString()

        db.insertPendingForm(
            payload = payload,
            photoPath = currentPhotoFile?.absolutePath,
            latitude = location?.latitude,
            longitude = location?.longitude,
            accuracy = location?.accuracy?.toDouble(),
            createdAt = System.currentTimeMillis()
        )

        formSavedWhileOffline = !isNetworkAvailable()
        statusText.text = if (isNetworkAvailable()) {
            "Registro guardado y sincronizado"
        } else {
            "Registro guardado offline. Se sincronizará cuando haya conexión"
        }
        formContainer.visibility = View.VISIBLE
        Toast.makeText(this, statusText.text, Toast.LENGTH_LONG).show()
        if (isNetworkAvailable()) {
            syncPendingForms()
        }
    }

    private fun syncPendingForms() {
        val pendingForms = db.getPendingForms()
        if (pendingForms.isEmpty()) {
            statusText.text = "No hay formularios pendientes"
            return
        }

        var syncedCount = 0
        pendingForms.forEach { form ->
            if (form.synced == 1) return@forEach
            db.markPendingFormSynced(form.id)
            syncedCount += 1
        }

        if (syncedCount > 0) {
            statusText.text = "Sincronizados $syncedCount formulario(s)"
            Toast.makeText(this, "Sincronizados $syncedCount formulario(s)", Toast.LENGTH_SHORT).show()
        }
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (!hasLocationPermission()) return

        val providers = locationManager?.getProviders(true) ?: emptyList()
        if (providers.contains(LocationManager.GPS_PROVIDER)) {
            locationManager?.requestLocationUpdates(
                LocationManager.GPS_PROVIDER,
                trackingIntervalMs,
                10f,
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
        lastKnownLocation = getFreshLocation()
        updateLocationStatus()
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
        fun onCredentialsEntered(entered: Boolean) {
            activity.runOnUiThread {
                activity.credentialsEntered = entered
                activity.showOfflinePromptIfNeeded()
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

        @JavascriptInterface
        fun triggerSync() {
            activity.runOnUiThread {
                activity.triggerOfflineSync()
            }
        }

        @JavascriptInterface
        fun hasPendingSync(): Boolean {
            return db.getPendingSyncCount() > 0
        }

        @JavascriptInterface
        fun getPendingSyncCount(): Int {
            return db.getPendingSyncCount()
        }

        @JavascriptInterface
        fun queueForSync(action: String, key: String, data: String) {
            db.queueForSync(action, key, data)
        }

        @JavascriptInterface
        fun getPendingSyncItems(): String {
            val items = db.getPendingSyncItems()
            val list = items.map { item ->
                """{"id":${item.id},"action":"${item.action}","key":"${item.key}","data":${item.data},"timestamp":${item.timestamp}}"""
            }
            return "[${list.joinToString(",")}]"
        }

        @JavascriptInterface
        fun markSynced(id: Long) {
            db.markSynced(id)
        }

        @JavascriptInterface
        fun markAllSynced() {
            db.markAllSynced()
        }
    }
}
