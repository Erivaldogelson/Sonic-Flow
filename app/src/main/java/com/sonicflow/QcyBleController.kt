package com.sonicflow

import android.Manifest
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.util.UUID
import java.util.ArrayDeque

/** Bluetooth LE transport for the QCY H3 family (JieLi RCSP). */
internal class QcyBleController(
    private val context: Context,
    private val onState: (State) -> Unit
) {
    data class State(
        val searching: Boolean = false,
        val connected: Boolean = false,
        val message: String = "Pronto para conectar",
        val battery: Int? = null,
        val charging: Boolean = false,
        val ancMode: Int? = null,
        val ancScene: Int = 3,
        val ancStrength: Int = 2,
        val deviceName: String = "QCY H3",
        val gameMode: Boolean? = null
    )

    private var state = State()
    private var scanner: BluetoothLeScanner? = null
    private var expectedClassicAddress: String? = null
    private var gatt: BluetoothGatt? = null
    private var connecting = false
    private var directConnectionFallback = false
    private var monitoringConnections = false
    private var reconnectAttempts = 0
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var seq = 1
    private val writeQueue = ArrayDeque<ByteArray>()
    private var writing = false
    private var pendingWrite: ByteArray? = null
    private var writeRetryCount = 0
    private val handler = Handler(Looper.getMainLooper())
    private val scanTimeout = Runnable { stopScan("Canal BLE do QCY H3 não localizado. Feche o app QCY se estiver aberto e tente novamente.") }
    private val gattSetupTimeout = Runnable {
        if (connecting && !state.connected) {
            if (directConnectionFallback) fallbackToScan()
            else {
                closeGatt()
                update(state.copy(searching = false, connected = false, message = "O canal BLE do QCY H3 não respondeu"))
                scheduleReconnect()
            }
        }
    }
    private val reconnectRunnable = Runnable {
        if (state.connected || connecting || state.searching || reconnectAttempts >= 5) return@Runnable
        val audioConnected = try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            adapter?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
        } catch (_: SecurityException) { false }
        if (audioConnected) {
            reconnectAttempts++
            connectKnownOrScan()
        }
    }

    private fun scheduleReconnect() {
        if (reconnectAttempts >= 5) return
        handler.removeCallbacks(reconnectRunnable)
        handler.postDelayed(reconnectRunnable, 1_500)
    }

    private fun update(next: State) { state = next; handler.post { onState(next) } }

    private val connectionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_ACL_CONNECTED || state.connected || connecting) return
            val device = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                else @Suppress("DEPRECATION") (intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE) as? BluetoothDevice)
            if (device == null) { scan(); return }
            val savedAddress = context?.getSharedPreferences("sonicflow", Context.MODE_PRIVATE)?.getString("qcy_device_address", null)
            val deviceName = try { device.name.orEmpty() } catch (_: SecurityException) { "" }
            val isKnownQcy = device.address == savedAddress || deviceName.contains("qcy", true) || deviceName.contains("h3", true)
            if (!isKnownQcy) { scan(); return }
            connectKnownOrScan()
        }
    }

    private fun ensureConnectionMonitor() {
        if (monitoringConnections) return
        if (Build.VERSION.SDK_INT >= 31 && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) return
        val filter = IntentFilter(BluetoothDevice.ACTION_ACL_CONNECTED)
        try {
            // Register after the runtime Bluetooth permission is granted as well as on service startup.
            ContextCompat.registerReceiver(context, connectionReceiver, filter, ContextCompat.RECEIVER_EXPORTED)
            monitoringConnections = true
        } catch (_: SecurityException) { }
    }

    fun startAutoConnect() {
        if (Build.VERSION.SDK_INT >= 31 && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            update(state.copy(message = "Permissão de conexão Bluetooth necessária")); return
        }
        ensureConnectionMonitor()
        connectKnownOrScan()
    }

    /** Try the already paired/active H3 first; its A2DP address may differ from its BLE address. */
    fun connectKnownOrScan() {
        if (Build.VERSION.SDK_INT >= 31 && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            update(state.copy(message = "Permissão de conexão Bluetooth necessária")); return
        }
        ensureConnectionMonitor()
        try {
            val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
            val profileConnected = adapter?.getProfileConnectionState(BluetoothProfile.A2DP) == BluetoothProfile.STATE_CONNECTED
            val prefs = context.getSharedPreferences("sonicflow", Context.MODE_PRIVATE)
            val savedAddress = prefs.getString("qcy_device_address", null)
            // App updates keep the paired device and the BLE address in Android's bond store.
            // Reuse the remembered LE endpoint first; this avoids requiring a fresh pairing.
            if (!profileConnected && !savedAddress.isNullOrBlank()) {
                val savedBleDevice = try { adapter?.getRemoteDevice(savedAddress) } catch (_: IllegalArgumentException) { null }
                if (savedBleDevice != null) {
                    stopScan(null)
                    closeGatt()
                    connecting = true
                    directConnectionFallback = true
                    update(state.copy(searching = true, message = "Reconectando ao fone salvo…"))
                    connect(savedBleDevice, tryBleFallback = true)
                    return
                }
            }
            val candidate = adapter?.bondedDevices?.firstOrNull {
                val name = try { it.name.orEmpty() } catch (_: SecurityException) { "" }
                (it.address == savedAddress || name.contains("qcy", true) || name.contains("h3", true)) &&
                    (name.contains("qcy", true) || name.contains("h3", true) || it.address == savedAddress)
            }
            if (profileConnected && candidate != null) {
                stopScan(null)
                closeGatt()
                connecting = true
                directConnectionFallback = true
                val name = try { candidate.name.orEmpty() } catch (_: SecurityException) { "" }
                update(state.copy(searching = true, deviceName = name.ifBlank { state.deviceName }, message = "Conectando ao fone pareado…"))
                connect(candidate, tryBleFallback = true)
                return
            }
        } catch (_: SecurityException) { }
        scan()
    }

    private fun fallbackToScan() {
        directConnectionFallback = false
        closeGatt()
        scan()
    }

    fun scan() {
        directConnectionFallback = false
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN) && Build.VERSION.SDK_INT >= 31) {
            update(state.copy(message = "Permissão de busca Bluetooth necessária")); return
        }
        stopScan(null)
        val adapter = (context.getSystemService(Context.BLUETOOTH_SERVICE) as? android.bluetooth.BluetoothManager)?.adapter
        scanner = adapter?.bluetoothLeScanner
        val activeScanner = scanner
        if (activeScanner == null) { update(state.copy(message = "Bluetooth indisponível")); return }
        expectedClassicAddress = try {
            val paired = adapter?.bondedDevices?.firstOrNull { device ->
                val name = try { device.name.orEmpty() } catch (_: SecurityException) { "" }
                name.contains("qcy", true) || name.contains("h3", true)
            }
            paired?.address ?: context.getSharedPreferences("sonicflow", Context.MODE_PRIVATE).getString("qcy_classic_address", null)
        } catch (_: SecurityException) { null }
        closeGatt()
        update(state.copy(searching = true, connected = false, message = "Procurando QCY H3…"))
        try {
            val filter = ScanFilter.Builder().setManufacturerData(0x521C, byteArrayOf(), byteArrayOf()).build()
            val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
            activeScanner.startScan(listOf(filter), settings, scanCallback)
            handler.removeCallbacks(scanTimeout)
            handler.postDelayed(scanTimeout, 12_000)
        } catch (_: SecurityException) { update(state.copy(searching = false, message = "Sem permissão para buscar dispositivos Bluetooth")) }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (connecting || state.connected) return
            val advert = parseQcyAdvert(result.scanRecord?.getManufacturerSpecificData(0x521C) ?: return) ?: return
            if (advert.vendorId !in setOf(0x4173, 0x4178, 0x4175)) return
            if (expectedClassicAddress != null && !advert.matches(expectedClassicAddress!!)) return
            val battery = advert.left
            val charging = advert.charging
            try {
                context.getSharedPreferences("sonicflow", Context.MODE_PRIVATE).edit()
                    .putString("qcy_device_address", result.device.address)
                    .putString("qcy_classic_address", advert.classicAddress)
                    .apply()
            } catch (_: SecurityException) { }
            stopScan(null)
            connecting = true
            update(state.copy(
                searching = true, connected = false, message = "QCY H3 encontrado • conectando…",
                battery = battery ?: state.battery, charging = if (battery != null) charging else state.charging
            ))
            connect(result.device)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.forEach { onScanResult(0, it) }
        }

        override fun onScanFailed(errorCode: Int) {
            stopScan("Busca Bluetooth falhou ($errorCode)")
        }
    }

    private fun stopScan(message: String?) {
        handler.removeCallbacks(scanTimeout)
        try { scanner?.stopScan(scanCallback) } catch (_: SecurityException) { }
        scanner = null
        if (message != null) update(state.copy(searching = false, message = message))
    }

    private fun connect(device: BluetoothDevice, autoConnect: Boolean = false, tryBleFallback: Boolean = false) {
        if (Build.VERSION.SDK_INT >= 31 && !hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            update(state.copy(message = "Permissão de conexão Bluetooth necessária")); return
        }
        try {
            val savedName = try { device.name } catch (_: SecurityException) { null }
            update(state.copy(deviceName = savedName?.takeIf { it.isNotBlank() } ?: "QCY H3"))
            gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(context, autoConnect, gattCallback, BluetoothDevice.TRANSPORT_LE)
            else device.connectGatt(context, autoConnect, gattCallback)
            handler.removeCallbacks(gattSetupTimeout)
            handler.postDelayed(gattSetupTimeout, 15_000)
            if (gatt == null) {
                connecting = false
                if (tryBleFallback) fallbackToScan() else update(state.copy(message = "Não foi possível conectar ao QCY H3"))
            }
        } catch (_: SecurityException) { connecting = false; update(state.copy(message = "Permissão de conexão Bluetooth necessária")) }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g !== gatt) { try { g.close() } catch (_: SecurityException) { }; return }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                if (directConnectionFallback) { fallbackToScan(); return }
                connecting = false
                update(state.copy(searching = false, connected = false, message = "Desconectado"))
                closeGatt(); scheduleReconnect(); return
            }
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                update(state.copy(searching = true, message = "Conectado • preparando controles…"))
                try {
                    if (!g.requestMtu(512) && !g.discoverServices()) update(state.copy(message = "Não foi possível abrir os serviços Bluetooth do fone"))
                } catch (_: SecurityException) { update(state.copy(message = "Permissão Bluetooth necessária")) }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                connecting = false
                if (directConnectionFallback) fallbackToScan()
                else { update(state.copy(searching = false, connected = false, message = "QCY H3 desconectado")); closeGatt(); scheduleReconnect() }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, mtu: Int, status: Int) {
            if (g !== gatt) return
            try {
                if (!g.discoverServices()) update(state.copy(message = "Não foi possível abrir os serviços Bluetooth do fone"))
            } catch (_: SecurityException) { update(state.copy(message = "Permissão Bluetooth necessária")) }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            if (g !== gatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) { if (directConnectionFallback) fallbackToScan() else { update(state.copy(searching = false, message = "Não foi possível abrir o canal do QCY H3")); closeGatt(); scheduleReconnect() }; return }
            val service = g.getService(SERVICE) ?: run { if (directConnectionFallback) fallbackToScan() else { update(state.copy(searching = false, message = "Protocolo do dispositivo não reconhecido")); closeGatt(); scheduleReconnect() }; return }
            writeCharacteristic = service.getCharacteristic(WRITE) ?: run { if (directConnectionFallback) fallbackToScan() else { update(state.copy(searching = false, message = "Canal de comandos QCY ausente")); closeGatt(); scheduleReconnect() }; return }
            val notify = service.getCharacteristic(NOTIFY)
            if (notify == null) { ready(); return }
            try {
                if (!g.setCharacteristicNotification(notify, true)) {
                    if (directConnectionFallback) fallbackToScan()
                    else update(state.copy(searching = false, message = "Não foi possível ativar as notificações BLE do fone"))
                    return
                }
                val descriptor = notify.getDescriptor(CCCD)
                if (descriptor == null) { ready(); return }
                val notificationValue = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    g.writeDescriptor(descriptor, notificationValue) == BluetoothStatusCodes.SUCCESS
                } else {
                    @Suppress("DEPRECATION")
                    descriptor.value = notificationValue
                    @Suppress("DEPRECATION")
                    g.writeDescriptor(descriptor)
                }
                if (!started) {
                    if (directConnectionFallback) fallbackToScan()
                    else update(state.copy(searching = false, message = "O fone recusou a ativação das notificações BLE"))
                }
            } catch (_: SecurityException) { update(state.copy(message = "Permissão Bluetooth necessária")) }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (g !== gatt) return
            if (status == BluetoothGatt.GATT_SUCCESS) ready()
            else if (directConnectionFallback) fallbackToScan()
            else { update(state.copy(searching = false, message = "Não foi possível ativar as respostas do fone")); closeGatt(); scheduleReconnect() }
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (g !== gatt) return
            parseNotification(characteristic.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, value: ByteArray) {
            if (g !== gatt) return
            parseNotification(value)
        }

        @Suppress("DEPRECATION")
        override fun onCharacteristicWrite(g: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (g !== gatt) return
            writing = false
            if (status == BluetoothGatt.GATT_SUCCESS) {
                pendingWrite = null
                writeRetryCount = 0
                writeNext()
            } else {
                writeRetryCount++
                if (writeRetryCount <= 3) handler.postDelayed({ writeNext() }, 180)
                else {
                    pendingWrite = null
                    writeRetryCount = 0
                    update(state.copy(message = "O QCY H3 recusou um comando Bluetooth"))
                    handler.post { writeNext() }
                }
            }
        }
    }

    private fun ready() {
        handler.removeCallbacks(gattSetupTimeout)
        handler.removeCallbacks(reconnectRunnable)
        reconnectAttempts = 0
        directConnectionFallback = false
        connecting = false
        update(state.copy(searching = false, connected = true, message = "Bluetooth conectado ao QCY H3"))
        requestItem(0x17)
        requestBattery()
        requestGameMode()
    }

    fun setNoiseMode(mode: Int, scene: Int = 0, strength: Int = 0) {
        if (!state.connected) { update(state.copy(message = "Conecte o QCY H3 para alterar o ANC")); return }
        val payload = byteArrayOf(0x17, 0x03, mode.toByte(), scene.toByte(), strength.toByte())
        sendCustom(payload)
        update(state.copy(ancMode = mode, ancScene = scene, ancStrength = strength, message = "Comando enviado ao QCY H3"))
    }

    /** JieLi RCSP attribute 5: 1 = normal, 2 = low-latency game mode. */
    fun setGameMode(enabled: Boolean) {
        if (!state.connected) { update(state.copy(message = "Conecte o QCY H3 para alterar o Modo Gamer")); return }
        sendRcsp(0xC0, byteArrayOf(2, 5, if (enabled) 2.toByte() else 1.toByte()))
        update(state.copy(gameMode = enabled, message = "Comando do Modo Gamer enviado"))
        handler.postDelayed({ requestGameMode() }, 500)
    }

    private fun requestGameMode() = sendRcsp(0xC1, byteArrayOf(0, 0, 0, 0x20))

    private fun requestItem(item: Int) = sendCustom(byteArrayOf(0xFE.toByte(), 0x01, item.toByte()))

    /** H3 uses the older JieLi protocol: battery comes from attribute 0, not QCY item 2F. */
    private fun requestBattery() = sendRcsp(0xC1, byteArrayOf(0, 0, 0, 1))

    private fun sendCustom(itemPayload: ByteArray) {
        sendRcsp(0xFF, itemPayload)
    }

    private fun sendRcsp(opcode: Int, payload: ByteArray) {
        val body = byteArrayOf((seq++ and 0xff).toByte()) + payload
        val frame = byteArrayOf(
            0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0xC0.toByte(), opcode.toByte(),
            ((body.size shr 8) and 0xff).toByte(), (body.size and 0xff).toByte()
        ) + body + byteArrayOf(0xEF.toByte())
        writeQueue.addLast(frame)
        writeNext()
    }

    private fun writeNext() {
        if (writing) return
        val characteristic = writeCharacteristic ?: return
        val currentGatt = gatt ?: return
        val frame = pendingWrite ?: writeQueue.pollFirst() ?: return
        pendingWrite = frame
        try {
            val writeType = if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            } else if (characteristic.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) {
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            } else {
                pendingWrite = null
                update(state.copy(message = "O canal de comandos do QCY H3 não aceita escrita"))
                return
            }
            writing = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                currentGatt.writeCharacteristic(characteristic, frame, writeType) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.writeType = writeType
                @Suppress("DEPRECATION")
                characteristic.setValue(frame) && currentGatt.writeCharacteristic(characteristic)
            }
            if (!writing) {
                writeRetryCount++
                if (writeRetryCount <= 3) handler.postDelayed({ writeNext() }, 180)
                else {
                    pendingWrite = null
                    writeRetryCount = 0
                    update(state.copy(message = "O canal BLE recusou um comando"))
                    handler.post { writeNext() }
                }
            }
        } catch (_: SecurityException) {
            writing = false
            pendingWrite = null
            writeRetryCount = 0
            update(state.copy(message = "Permissão de conexão Bluetooth necessária"))
        }
    }

    private fun parseNotification(data: ByteArray) {
        rcspBuffer += data
        while (true) {
            val start = (0..rcspBuffer.size - 3).firstOrNull {
                rcspBuffer[it] == 0xFE.toByte() && rcspBuffer[it + 1] == 0xDC.toByte() && rcspBuffer[it + 2] == 0xBA.toByte()
            }
            if (start == null) { rcspBuffer = rcspBuffer.takeLast(2).toByteArray(); return }
            if (start > 0) rcspBuffer = rcspBuffer.copyOfRange(start, rcspBuffer.size)
            if (rcspBuffer.size < 7) return
            val flags = rcspBuffer[3].toInt() and 0xFF
            val opcode = rcspBuffer[4].toInt() and 0xFF
            val length = ((rcspBuffer[5].toInt() and 0xFF) shl 8) or (rcspBuffer[6].toInt() and 0xFF)
            if (length > 4096) { rcspBuffer = rcspBuffer.copyOfRange(1, rcspBuffer.size); continue }
            val total = 8 + length
            if (rcspBuffer.size < total) return
            if (rcspBuffer[total - 1] != 0xEF.toByte()) { rcspBuffer = rcspBuffer.copyOfRange(1, rcspBuffer.size); continue }
            val params = rcspBuffer.copyOfRange(7, 7 + length)
            if (flags and 0x80 != 0) {
                if (flags and 0x40 != 0 && params.isNotEmpty()) sendRcspAnswer(opcode, params[0].toInt() and 0xFF)
                if (opcode == 0xFF && params.isNotEmpty()) parseQcyItems(params.copyOfRange(1, params.size))
            } else if (params.size >= 2 && params[0].toInt() == 0) {
                val payload = params.copyOfRange(2, params.size)
                when (opcode) {
                    0xFF -> parseQcyItems(payload)
                    0xC1 -> parseJieliAttributes(payload)
                }
            }
            rcspBuffer = rcspBuffer.copyOfRange(total, rcspBuffer.size)
        }
    }

    private fun sendRcspAnswer(opcode: Int, requestSequence: Int) {
        val params = byteArrayOf(0, requestSequence.toByte())
        val frame = byteArrayOf(0xFE.toByte(), 0xDC.toByte(), 0xBA.toByte(), 0, opcode.toByte(), 0, 2) + params + byteArrayOf(0xEF.toByte())
        writeQueue.addLast(frame)
        writeNext()
    }

    private fun parseQcyItems(bytes: ByteArray) {
        var at = if (bytes.size >= 2 && bytes[0] == 0xFF.toByte()) 2 else 0
        val limit = if (at == 2) minOf(bytes.size, 2 + (bytes[1].toInt() and 0xFF)) else bytes.size
        while (at + 2 <= limit) {
            val item = bytes[at].toInt() and 0xFF
            val size = bytes[at + 1].toInt() and 0xFF
            if (at + 2 + size > limit) break
            val value = bytes.copyOfRange(at + 2, at + 2 + size)
            if (item == 0x17 && value.size >= 3) {
                update(state.copy(ancMode = value[0].toInt() and 0xFF, ancScene = value[1].toInt() and 0xFF, ancStrength = value[2].toInt() and 0xFF))
            } else if (item == 0x2F && value.isNotEmpty()) updateBattery(value[0])
            at += 2 + size
        }
    }

    private fun parseJieliAttributes(bytes: ByteArray) {
        var at = 0
        while (at + 2 <= bytes.size) {
            val length = bytes[at].toInt() and 0xFF
            if (length < 2 || at + 1 + length > bytes.size) break
            val type = bytes[at + 1].toInt() and 0xFF
            val value = bytes.copyOfRange(at + 2, at + 1 + length)
            if (type == 0 && value.isNotEmpty()) updateBattery(value[0])
            else if (type == 5 && value.isNotEmpty()) update(state.copy(gameMode = (value[0].toInt() and 0xFF) == 2))
            at += 1 + length
        }
    }

    private fun updateBattery(raw: Byte) {
        val value = raw.toInt() and 0xFF
        val battery = value and 0x7F
        if (battery in 1..100) update(state.copy(battery = battery, charging = value and 0x80 != 0))
    }

    fun close() {
        stopScan(null)
        if (monitoringConnections) {
            try { context.unregisterReceiver(connectionReceiver) } catch (_: IllegalArgumentException) { }
            monitoringConnections = false
        }
        closeGatt()
    }

    private fun closeGatt() {
        handler.removeCallbacks(gattSetupTimeout)
        try { gatt?.disconnect(); gatt?.close() } catch (_: SecurityException) { }
        gatt = null; writeCharacteristic = null; writing = false; pendingWrite = null; writeRetryCount = 0; connecting = false; writeQueue.clear()
    }

    private fun hasPermission(permission: String) = ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

    private var rcspBuffer = ByteArray(0)

    private data class QcyAdvert(val vendorId: Int, val classicAddress: String, val otherAddress: String, val left: Int?, val charging: Boolean) {
        fun matches(address: String) = classicAddress.equals(address, true) || otherAddress.equals(address, true)
    }

    private fun parseQcyAdvert(data: ByteArray): QcyAdvert? {
        if (data.size < 20) return null
        val d = if (data.size >= 24) data else data.copyOf(24)
        fun hex(index: Int) = (d[index].toInt() and 0xFF).toString(16).uppercase().padStart(2, '0')
        fun address(vararg indexes: Int) = indexes.joinToString(":") { hex(it) }
        val classic = address(12, 11, 13, 16, 15, 14)
        val otherRaw = address(19, 18, 20, 23, 22, 21)
        val other = otherRaw.takeIf { it != "00:00:00:00:00:00" } ?: classic
        val rawBattery = d[5].toInt() and 0xFF
        return QcyAdvert(
            vendorId = ((d[0].toInt() and 0xFF) shl 8) or (d[1].toInt() and 0xFF),
            classicAddress = classic, otherAddress = other,
            left = (rawBattery and 0x7F).takeIf { it in 1..100 }, charging = rawBattery and 0x80 != 0
        )
    }

    private companion object {
        val SERVICE: UUID = UUID.fromString("0000A002-0000-1000-8000-00805F9B34FB")
        val WRITE: UUID = UUID.fromString("00000001-0000-1000-8000-00805F9B34FB")
        val NOTIFY: UUID = UUID.fromString("00000002-0000-1000-8000-00805F9B34FB")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")
    }
}
