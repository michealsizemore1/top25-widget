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
 * Chest strap (Polar H10 or any standard Bluetooth heart-rate strap) using the
 * Heart Rate service (0x180D / measurement 0x2A37). While the strap is sending,
 * its reading replaces the watch's wrist sensor. Also collects R-R intervals.
 */
@SuppressLint("MissingPermission")
class HrStrap(private val ctx: Context) {

    companion object {
        private fun uuid16(x: Int) = UUID.fromString(String.format("0000%04x-0000-1000-8000-00805f9b34fb", x))
        val HR_SERVICE: UUID = uuid16(0x180D)
        val HR_MEASUREMENT: UUID = uuid16(0x2A37)
        val BATTERY_SERVICE: UUID = uuid16(0x180F)
        val BATTERY_LEVEL: UUID = uuid16(0x2A19)
        val CCC_DESCRIPTOR: UUID = uuid16(0x2902)
        private const val PREFS = "hrstrap"

        fun savedAddress(ctx: Context): String? = ctx.getSharedPreferences(PREFS, 0).getString("address", null)
        fun savedName(ctx: Context): String? = ctx.getSharedPreferences(PREFS, 0).getString("name", null)
        fun forget(ctx: Context) = ctx.getSharedPreferences(PREFS, 0).edit().clear().apply()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val adapter = ctx.getSystemService(BluetoothManager::class.java)?.adapter
    private var gatt: BluetoothGatt? = null
    private var scanning = false
    private var stopped = false

    fun start() {
        stopped = false
        if (adapter == null || !adapter.isEnabled) { Run.strapStatus = "Bluetooth off"; return }
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
        Run.strapConnected = false
        Run.strapStatus = if (savedAddress(ctx) != null) "Not connected" else "Not paired"
    }

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
            stopScan()
            ctx.getSharedPreferences(PREFS, 0).edit()
                .putString("address", device.address).putString("name", name.ifBlank { "HR strap" }).apply()
            connect(device)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            Run.strapStatus = "Scan failed ($errorCode)"
        }
    }

    private fun scan() {
        val scanner = adapter?.bluetoothLeScanner ?: run { Run.strapStatus = "Bluetooth off"; return }
        Run.strapStatus = "Searching… wet & wear the strap"
        val filters = listOf(ScanFilter.Builder().setServiceUuid(ParcelUuid(HR_SERVICE)).build())
        val settings = ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build()
        try { scanner.startScan(filters, settings, scanCallback); scanning = true } catch (e: Exception) {
            Run.strapStatus = "Can't scan: ${e.message}"; return
        }
        handler.postDelayed({
            if (scanning) { stopScan(); Run.strapStatus = "No strap found · using wrist" }
        }, 20_000)
    }

    private fun stopScan() {
        if (!scanning) return
        scanning = false
        try { adapter?.bluetoothLeScanner?.stopScan(scanCallback) } catch (e: Exception) { }
    }

    // ---------------------------------------------------------------- connection

    private fun connect(device: BluetoothDevice) {
        Run.strapStatus = "Connecting…"
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
                Run.strapStatus = "Connected"
                g.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Run.strapConnected = false
                Run.strapStatus = "Reconnecting… (wrist for now)"
                try { g.close() } catch (e: Exception) { }
                if (gatt === g) gatt = null
                reconnectLater()
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) {
            val c = g.getService(HR_SERVICE)?.getCharacteristic(HR_MEASUREMENT)
            if (c == null) { Run.strapStatus = "Not a heart-rate strap?"; return }
            Run.strapConnected = true
            Run.strapStatus = savedName(ctx) ?: "HR strap"
            g.setCharacteristicNotification(c, true)
            val d = c.getDescriptor(CCC_DESCRIPTOR) ?: return
            if (Build.VERSION.SDK_INT >= 33) {
                g.writeDescriptor(d, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE)
            } else {
                @Suppress("DEPRECATION") run { d.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; g.writeDescriptor(d) }
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) {
            g.getService(BATTERY_SERVICE)?.getCharacteristic(BATTERY_LEVEL)?.let { g.readCharacteristic(it) }
        }

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

    // ---------------------------------------------------------------- parsing

    private fun u8(b: ByteArray, i: Int) = b[i].toInt() and 0xFF
    private fun u16(b: ByteArray, i: Int) = u8(b, i) or (u8(b, i + 1) shl 8)

    private fun handle(uuid: java.util.UUID, b: ByteArray) {
        when (uuid) {
            BATTERY_LEVEL -> if (b.isNotEmpty()) Run.strapBattery = u8(b, 0).coerceIn(0, 100)
            HR_MEASUREMENT -> if (b.size >= 2) {
                val flags = u8(b, 0)
                var i = 1
                val bpm = if (flags and 0x01 != 0) { val v = u16(b, i); i += 2; v } else { val v = u8(b, i); i += 1; v }
                // Bits 1–2: skin contact supported (bit 2) and detected (bit 1).
                val contactSupported = flags and 0x04 != 0
                val contact = !contactSupported || flags and 0x02 != 0
                if (flags and 0x08 != 0) i += 2   // energy expended (skip)
                if (flags and 0x10 != 0) {
                    while (i + 1 < b.size) { Run.onRr(u16(b, i) * 1000 / 1024); i += 2 }
                }
                if (contact && bpm in 30..230) {
                    Run.heartRate = bpm
                    Run.strapLastUpdate = System.currentTimeMillis()
                }
                Run.strapContact = contact
            }
        }
    }
}
