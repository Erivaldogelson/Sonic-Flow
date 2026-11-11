package com.sonicflow

import android.Manifest
import android.content.IntentFilter
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.window.Dialog
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.togetherWith
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.saveable.rememberSaveable
import coil.compose.AsyncImage
import androidx.core.view.WindowCompat
import java.util.Locale
import kotlinx.coroutines.delay

private const val H3_WHITE_IMAGE = "https://www.qcy.com/cdn/shop/files/H3_-1.png?v=1779672583&width=800"

class MainActivity : ComponentActivity() {
    override fun onStart() {
        super.onStart()
        appIsVisible = true
        if (Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            ContextCompat.startForegroundService(this, Intent(this, ConnectionPopupService::class.java).setAction(ConnectionPopupService.ACTION_HIDE_POPUP))
        }
    }

    override fun onStop() {
        appIsVisible = false
        super.onStop()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        setContent {
            val systemDark = androidx.compose.foundation.isSystemInDarkTheme()
            val context = androidx.compose.ui.platform.LocalContext.current
            val colors = when {
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && systemDark -> dynamicDarkColorScheme(context)
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> dynamicLightColorScheme(context)
                systemDark -> darkColorScheme(primary = Color(0xFFD0BCFF), secondary = Color(0xFFCCC2DC))
                else -> lightColorScheme(primary = Color(0xFF6557B8), secondary = Color(0xFF6750A4))
            }
            SideEffect {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                window.decorView.setBackgroundColor(android.graphics.Color.TRANSPARENT)
                window.setBackgroundDrawableResource(android.R.color.transparent)
                if (Build.VERSION.SDK_INT >= 29) {
                    window.isStatusBarContrastEnforced = false
                    window.isNavigationBarContrastEnforced = false
                }
                WindowCompat.getInsetsController(window, window.decorView).apply {
                    isAppearanceLightStatusBars = !systemDark
                    isAppearanceLightNavigationBars = !systemDark
                }
            }
            MaterialTheme(colorScheme = colors) { SonicFlowHome() }
        }
    }

    override fun onResume() {
        super.onResume()
        val service = Intent(this, ConnectionPopupService::class.java)
        if (Build.VERSION.SDK_INT < 31 || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED) {
            ContextCompat.startForegroundService(this, service)
        }
    }

    companion object {
        @Volatile var appIsVisible: Boolean = false
            private set
    }
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun SonicFlowHome() {
    val context = LocalContext.current
    var bluetoothState by remember { mutableStateOf(QcyBleController.State()) }
    val equalizer = remember(context) { AndroidEqualizer(context) }
    DisposableEffect(equalizer) { onDispose { equalizer.close() } }
    var sceneMenu by remember { mutableStateOf(false) }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val screenScrollStates = remember {
        listOf(androidx.compose.foundation.ScrollState(0), androidx.compose.foundation.ScrollState(0), androidx.compose.foundation.ScrollState(0))
    }
    val pageScrollState = screenScrollStates[tab]
    val preferences = remember(context) { context.getSharedPreferences("sonicflow", android.content.Context.MODE_PRIVATE) }
    var popupEnabled by remember { mutableStateOf(preferences.getBoolean("connection_popup", true)) }
    var autoGameModeEnabled by remember { mutableStateOf(preferences.getBoolean("auto_game_mode", false)) }
    var popupDisplayName by remember { mutableStateOf(preferences.getString("popup_display_name", "") ?: "") }
    var chosenLanguage by remember { mutableStateOf(SonicLocale.selected(context)) }
    var showLanguagePicker by remember { mutableStateOf(false) }
    var showAboutMe by remember { mutableStateOf(false) }
    val permissionSettingsLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { }
    fun tx(value: String) = SonicLocale.text(context, value)
    LaunchedEffect(popupEnabled) { preferences.edit().putBoolean("connection_popup", popupEnabled).apply() }
    DisposableEffect(context) {
        val stateReceiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(c: android.content.Context?, intent: Intent?) {
                if (intent?.action != ConnectionPopupService.ACTION_STATE) return
                bluetoothState = QcyBleController.State(
                    searching = intent.getBooleanExtra("searching", false), connected = intent.getBooleanExtra("connected", false),
                    message = intent.getStringExtra("message") ?: "Pronto para conectar",
                    battery = intent.getIntExtra("battery", -1).takeIf { it >= 0 }, charging = intent.getBooleanExtra("charging", false),
                    ancMode = intent.getIntExtra("ancMode", -1).takeIf { it >= 0 }, ancScene = intent.getIntExtra("ancScene", 3),
                    ancStrength = intent.getIntExtra("ancStrength", 2), deviceName = intent.getStringExtra("deviceName") ?: "QCY H3",
                    gameMode = if (intent.hasExtra("gameMode")) intent.getBooleanExtra("gameMode", false) else null
                )
            }
        }
        ContextCompat.registerReceiver(context, stateReceiver, IntentFilter(ConnectionPopupService.ACTION_STATE), ContextCompat.RECEIVER_NOT_EXPORTED)
        ContextCompat.startForegroundService(context, Intent(context, ConnectionPopupService::class.java).setAction(ConnectionPopupService.ACTION_GET_STATE))
        onDispose { try { context.unregisterReceiver(stateReceiver) } catch (_: IllegalArgumentException) { } }
    }

