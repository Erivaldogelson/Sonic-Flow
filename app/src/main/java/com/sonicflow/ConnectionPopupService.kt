package com.sonicflow

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.app.AppOpsManager
import android.bluetooth.BluetoothDevice
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.provider.Settings
import android.app.usage.UsageStatsManager
import android.view.Gravity
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.toDrawable
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.ui.graphics.toArgb
import coil.ImageLoader
import coil.request.ImageRequest

/** Keeps the Bluetooth link monitored and displays the connection card over other apps. */
class ConnectionPopupService : Service() {
    private lateinit var controller: QcyBleController
    private var windowManager: WindowManager? = null
    private var popup: LinearLayout? = null
    private var popupParams: WindowManager.LayoutParams? = null
    private var statusCard: LinearLayout? = null
    private var statusCardWindowManager: WindowManager? = null
    private var lastConnected = false
    private var latestState = QcyBleController.State()
    private var liveNotificationImage: Bitmap? = null
    private var liveNotificationImageLoading = false
    private val imageLoader by lazy { ImageLoader(this) }
    private val handler = Handler(Looper.getMainLooper())
    private val hidePopup = Runnable { removePopup() }
    private var autoGameCommandSent = false
    private val autoGameModeMonitor = object : Runnable {
        override fun run() {
            val prefs = getSharedPreferences("sonicflow", MODE_PRIVATE)
            if (prefs.getBoolean("auto_game_mode", false) && latestState.connected && hasUsageAccess()) {
                val now = System.currentTimeMillis()
                val stats = (getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager)
                    ?.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, now - 60_000L, now)
                val foreground = stats?.maxByOrNull { it.lastTimeUsed }
                val isGame = foreground?.packageName?.let { packageName ->
                    try { packageManager.getApplicationInfo(packageName, 0).category == android.content.pm.ApplicationInfo.CATEGORY_GAME }
                    catch (_: Exception) { false }
                } == true
                if (!isGame) autoGameCommandSent = false
                else if (!autoGameCommandSent && latestState.gameMode != true) {
                    autoGameCommandSent = true
                    controller.setGameMode(true)
                }
            } else autoGameCommandSent = false
            handler.postDelayed(this, 2500L)
        }
    }

    private fun hasUsageAccess(): Boolean {
        val appOps = getSystemService(APP_OPS_SERVICE) as? AppOpsManager ?: return false
        return appOps.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), packageName) == AppOpsManager.MODE_ALLOWED
    }
    private val aclReceiver = object : android.content.BroadcastReceiver() {
        override fun onReceive(context: android.content.Context?, intent: Intent?) {
            val action = intent?.action ?: return
            val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") (intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice)
            val prefs = getSharedPreferences("sonicflow", MODE_PRIVATE)
            val savedAddress = prefs.getString("qcy_device_address", null)
            val savedClassicAddress = prefs.getString("qcy_classic_address", null)
            val bluetoothName = try { device?.name.orEmpty() } catch (_: SecurityException) { "" }
            val isH3 = device != null && (
                device.address == savedAddress || device.address == savedClassicAddress ||
                    bluetoothName.contains("qcy", true) || bluetoothName.contains("h3", true)
            )
            val customName = prefs.getString("popup_display_name", "")?.trim().orEmpty()
            val displayName = customName.ifBlank { bluetoothName }.ifBlank { latestState.deviceName }
            if (!isH3) return
            if (action == BluetoothDevice.ACTION_ACL_CONNECTED) {
                prefs.edit().putBoolean("qcy_connected_here", true).apply()
                if (prefs.getBoolean("connection_popup", true) && !lastConnected) showConnectionPopup(displayName, latestState.battery, latestState.charging)
                lastConnected = true
            } else if (action == BluetoothDevice.ACTION_ACL_DISCONNECTED) {
                prefs.edit().putBoolean("qcy_connected_here", false).putBoolean("qcy_audio_playing", false)
                    .putBoolean("qcy_audio_stop_pending", false).putBoolean("qcy_audio_handoff_pending", false).apply()
                lastConnected = false
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForegroundServiceNotification()
        val aclFilter = android.content.IntentFilter().apply {
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
        }
        try { ContextCompat.registerReceiver(this, aclReceiver, aclFilter, ContextCompat.RECEIVER_EXPORTED) } catch (_: SecurityException) { }
        controller = QcyBleController(this) { state ->
            latestState = state
            publishState(state)
            updateLiveNotification(state)
            if (state.connected) getSharedPreferences("sonicflow", MODE_PRIVATE).edit().putBoolean("qcy_connected_here", true).apply()
            if (state.connected && !lastConnected) {
                val prefs = getSharedPreferences("sonicflow", MODE_PRIVATE)
                val customName = prefs.getString("popup_display_name", "")?.trim().orEmpty()
                val displayName = customName.ifBlank { state.deviceName }
                if (prefs.getBoolean("connection_popup", true)) showConnectionPopup(displayName, state.battery, state.charging)
            }
            if (state.connected) updatePopupBattery(state.battery, state.charging)
            lastConnected = state.connected
        }
        controller.startAutoConnect()
        handler.post(autoGameModeMonitor)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf(); return START_NOT_STICKY
        }
        when (intent?.action) {
            ACTION_SCAN -> controller.connectKnownOrScan()
            ACTION_SET_MODE -> controller.setNoiseMode(intent.getIntExtra("mode", 2), intent.getIntExtra("scene", 0), intent.getIntExtra("strength", 0))
            ACTION_SET_GAME_MODE -> controller.setGameMode(intent.getBooleanExtra("enabled", false))
            ACTION_REFRESH_LIVE_NOTIFICATION -> { createNotificationChannels(); updateLiveNotification(latestState) }
            ACTION_HIDE_POPUP -> { removePopup(); removeStatusCard(); updateLiveNotification(latestState) }
        }
        publishState(latestState)
        return START_STICKY
    }

    private fun publishState(state: QcyBleController.State) {
        sendBroadcast(Intent(ACTION_STATE).setPackage(packageName)
            .putExtra("connected", state.connected).putExtra("searching", state.searching)
            .putExtra("message", state.message).putExtra("battery", state.battery ?: -1)
            .putExtra("charging", state.charging).putExtra("ancMode", state.ancMode ?: -1)
            .putExtra("ancScene", state.ancScene).putExtra("ancStrength", state.ancStrength)
            .putExtra("gameMode", state.gameMode ?: false).putExtra("deviceName", state.deviceName))
    }

    private fun startForegroundServiceNotification() {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, CHANNEL_SERVICE)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("SonicFlow ativo")
            .setContentText("Aguardando conexão do fone")
            .setContentIntent(open).setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
        if (Build.VERSION.SDK_INT >= 29) {
            ServiceCompat.startForeground(this, SERVICE_NOTIFICATION_ID, notification, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE)
        } else startForeground(SERVICE_NOTIFICATION_ID, notification)
    }

    private fun updateLiveNotification(state: QcyBleController.State) {
        val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        val enabled = getSharedPreferences("sonicflow", MODE_PRIVATE).getBoolean("live_notifications", false)
        if (!enabled || !state.connected || MainActivity.appIsVisible) {
            manager.cancel(LIVE_NOTIFICATION_ID)
            return
        }
        val productImage = liveNotificationImage
        if (productImage == null && !liveNotificationImageLoading) {
            liveNotificationImageLoading = true
            val request = ImageRequest.Builder(this)
                .data(H3_WHITE_IMAGE)
                .allowHardware(false)
                .target(onSuccess = { drawable ->
                    liveNotificationImageLoading = false
                    val bitmap = (drawable as? BitmapDrawable)?.bitmap ?: run {
                        val rendered = Bitmap.createBitmap(drawable.intrinsicWidth.coerceAtLeast(1), drawable.intrinsicHeight.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
                        drawable.setBounds(0, 0, rendered.width, rendered.height)
                        drawable.draw(Canvas(rendered))
                        rendered
                    }
                    val maxSide = (256 * resources.displayMetrics.density).toInt().coerceAtLeast(1)
                    val scale = minOf(1f, maxSide.toFloat() / maxOf(bitmap.width, bitmap.height))
                    liveNotificationImage = if (scale < 1f) Bitmap.createScaledBitmap(
                        bitmap,
                        (bitmap.width * scale).toInt().coerceAtLeast(1),
                        (bitmap.height * scale).toInt().coerceAtLeast(1),
                        true
                    ) else bitmap
                    if (latestState.connected && getSharedPreferences("sonicflow", MODE_PRIVATE).getBoolean("live_notifications", false)) {
                        updateLiveNotification(latestState)
                    }
                }, onError = {
                    liveNotificationImageLoading = false
                })
                .build()
            imageLoader.enqueue(request)
        }
        val mode = when (state.ancMode) {
            1 -> "ANC"
            3 -> SonicLocale.text(this, "Transparência")
            else -> SonicLocale.text(this, "Desligado")
        }
        val name = getSharedPreferences("sonicflow", MODE_PRIVATE).getString("popup_display_name", "")?.trim().orEmpty().ifBlank { state.deviceName }
        fun modeAction(label: String, value: Int, scene: Int = 0, strength: Int = 0): NotificationCompat.Action {
            val intent = Intent(this, ConnectionPopupService::class.java).setAction(ACTION_SET_MODE)
                .putExtra("mode", value).putExtra("scene", scene).putExtra("strength", strength)
            val pending = PendingIntent.getService(this, 3600 + value, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            return NotificationCompat.Action.Builder(android.R.drawable.ic_media_play, label, pending).build()
        }
        val prefs = getSharedPreferences("sonicflow", MODE_PRIVATE)
        val liveColor = if (prefs.getBoolean("live_notifications_material_you", false) && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val dark = (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES
            if (dark) dynamicDarkColorScheme(this).primary.toArgb() else dynamicLightColorScheme(this).primary.toArgb()
        } else Color.rgb(126, 87, 194)
        val openApp = PendingIntent.getActivity(
            this,
            LIVE_NOTIFICATION_ID,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val notification = NotificationCompat.Builder(this, CHANNEL_LIVE)
            .setSmallIcon(R.drawable.ic_headphones_notification)
            .setColor(liveColor)
            .setContentTitle(name)
            .setContentText("${SonicLocale.text(this, "Conectado")} • $mode")
            .setContentIntent(openApp)
            .setSubText(SonicLocale.text(this, "SonicFlow"))
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setShowWhen(false)
            .setRequestPromotedOngoing(true)
            .setShortCriticalText(mode)
            .apply { if (productImage != null) setLargeIcon(productImage) }
            .setStyle(NotificationCompat.BigTextStyle().bigText("${SonicLocale.text(this, "Fone conectado")}. ${SonicLocale.text(this, "Cancelamento de ruído")}: $mode."))
            .addAction(modeAction(SonicLocale.text(this, "Desligado"), 2))
            .addAction(modeAction("ANC", 1, state.ancScene, state.ancStrength))
            .addAction(modeAction(SonicLocale.text(this, "Transparência"), 3, 1, 4))
            .build()
        manager.notify(LIVE_NOTIFICATION_ID, notification)
    }

    private fun showConnectionPopup(name: String, battery: Int?, charging: Boolean) {
        if (isHomeLauncherForeground()) showPopup(name, battery, charging)
        else showStatusCard(name, battery, charging)
    }

    private fun isHomeLauncherForeground(): Boolean {
        if (MainActivity.appIsVisible || !hasUsageAccess()) return false
        val now = System.currentTimeMillis()
        val events = (getSystemService(USAGE_STATS_SERVICE) as? UsageStatsManager)?.queryEvents(now - 20_000L, now) ?: return false
        val event = android.app.usage.UsageEvents.Event()
        var foregroundPackage: String? = null
        var latestTime = 0L
        while (events.hasNextEvent()) {
            events.getNextEvent(event)
            val foreground = if (Build.VERSION.SDK_INT >= 29) event.eventType == android.app.usage.UsageEvents.Event.ACTIVITY_RESUMED
                else @Suppress("DEPRECATION") (event.eventType == android.app.usage.UsageEvents.Event.MOVE_TO_FOREGROUND)
            if (foreground && event.timeStamp >= latestTime) {
                latestTime = event.timeStamp
                foregroundPackage = event.packageName
            }
        }
        val homeIntent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val homePackages = packageManager.queryIntentActivities(homeIntent, 0).map { it.activityInfo.packageName }.toSet()
        return foregroundPackage in homePackages
    }

    private fun showPopup(name: String, battery: Int?, charging: Boolean) {
        if (!Settings.canDrawOverlays(this)) {
            val notification = NotificationCompat.Builder(this, CHANNEL_POPUP)
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle(name).setContentText("Conectado • bateria ${battery?.let { "$it%" } ?: "—"}")
                .setAutoCancel(true).setTimeoutAfter(7_000).setPriority(NotificationCompat.PRIORITY_MAX)
                .setCategory(NotificationCompat.CATEGORY_EVENT).build()
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(POPUP_NOTIFICATION_ID, notification)
            return
        }
        handler.post {
            removePopup()
            val density = resources.displayMetrics.density
            fun dp(v: Int) = (v * density).toInt()
            val darkTheme = isSystemDarkTheme()
            val cardColor = if (darkTheme) Color.rgb(35, 35, 42) else Color.WHITE
            val textColor = if (darkTheme) Color.WHITE else Color.BLACK
            val cardWidth = (resources.displayMetrics.widthPixels / density - 28f).toInt().coerceIn(300, 440)
            val card = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setPadding(dp(18), dp(20), dp(18), dp(20))
                background = GradientDrawable().apply { setColor(cardColor); cornerRadius = dp(30).toFloat() }
                elevation = dp(14).toFloat()
            }
            val title = TextView(this).apply {
                text = name; setTextColor(textColor); textSize = 22f; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                gravity = Gravity.CENTER; maxLines = 1; ellipsize = android.text.TextUtils.TruncateAt.END
            }
            val image = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "Imagem do $name"
                alpha = 1f
                imageAlpha = 255
                setColorFilter(null)
                setBackgroundColor(Color.TRANSPARENT)
                setLayerType(android.view.View.LAYER_TYPE_HARDWARE, null)
            }
            val meter = BatteryOverlayView(this).apply { setBattery(battery, charging) }
            title.maxLines = 4
            title.setPadding(0, dp(3), 0, dp(3))
            card.addView(title, LinearLayout.LayoutParams(cardWidth - dp(36), WindowManager.LayoutParams.WRAP_CONTENT))
            card.addView(image, LinearLayout.LayoutParams(cardWidth - dp(28), dp(292)))
            card.addView(meter, LinearLayout.LayoutParams(cardWidth - dp(36), dp(64)))
            val request = ImageRequest.Builder(this).data(H3_WHITE_IMAGE).allowHardware(false).crossfade(false).target(image).build()
            ImageLoader(this).enqueue(request)
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(dp(cardWidth), WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                y = dp(30)
            }
            try {
                windowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                windowManager?.addView(card, params)
                popup = card; popupParams = params
                card.alpha = 0f
                card.translationY = dp(90).toFloat()
                card.animate().alpha(1f).translationY(0f).setDuration(520)
                    .setInterpolator(android.view.animation.DecelerateInterpolator(1.6f)).start()
                handler.removeCallbacks(hidePopup); handler.postDelayed(hidePopup, 7_000)
            } catch (_: Exception) { }
        }
    }

    private fun updatePopupBattery(level: Int?, charging: Boolean) {
        handler.post {
            (popup?.getChildAt(2) as? BatteryOverlayView)?.setBattery(level, charging)
            (statusCard?.getChildAt(2) as? BatteryOverlayView)?.setBattery(level, charging)
        }
    }

    private fun showStatusCard(name: String, battery: Int?, charging: Boolean) {
        handler.post {
            removeStatusCard()
            if (!Settings.canDrawOverlays(this)) {
                val fallback = NotificationCompat.Builder(this, CHANNEL_POPUP)
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle(name).setContentText("Conectado • ${battery?.let { "$it%" } ?: "—"}").setAutoCancel(true).setTimeoutAfter(5_000)
                    .setPriority(NotificationCompat.PRIORITY_MAX).setCategory(NotificationCompat.CATEGORY_EVENT).build()
                (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).notify(STATUS_CARD_NOTIFICATION_ID, fallback)
                return@post
            }
            val density = resources.displayMetrics.density
            fun dp(value: Int) = (value * density).toInt()
            val capsule = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(14), dp(10), dp(14), dp(10))
                background = GradientDrawable().apply {
                    setColor(if (isSystemDarkTheme()) Color.rgb(39, 39, 46) else Color.WHITE)
                    cornerRadius = dp(32).toFloat()
                    setStroke(dp(1), if (isSystemDarkTheme()) Color.rgb(90, 90, 102) else Color.rgb(220, 220, 226))
                }
                elevation = dp(12).toFloat()
            }
            val icon = ImageView(this).apply {
                scaleType = ImageView.ScaleType.FIT_CENTER
                contentDescription = "QCY H3"
            }
            val text = TextView(this).apply {
                this.text = name
                setTextColor(if (isSystemDarkTheme()) Color.WHITE else Color.BLACK)
                textSize = 14f
                typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
                maxLines = 2
                setPadding(dp(10), 0, 0, 0)
                maxWidth = dp(190)
            }
            val meter = BatteryOverlayView(this, compact = true).apply { setBattery(battery, charging) }
            capsule.addView(icon, LinearLayout.LayoutParams(dp(38), dp(38)))
            capsule.addView(text, LinearLayout.LayoutParams(0, WindowManager.LayoutParams.WRAP_CONTENT, 1f))
            capsule.addView(meter, LinearLayout.LayoutParams(dp(46), dp(46)))
            ImageLoader(this).enqueue(ImageRequest.Builder(this).data(H3_WHITE_IMAGE).allowHardware(false).crossfade(false).target(icon).build())
            val statusBarHeightId = resources.getIdentifier("status_bar_height", "dimen", "android")
            val statusBarHeight = if (statusBarHeightId != 0) resources.getDimensionPixelSize(statusBarHeightId) else dp(24)
            val type = if (Build.VERSION.SDK_INT >= 26) WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY else WindowManager.LayoutParams.TYPE_PHONE
            val params = WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, type,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT).apply {
                gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
                y = statusBarHeight + dp(2)
            }
            try {
                statusCardWindowManager = getSystemService(WINDOW_SERVICE) as WindowManager
                statusCardWindowManager?.addView(capsule, params)
                statusCard = capsule
                capsule.alpha = 0f
                capsule.translationY = -dp(18).toFloat()
                capsule.animate().alpha(1f).translationY(0f).setDuration(280)
                    .setInterpolator(android.view.animation.DecelerateInterpolator()).start()
                handler.removeCallbacks(hideStatusCard)
                handler.postDelayed(hideStatusCard, 5_000)
            } catch (_: Exception) { }
        }
    }

    private val hideStatusCard = Runnable { removeStatusCard() }

    private fun removeStatusCard() {
        handler.removeCallbacks(hideStatusCard)
        try { statusCard?.let { statusCardWindowManager?.removeView(it) } } catch (_: Exception) { }
        statusCard = null
    }

    private fun isSystemDarkTheme(): Boolean =
        (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES

    private fun removePopup() {
        handler.removeCallbacks(hidePopup)
        try { popup?.let { windowManager?.removeView(it) } } catch (_: Exception) { }
        popup = null; popupParams = null
    }

    private fun createNotificationChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val manager = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(NotificationChannel(CHANNEL_SERVICE, "Monitoramento Bluetooth", NotificationManager.IMPORTANCE_LOW))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_POPUP, "Conexão dos fones", NotificationManager.IMPORTANCE_HIGH))
            manager.createNotificationChannel(NotificationChannel(CHANNEL_LIVE, SonicLocale.text(this, "Live notifications"), NotificationManager.IMPORTANCE_DEFAULT))
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoGameModeMonitor)
        removePopup()
        removeStatusCard()
        try { unregisterReceiver(aclReceiver) } catch (_: IllegalArgumentException) { }
        if (::controller.isInitialized) controller.close()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private class BatteryOverlayView(context: android.content.Context, private val compact: Boolean = false) : android.view.View(context) {
        private val ring = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { style = android.graphics.Paint.Style.STROKE; strokeCap = android.graphics.Paint.Cap.ROUND }
        private val label = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { textSize = 13f * resources.displayMetrics.density; textAlign = android.graphics.Paint.Align.CENTER; typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL) }
        private var level: Int? = null
        private var charging = false
        fun setBattery(value: Int?, isCharging: Boolean) { level = value; charging = isCharging; invalidate() }
        override fun onDraw(canvas: android.graphics.Canvas) {
            super.onDraw(canvas)
            val density = resources.displayMetrics.density
            val color = when {
                level == null -> if ((resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) == android.content.res.Configuration.UI_MODE_NIGHT_YES) Color.WHITE else Color.BLACK
                level!! <= 15 -> Color.rgb(255, 59, 48)
                else -> Color.rgb(48, 209, 88)
            }
            val cx = width / 2f
            val cy = height / 2f - if (compact) 0f else 5 * density
            val radius = (if (compact) 18f else 22f) * density
            val bounds = android.graphics.RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            ring.strokeWidth = (if (compact) 4f else 5f) * density; ring.color = Color.rgb(112,112,122)
            canvas.drawArc(bounds, 135f, 270f, false, ring)
            ring.color = color
            level?.let { canvas.drawArc(bounds, 135f, 270f * it / 100f, false, ring) }
            label.color = color
            label.alpha = 255
            label.textSize = (if (compact) 13f else 15f) * density
            canvas.drawText(level?.let { "$it%" } ?: "—", cx, cy + label.textSize * .36f, label)
        }
    }

    companion object {
        const val ACTION_STOP = "com.sonicflow.action.STOP_POPUP_SERVICE"
        const val ACTION_SCAN = "com.sonicflow.action.SCAN"
        const val ACTION_SET_MODE = "com.sonicflow.action.SET_MODE"
        const val ACTION_SET_GAME_MODE = "com.sonicflow.action.SET_GAME_MODE"
        const val ACTION_GET_STATE = "com.sonicflow.action.GET_STATE"
        const val ACTION_HIDE_POPUP = "com.sonicflow.action.HIDE_POPUP"
        const val ACTION_STATE = "com.sonicflow.action.STATE"
        const val ACTION_REFRESH_LIVE_NOTIFICATION = "com.sonicflow.action.REFRESH_LIVE_NOTIFICATION"
        const val CHANNEL_SERVICE = "sonicflow_monitor"
        const val CHANNEL_POPUP = "sonicflow_popup"
        const val CHANNEL_LIVE = "sonicflow_live"
        const val SERVICE_NOTIFICATION_ID = 3501
        const val POPUP_NOTIFICATION_ID = 3502
        const val STATUS_CARD_NOTIFICATION_ID = 3503
        const val LIVE_NOTIFICATION_ID = 3504
        const val H3_WHITE_IMAGE = "https://www.qcy.com/cdn/shop/files/H3_-1.png?v=1779672583&width=800"
    }
}
