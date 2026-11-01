package io.github.michealsizemore1.top25widget.run

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanFilter
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.ParcelUuid
import java.util.UUID

/**
 * Stryd footpod over Bluetooth LE, using the standard services every Stryd exposes:
 *  - Running Speed & Cadence (0x1814 / measurement 0x2A53): speed, cadence, total distance
 *  - Cycling Power (0x1818 / measurement 0x2A63): running power in watts
 * Readings go straight into [Run]. The pod's address is remembered after the first pairing.
 */
@SuppressLint("MissingPermission")   // callers check BLUETOOTH_SCAN / BLUETOOTH_CONNECT first
class Stryd(private val ctx: Context) {

    companion object {
        private fun uuid16(x: Int) = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", x))
        val RSC_SERVICE: UUID = uuid16(0x1814)
        val RSC_MEASUREMENT: UUID = uuid16(0x2A53)
        val POWER_SERVICE: UUID = uuid16(0x1818)
        val POWER_MEASUREMENT: UUID = uuid16(0x2A63)
        val CCC_DESCRIPTOR: UUID = uuid16(0x2902)
        val BATTERY_SERVICE: UUID = uuid16(0x180F)
        val BATTERY_LEVEL: UUID = uuid16(0x2A19)
        private const val PREFS = "stryd"

        fun savedAddress(ctx: Context): String? = ctx.getSharedPreferences(PREFS, 0).getString("address", null)
        fun savedName(ctx: Context): String? = ctx.getSharedPreferences(PREFS, 0).getString("name", null)
        fun forget(ctx: Context) = ctx.getSharedPreferences(PREFS, 0).edit().clear().apply()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var stopped = false
    private val pendingNotify = ArrayDeque<BluetoothGattCharacteristic>()

    /** Connect to the saved pod, or scan for one if none is saved. */
    fun start() {
        stopped = false
        if (adapter == null || !adapter.isEnabled) { Run.strydStatus = "Bluetooth off"; return }
        val addr = savedAddress(ctx)
        if (addr == null) { scan(); return }
        connect(adapter.getRemoteDevice(addr))
    }

    fun stop() {
        stopped = true
        stopScan()
        handler.removeCallbacksAndMessages(null)
        try { gatt?.disconnect(); gatt?.close() } catch (e: Exception) { }
        gatt = null
        Run.strydConnected = false
        Run.strydStatus = if (savedAddress(ctx) != null) "Not connected" else "Not paired"
    }

    /** Forget the saved pod and look for one nearby. */
    fun pairNew() {
        stop()
        forget(ctx)
        stopped = false
        scan()
    }

    // ---------------------------------------------------------------- scanning

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val device = result.device
            val name = result.scanRecord?.deviceName ?: device.name ?: ""
            val uuids = result.scanRecord?.serviceUuids.orEmpty().map { it.uuid }
            val looksLikeStryd = name.contains("stryd", ignoreCase = true) ||
                (uuids.contains(RSC_SERVICE) && uuids.contains(POWER_SERVICE))
            if (!looksLikeStryd) return
            stopScan()
            ctx.getSharedPreferences(PREFS, 0).edit()
                .putString("address", device.address).putString("name", name.ifBlank { "Stryd" }).apply()
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Run.strydStatus = "Scan failed ($errorCode)"
        }
    }

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { Run.strydStatus = "Bluetooth off"; return }
        Run.strydStatus = "Searching… wake your Stryd"
        val filters = listOf(
            ScanFilter.Builder().setServiceUuid(ParcelUuid(RSC_SERVICE)).build(),
            ScanFilter.Builder().setServiceUuid(ParcelUuid(POWER_SERVICE)).build(),
        )
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { scanner.startScan(filters, settings, scanCallback); scanning = true } catch (e: Exception) {
            Run.strydStatus = "Can't scan: ${e.message}"; return
        }
        handler.postDelayed({
            if (scanning) { stopScan(); Run.strydStatus = "No Stryd found. Tap to retry" }
        }, 20_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- connection

    private fun connect(device: BluetoothDevice) {
        Run.strydStatus = "Connecting…"
        try { gatt?.close() } catch (e: Exception) { }
        gatt = device.connectGatt(ctx, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    private fun reconnectLater() {
        if (stopped) return
        handler.postDelayed({
            if (!stopped) savedAddress(ctx)?.let { addr -> adapter?.let { connect(it.getRemoteDevice(addr)) } }
        }, 3000)
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Run.strydStatus = "Connected"
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Run.strydConnected = false
                Run.strydStatus = "Reconnecting…"
                try { g.close() } catch (e: Exception) { }
                if (gatt === g) gatt = null
                reconnectLater()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            pendingNotify.clear()
            g.getService(RSC_SERVICE)?.getCharacteristic(RSC_MEASUREMENT)?.let { pendingNotify.addLast(it) }
            g.getService(POWER_SERVICE)?.getCharacteristic(POWER_MEASUREMENT)?.let { pendingNotify.addLast(it) }
            if (pendingNotify.isEmpty()) { Run.strydStatus = "Not a Stryd?"; return }
            Run.strydConnected = true
            Run.strydStatus = "Connected · ${savedName(ctx) ?: "Stryd"}"
            enableNext(g)
        }

        // Bluetooth allows one setup step at a time: turn on notifications one characteristic after another.
        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) { enableNext(g) }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION") handle(c.uuid, c.value ?: return)
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            handle(c.uuid, value)
        }

        @Deprecated("Deprecated in Java")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            @Suppress("DEPRECATION") if (status == BluetoothGatt.GATT_SUCCESS) handle(c.uuid, c.value ?: return)
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) handle(c.uuid, value)
        }
    }

    private fun enableNext(g: BluetoothGatt) {
        val c = pendingNotify.removeFirstOrNull() ?: run {
            // Notifications are on; now read the pod's battery level once.
            g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)?.let { g.readCharacteristic(it) }
            return
        }
        g.setCharacteristicNotification(c, true)
        val d = c.getDescriptor(CCC_DESCRIPTOR) ?: run { enableNext(g); return }
        if (Build.VERSION.SDK_INT >= 33) {
            g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
        } else {
            @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
        }
    }

    // ---------------------------------------------------------------- parsing

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)
    private fun s16(b: ByteArray, i: Int) = u16(b, i).toShort().toInt()
    private fun u32(b: ByteArray, i: Int) = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)

    private fun handle(uuid: UUID, b: ByteArray) {
        val now = System.currentTimeMillis()
        when (uuid) {
            RSC_MEASUREMENT -> if (b.size >= 4) {
                val flags = u8(b, 0)
                val speed = u16(b, 1) / 256.0           // m/s
                var cadence = u8(b, 3)                  // per spec steps/min; some pods send strides/min
                if (cadence in 1..119 && speed > 1.8) cadence *= 2
                var i = 4
                if (flags and 0x01 != 0 && b.size >= i + 2) { Run.strydStride = u16(b, i) / 100.0; i += 2 }   // stride length, cm
                if (flags and 0x02 != 0 && b.size >= i + 4) Run.strydTotalDistance = u32(b, i) / 10.0
                Run.strydSpeed = speed
                Run.strydCadence = cadence
                Run.strydLastUpdate = now
            }
            BATTERY_LEVEL -> if (b.isNotEmpty()) Run.strydBattery = u8(b, 0).coerceIn(0, 100)
            POWER_MEASUREMENT -> if (b.size >= 4) {
                Run.strydPower = s16(b, 2).coerceAtLeast(0)
                Run.strydLastUpdate = now
            }
        }
    }
}