    val permissions = if (Build.VERSION.SDK_INT >= 31) {
        arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.all { it }) ContextCompat.startForegroundService(context, Intent(context, ConnectionPopupService::class.java).setAction(ConnectionPopupService.ACTION_SCAN))
        else bluetoothState = bluetoothState.copy(message = "Permita o Bluetooth para procurar e controlar o fone")
    }
    LaunchedEffect(Unit) {
        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) ContextCompat.startForegroundService(context, Intent(context, ConnectionPopupService::class.java))
        else permissionLauncher.launch(permissions)
    }

    val surfaceColor = MaterialTheme.colorScheme.background
    Box(Modifier.fillMaxSize().background(surfaceColor)) {
      val edgeScrim = if (androidx.compose.foundation.isSystemInDarkTheme()) Color.Black.copy(alpha = .28f) else Color.Black.copy(alpha = .08f)
      Column(Modifier.fillMaxSize().statusBarsPadding()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
        AnimatedContent(
            targetState = tab,
            modifier = Modifier.fillMaxSize(),
            transitionSpec = {
                (slideInVertically(
                    animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow),
                    initialOffsetY = { height -> height / 12 }
                ) + fadeIn(tween(180))) togetherWith
                    (slideOutVertically(tween(120), targetOffsetY = { height -> -height / 16 }) + fadeOut(tween(100)))
            },
            label = "expressiveScreenTransition"
        ) { destination ->
        val destinationScrollState = screenScrollStates[destination]
        if (destination == 2) {
            Column(Modifier.fillMaxSize().verticalScroll(destinationScrollState).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ScreenHeader(tx("Ajustes"))
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tx("Nome no pop-up"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                        Text(tx("Deixe vazio para usar o nome salvo no Bluetooth."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        OutlinedTextField(
                            value = popupDisplayName,
                            onValueChange = { value -> popupDisplayName = value; preferences.edit().putString("popup_display_name", value).apply() },
                            modifier = Modifier.fillMaxWidth(), singleLine = true,
                            placeholder = { Text(bluetoothState.deviceName) },
                            label = { Text(tx("Nome exibido")) }, shape = RoundedCornerShape(18.dp)
                        )
                    }
                }
                Text(tx("Idioma"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                Card(onClick = { showLanguagePicker = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Language, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(tx("Idioma"), fontWeight = FontWeight.SemiBold); Text(SonicLocale.languages.first { it.id == chosenLanguage }.label, color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 13.sp) }
                        Icon(Icons.Rounded.ChevronRight, null)
                    }
                }
                Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(tx("Permissões"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        val runtimePerms = buildList {
                            if (Build.VERSION.SDK_INT >= 31) { add(Manifest.permission.BLUETOOTH_CONNECT); add(Manifest.permission.BLUETOOTH_SCAN) }
                            else add(Manifest.permission.ACCESS_FINE_LOCATION)
                            if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                        }
                        val bluetoothGranted = runtimePerms.filter { it != Manifest.permission.POST_NOTIFICATIONS }.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
                        val notifGranted = Build.VERSION.SDK_INT < 33 || ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
                        PermissionRow(tx("Bluetooth e dispositivos próximos"), bluetoothGranted, tx("Concedida"), tx("Não concedida")) {
                            permissionSettingsLauncher.launch(runtimePerms.filter { it != Manifest.permission.POST_NOTIFICATIONS }.toTypedArray())
                        }
                        if (Build.VERSION.SDK_INT >= 33) PermissionRow(tx("Notificações"), notifGranted, tx("Concedida"), tx("Não concedida")) {
                            permissionSettingsLauncher.launch(arrayOf(Manifest.permission.POST_NOTIFICATIONS))
                        }
                        PermissionRow(tx("Sobre outros apps"), Settings.canDrawOverlays(context), tx("Concedida"), tx("Não concedida")) {
                            context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        }
                        PermissionRow(tx("Ajustar áudio"), ContextCompat.checkSelfPermission(context, Manifest.permission.MODIFY_AUDIO_SETTINGS) == PackageManager.PERMISSION_GRANTED, tx("Concedida"), tx("Não concedida")) {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }
                        PermissionRow(tx("Acesso à internet e serviço em segundo plano"), true, tx("Concedida"), tx("Não concedida")) {
                            context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}")))
                        }
                    }
                }
                Card(onClick = { showAboutMe = true }, colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Person, null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Text(tx("Sobre mim"), Modifier.weight(1f), fontWeight = FontWeight.SemiBold)
                        Icon(Icons.Rounded.ChevronRight, null)
                    }
                }
            }
        } else if (destination == 1) {
            EqualizerScreen(equalizer, Modifier.fillMaxSize(), destinationScrollState)
        } else {
            Column(Modifier.fillMaxSize().verticalScroll(destinationScrollState).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                ScreenHeader(tx("Meus fones"))
                Box(Modifier.fillMaxWidth().height(190.dp), contentAlignment = Alignment.Center) {
                    AsyncImage(
                        model = H3_WHITE_IMAGE,
                        contentDescription = "QCY H3 branco em vista 3D",
                        modifier = Modifier.size(168.dp).graphicsLayer { alpha = if (bluetoothState.connected) 1f else .58f },
                        contentScale = ContentScale.Fit,
                        colorFilter = if (bluetoothState.connected) null else androidx.compose.ui.graphics.ColorFilter.colorMatrix(
                            androidx.compose.ui.graphics.ColorMatrix().apply { setToSaturation(0f) }
                        )
                    )
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(popupDisplayName.trim().ifBlank { bluetoothState.deviceName }, fontSize = 23.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
                        Text(if (!bluetoothState.connected && !bluetoothState.searching) tx("Desconectado") else tx(bluetoothState.message), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Button(modifier = Modifier.animateContentSize(), onClick = {
                        if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) ContextCompat.startForegroundService(context, Intent(context, ConnectionPopupService::class.java).setAction(ConnectionPopupService.ACTION_SCAN))
                        else permissionLauncher.launch(permissions)
                    }, shape = RoundedCornerShape(18.dp), enabled = !bluetoothState.searching) {
                        AnimatedContent(targetState = bluetoothState.searching, label = "connectIcon") { searching ->
                            if (searching) CircularProgressIndicator(Modifier.size(18.dp), strokeWidth = 2.dp)
                            else Icon(Icons.Rounded.BluetoothSearching, null)
                        }
                        Spacer(Modifier.width(6.dp)); Text(if (bluetoothState.searching) "Buscando" else if (bluetoothState.connected) "Reconectar" else "Conectar")
                    }
                }

                BatteryIndicator(bluetoothState.battery, bluetoothState.charging, bluetoothState.connected, Modifier.fillMaxWidth().height(48.dp))

                Text(tx("Cancelamento de ruído"), fontSize = 19.sp, fontWeight = FontWeight.Bold, color = if (bluetoothState.connected) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.outline)
                Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                            NoiseModeButton(tx("Desligado"), Icons.Rounded.VolumeOff, selected = bluetoothState.connected && bluetoothState.ancMode == 2, modifier = Modifier.weight(1f), enabled = bluetoothState.connected) { sendMode(context, 2) }
                            NoiseModeButton("ANC", Icons.Rounded.GraphicEq, selected = bluetoothState.connected && bluetoothState.ancMode == 1, modifier = Modifier.weight(1f), enabled = bluetoothState.connected) { sendMode(context, 1, bluetoothState.ancScene, bluetoothState.ancStrength) }
                            NoiseModeButton(tx("Transparência"), Icons.Rounded.Hearing, selected = bluetoothState.connected && bluetoothState.ancMode == 3, modifier = Modifier.weight(1.2f), enabled = bluetoothState.connected) { sendMode(context, 3, 1, 4) }
                        }
                        if (bluetoothState.connected && bluetoothState.ancMode == 1) {
                            Box {
                                OutlinedButton(onClick = { sceneMenu = true }, modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                                    Text("Cena ANC: ${sceneLabel(bluetoothState.ancScene)}"); Spacer(Modifier.weight(1f)); Icon(Icons.Rounded.ExpandMore, null)
                                }
                                DropdownMenu(expanded = sceneMenu, onDismissRequest = { sceneMenu = false }) {
                                    listOf(1 to "Redução de ruído", 2 to "Deslocamento", 3 to "Ambiente interno", 4 to "Redução de vento", 5 to "Adaptativo").forEach { (scene, label) ->
                                        DropdownMenuItem(text = { Text(label) }, onClick = {
                                            sceneMenu = false
                                            val value = if (scene in 1..3) bluetoothState.ancStrength else 0
                                            sendMode(context, 1, scene, value)
                                        })
                                    }
                                }
                            }
                            if (bluetoothState.ancScene in 1..3) {
                                Text("Intensidade ${bluetoothState.ancStrength + 1}/3", style = MaterialTheme.typography.labelLarge)
                                var pendingStrength by remember(bluetoothState.ancScene, bluetoothState.ancStrength) { mutableFloatStateOf(bluetoothState.ancStrength.toFloat()) }
                                Slider(value = pendingStrength, onValueChange = { pendingStrength = it }, onValueChangeFinished = {
                                    sendMode(context, 1, bluetoothState.ancScene, pendingStrength.toInt().coerceIn(0, 2))
                                }, valueRange = 0f..2f, steps = 1)
                            }
                        }
                        if (!bluetoothState.connected) Text("Conecte o fone para enviar comandos ANC.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        else Text("Os modos são enviados diretamente pelo Bluetooth LE.", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }

                Card(onClick = { tab = 1 }, shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Row(Modifier.fillMaxWidth().padding(18.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.Equalizer, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) { Text(tx("Equalizador"), fontWeight = FontWeight.SemiBold); Text(tx("Ajuste o som"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow, contentColor = MaterialTheme.colorScheme.onSurface)) {
                        Row(Modifier.fillMaxWidth().clickable {
                            if (!Settings.canDrawOverlays(context)) context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                        }.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Rounded.NotificationsActive, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text("Pop-up", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
                            Text(if (Settings.canDrawOverlays(context)) "Sobre outros apps • parte inferior" else "Toque para permitir sobreposição", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                        Switch(checked = popupEnabled, onCheckedChange = { enabled ->
                            popupEnabled = enabled
                            preferences.edit().putBoolean("connection_popup", enabled).apply()
                            val service = Intent(context, ConnectionPopupService::class.java)
                            if (enabled) {
                                if (!Settings.canDrawOverlays(context)) context.startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:${context.packageName}")))
                                if (permissions.all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }) ContextCompat.startForegroundService(context, service)
                            }
                        })
                    }
                }
                Card(shape = RoundedCornerShape(20.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
                        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.SportsEsports, contentDescription = null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tx("Modo Gamer"), fontWeight = FontWeight.SemiBold)
                                Text(tx("Reduz a latência do áudio"), fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = bluetoothState.gameMode == true, enabled = bluetoothState.connected, onCheckedChange = { enabled ->
                                ContextCompat.startForegroundService(context, Intent(context, ConnectionPopupService::class.java).setAction(ConnectionPopupService.ACTION_SET_GAME_MODE).putExtra("enabled", enabled))
                            })
                        }
                        HorizontalDivider(Modifier.padding(vertical = 4.dp), color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .45f))
                        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.AutoMode, null, tint = MaterialTheme.colorScheme.primary)
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(tx("Ativar automaticamente ao abrir um jogo"), style = MaterialTheme.typography.bodyMedium)
                                Text(tx("Requer acesso aos dados de uso"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            }
                            Switch(checked = autoGameModeEnabled, onCheckedChange = { enabled ->
                                autoGameModeEnabled = enabled
                                preferences.edit().putBoolean("auto_game_mode", enabled).apply()
                                if (enabled) context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                                ContextCompat.startForegroundService(context, Intent(context, ConnectionPopupService::class.java))
                            })
                        }
                    }
                }
                Spacer(Modifier.height(4.dp))
            }
        }
        }
        if (pageScrollState.value > 0) {
            Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(88.dp).background(Brush.verticalGradient(listOf(surfaceColor.copy(alpha = .96f), Color.Transparent))))
        }
        if (pageScrollState.maxValue > 0 && pageScrollState.value < pageScrollState.maxValue) {
            Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(88.dp).background(Brush.verticalGradient(listOf(Color.Transparent, surfaceColor.copy(alpha = .96f)))) )
        }
        }

        Row(
            Modifier.fillMaxWidth(.84f).align(Alignment.CenterHorizontally).navigationBarsPadding()
                .padding(start = 8.dp, top = 6.dp, end = 8.dp, bottom = 8.dp)
                .clip(CircleShape).background(MaterialTheme.colorScheme.surfaceContainerLow).padding(5.dp),
            horizontalArrangement = Arrangement.spacedBy(5.dp)
        ) {
            listOf(Icons.Rounded.Headphones to tx("Fones"), Icons.Rounded.Equalizer to tx("Som"), Icons.Rounded.Settings to tx("Ajustes")).forEachIndexed { index, item ->
                BottomNavPill(item.first, item.second, selected = tab == index, modifier = Modifier.weight(1f)) { tab = index }
            }
        }
      }
      val statusBarInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
      Box(Modifier.align(Alignment.TopCenter).fillMaxWidth().height(statusBarInset).background(Brush.verticalGradient(listOf(edgeScrim, Color.Transparent))))
    }
    if (showLanguagePicker) {
        ModalBottomSheet(onDismissRequest = { showLanguagePicker = false }) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(tx("Escolher idioma"), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                SonicLocale.languages.forEach { language ->
                    val label = if (language.id == "system") tx("Idioma do sistema") else language.label
                    Surface(onClick = {
                        chosenLanguage = language.id
                        preferences.edit().putString("app_language", language.id).apply()
                        showLanguagePicker = false
                    }, shape = RoundedCornerShape(28.dp), color = if (chosenLanguage == language.id) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surfaceContainerLow) {
                        Row(Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                            if (chosenLanguage == language.id) Icon(Icons.Rounded.Check, null, tint = MaterialTheme.colorScheme.onSecondaryContainer)
                        }
                    }
                }
                Spacer(Modifier.height(20.dp))
            }
        }
    }
    if (showAboutMe) {
        ModalBottomSheet(onDismissRequest = { showAboutMe = false }) {
            Column(
                Modifier.fillMaxWidth().heightIn(max = 680.dp).verticalScroll(rememberScrollState()).padding(horizontal = 20.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Text(tx("Sobre mim"), modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text("Erivaldo Gelson da Rocha João", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(tx("Minhas redes sociais"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 12.dp))
                AboutAction(Icons.Rounded.CameraAlt, "Instagram", "@erivaldo_gelson") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.instagram.com/erivaldo_gelson?vrfl=MWY5bWRqenNybnpzZg==")))
                }
                AboutAction(Icons.Rounded.AlternateEmail, "Threads", "@erivaldo_gelson") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.threads.com/@erivaldo_gelson")))
                }
                AboutAction(Icons.Rounded.Code, "GitHub", "Erivaldogelson") {
                    context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://github.com/Erivaldogelson")))
                }
                Text(tx("Ajude a investir neste e em novos projetos"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp))
                Text(tx("Toque nos dados para copiá-los."), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                DonationAction(Icons.Rounded.AccountBalanceWallet, tx("PIX • Brasil"), "Erivaldojelson8@gmail.com") { copyDonation(context, "PIX", "Erivaldojelson8@gmail.com") }
                DonationAction(Icons.Rounded.AccountBalance, tx("IBAN • Europa"), "LT973250031675568513") { copyDonation(context, "IBAN", "LT973250031675568513") }
                DonationAction(Icons.Rounded.Public, tx("SWIFT • Estados Unidos"), "REVOSGS2") { copyDonation(context, "SWIFT", "REVOSGS2") }
                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun AboutAction(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.Rounded.OpenInNew, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun DonationAction(icon: ImageVector, title: String, value: String, onClick: () -> Unit) {
    Surface(onClick = onClick, shape = RoundedCornerShape(24.dp), color = MaterialTheme.colorScheme.surfaceContainerLow) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.primary); Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.SemiBold); Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
            Icon(Icons.Rounded.ContentCopy, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

private fun copyDonation(context: android.content.Context, label: String, value: String) {
    val clipboard = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    clipboard.setPrimaryClip(android.content.ClipData.newPlainText(label, value))
    android.widget.Toast.makeText(context, "$label copiado", android.widget.Toast.LENGTH_SHORT).show()
}

@Composable
private fun PermissionRow(title: String, granted: Boolean, grantedText: String, deniedText: String, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(if (granted) Icons.Rounded.CheckCircle else Icons.Rounded.ErrorOutline, null, tint = if (granted) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Medium); Text(if (granted) grantedText else deniedText, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun ScreenHeader(title: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 16.dp)) {
        if (title != "Equalizador") Text("SONICFLOW", fontSize = 12.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary, letterSpacing = 2.sp)
        Text(title, fontSize = 27.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onBackground)
    }
}

private fun sendMode(context: android.content.Context, mode: Int, scene: Int = 0, strength: Int = 0) {
    ContextCompat.startForegroundService(context, Intent(context, ConnectionPopupService::class.java).setAction(ConnectionPopupService.ACTION_SET_MODE).putExtra("mode", mode).putExtra("scene", scene).putExtra("strength", strength))
}

@Composable
private fun BatteryIndicator(battery: Int?, charging: Boolean, connected: Boolean, modifier: Modifier = Modifier) {
    val batteryColor = when {
        !connected -> MaterialTheme.colorScheme.outline
        battery != null && battery <= 15 -> Color(0xFFFF453A)
        charging -> Color(0xFF30D158)
        else -> MaterialTheme.colorScheme.onBackground
    }
    Box(modifier, contentAlignment = Alignment.Center) {
        val trackColor = MaterialTheme.colorScheme.outlineVariant
        Canvas(Modifier.size(40.dp)) {
            val outline = Stroke(width = 5.dp.toPx(), cap = StrokeCap.Round)
            val ring = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round)
            if (connected) drawArc(Color.Black.copy(alpha = .3f), 135f, 270f, false, style = outline)
            drawArc(trackColor, 135f, 270f, false, style = ring)
            if (connected && battery != null) drawArc(batteryColor, 135f, 270f * battery / 100f, false, style = ring)
        }
        if (connected) Text(battery?.let { "$it%" } ?: "—", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = batteryColor, textAlign = TextAlign.Center)
    }
}

@Composable
private fun BottomNavPill(icon: ImageVector, label: String, selected: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Surface(
        modifier = modifier.heightIn(min = 40.dp).clip(shape).clickable(onClick = onClick).animateContentSize(tween(220)),
        shape = shape,
        color = if (selected) scheme.secondaryContainer else Color.Transparent,
        contentColor = if (selected) scheme.onSecondaryContainer else scheme.onSurfaceVariant
    ) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 5.dp, vertical = 8.dp), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(4.dp))
            Text(label, fontSize = 10.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium, maxLines = 1)
        }
    }
}

@Composable
private fun EqualizerScreen(equalizer: AndroidEqualizer, modifier: Modifier = Modifier, scrollState: androidx.compose.foundation.ScrollState) {
    var selectedProfile by rememberSaveable { mutableStateOf("Personalizado") }
    Column(modifier.verticalScroll(scrollState).padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ScreenHeader("Equalizador")
        if (equalizer.error != null) {
            Card(colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)) {
                Text("Equalizador indisponível neste aparelho: ${equalizer.error}", Modifier.padding(16.dp), color = MaterialTheme.colorScheme.onErrorContainer)
            }
        } else if (equalizer.levels.isEmpty()) {
            Text("O sistema não disponibilizou bandas de equalização.", color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text("Perfis de som", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onSurface)
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("Neutro", "Graves", "Voz", "Rock", "Clássico", "Podcast", "Agudos").forEach { preset ->
                    FilterChip(selected = selectedProfile == preset, onClick = {
                        equalizer.applyPreset(preset)
                        selectedProfile = preset
                    }, label = { Text(preset) })
                }
            }
            Card(shape = RoundedCornerShape(24.dp), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow)) {
                Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp)) {
                    equalizer.levels.indices.forEach { index ->
                        val level = equalizer.levels[index]
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(frequencyLabel(equalizer.frequencies[index]), modifier = Modifier.width(62.dp), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Slider(value = level, onValueChange = { equalizer.setBand(index, it); selectedProfile = "Personalizado" }, valueRange = equalizer.minDb..equalizer.maxDb, modifier = Modifier.weight(1f))
                            Text(String.format(Locale.getDefault(), "%+.1f", level), modifier = Modifier.width(46.dp), textAlign = TextAlign.End, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurface)
                        }
                    }
                }
            }
            Text("Os ajustes ficam salvos neste aparelho e aplicados ao áudio do sistema enquanto o SonicFlow estiver aberto.", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(8.dp))
    }
}

private fun frequencyLabel(hz: Int): String = if (hz >= 1000) {
    val khz = hz / 1000f
    if (khz == khz.toInt().toFloat()) "${khz.toInt()} kHz" else String.format(Locale.getDefault(), "%.1f kHz", khz)
} else "$hz Hz"

@Composable
@OptIn(ExperimentalFoundationApi::class)
private fun NoiseModeButton(label: String, icon: ImageVector, selected: Boolean, modifier: Modifier, enabled: Boolean, onClick: () -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val isSelected = selected && enabled
    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) .91f else 1f,
        animationSpec = spring(dampingRatio = .42f, stiffness = Spring.StiffnessHigh),
        label = "modePressScale"
    )
    val cornerRadius by animateDpAsState(if (pressed || isSelected) 24.dp else 16.dp, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium), label = "modeShape")
    val shape = RoundedCornerShape(cornerRadius)
    val iconSize by animateDpAsState(if (isSelected) 20.dp else 17.dp, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium), label = "modeIconSize")
    val selectedBounce by animateFloatAsState(if (isSelected) 1.12f else 1f, animationSpec = spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMediumLow), label = "modeSelectedBounce")
    Surface(
        modifier = modifier.animateContentSize(spring(dampingRatio = Spring.DampingRatioLowBouncy, stiffness = Spring.StiffnessMedium)).graphicsLayer { scaleX = scale; scaleY = scale }.heightIn(min = 52.dp).clip(shape).then(if (isSelected) Modifier.border(1.dp, scheme.primary, shape) else Modifier).combinedClickable(enabled = enabled, interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick, onLongClick = onClick),
        shape = shape,
        color = when { !enabled -> scheme.surfaceContainerLow; isSelected -> scheme.secondaryContainer; else -> scheme.surfaceContainerHigh },
        contentColor = when { !enabled -> scheme.outline; isSelected -> scheme.onSecondaryContainer; else -> scheme.onSurface },
        tonalElevation = if (isSelected) 1.dp else 0.dp
    ) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 9.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center) {
                Icon(icon, contentDescription = null, modifier = Modifier.size(iconSize).graphicsLayer { scaleX = selectedBounce; scaleY = selectedBounce })
                Spacer(Modifier.width(4.dp))
                Text(label, textAlign = TextAlign.Center, fontSize = 11.sp, lineHeight = 14.sp, fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
            }
        }
    }
}

private fun sceneLabel(scene: Int) = when (scene) {
    1 -> "Redução de ruído"
    2 -> "Deslocamento"
    3 -> "Ambiente interno"
    4 -> "Redução de vento"
    else -> "Adaptativo"
}
