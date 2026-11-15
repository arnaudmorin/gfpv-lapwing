package com.gfpv.lapwing.ble

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.content.Context
import android.os.Build
import android.util.Log
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withTimeout
import java.io.IOException
import java.util.UUID

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val name: String) : ConnectionState
    data class Connected(val name: String) : ConnectionState
    /** The link dropped unexpectedly, Android reconnects as soon as the timer is back in range. */
    data class Reconnecting(val name: String) : ConnectionState
}

class GattException(message: String) : IOException(message)

/**
 * One BLE link to a Lapwing timer.
 *
 * Android's GATT client can only run one operation at a time, so every read/write/descriptor/MTU
 * operation goes through [gattOp], which holds [opLock] until the matching callback arrives.
 * Callbacks arrive on a binder thread and complete [pending].
 */
@SuppressLint("MissingPermission") // permissions are requested by the UI before connecting
class TimerConnection(private val context: Context, private val scope: CoroutineScope) {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _config = MutableStateFlow<TimerConfig?>(null)
    val config: StateFlow<TimerConfig?> = _config.asStateFlow()

    private val _status = MutableStateFlow<TimerStatus?>(null)
    val status: StateFlow<TimerStatus?> = _status.asStateFlow()

    private val _info = MutableStateFlow<DeviceInfo?>(null)
    val info: StateFlow<DeviceInfo?> = _info.asStateFlow()

    private val _laps = MutableSharedFlow<LapEvent>(extraBufferCapacity = 64)
    val laps: SharedFlow<LapEvent> = _laps.asSharedFlow()

    private val _rssi = MutableSharedFlow<Int>(extraBufferCapacity = 64)
    val rssi: SharedFlow<Int> = _rssi.asSharedFlow()

    private val otaReplies = Channel<ByteArray>(Channel.UNLIMITED)

    private val opLock = Mutex()
    @Volatile private var pending: CompletableDeferred<ByteArray>? = null
    @Volatile private var gatt: BluetoothGatt? = null
    @Volatile private var device: BluetoothDevice? = null
    @Volatile private var userDisconnect = false
    @Volatile private var mtu = 23
    @Volatile private var rssiStreaming = false
    private var setupJob: Job? = null

    private val adapter get() = context.getSystemService(BluetoothManager::class.java).adapter

    val deviceName: String get() = device?.name ?: device?.address ?: "timer"

    fun connect(address: String) {
        disconnect()
        val dev = adapter.getRemoteDevice(address)
        device = dev
        userDisconnect = false
        _state.value = ConnectionState.Connecting(dev.name ?: address)
        openGatt(dev, autoConnect = false)
    }

    fun disconnect() {
        userDisconnect = true
        setupJob?.cancel()
        gatt?.let {
            it.disconnect()
            it.close()
        }
        gatt = null
        failPending("disconnected")
        _state.value = ConnectionState.Disconnected
        _status.value = null
    }

    private fun openGatt(dev: BluetoothDevice, autoConnect: Boolean) {
        gatt = dev.connectGatt(context, autoConnect, callback, BluetoothDevice.TRANSPORT_LE)
    }

    // ---- public operations ------------------------------------------------------------------

    suspend fun startTimer() = write(LapwingUuids.CONTROL, byteArrayOf(ControlCommand.START))

    suspend fun stopTimer() = write(LapwingUuids.CONTROL, byteArrayOf(ControlCommand.STOP))

    suspend fun writeConfig(config: TimerConfig) {
        write(LapwingUuids.CONFIG, config.toJson().toByteArray())
        _config.value = config
    }

    suspend fun refreshConfig(): TimerConfig {
        val cfg = TimerConfig.fromJson(String(read(LapwingUuids.CONFIG)))
        _config.value = cfg
        return cfg
    }

    /** RSSI is only streamed by the timer while we are subscribed (calibration screen). */
    suspend fun setRssiStreaming(enabled: Boolean) {
        rssiStreaming = enabled
        if (state.value is ConnectionState.Connected) setNotify(LapwingUuids.RSSI, enabled)
    }

    /**
     * Sends a firmware image over BLE. The timer verifies it and reboots; this connection then
     * reconnects on its own once the timer advertises again.
     */
    suspend fun updateFirmware(image: FirmwareImage, onProgress: (Float) -> Unit) {
        val g = gatt ?: throw GattException("Not connected")
        g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_HIGH)
        while (otaReplies.tryReceive().isSuccess) { /* drop stale replies */ }

        write(LapwingUuids.OTA_CTRL, image.beginCommand())
        expectOtaReply(OtaCommand.BEGIN, 15_000)

        val chunkSize = minOf(mtu - 3, 512)
        val bytes = image.bytes
        var offset = 0
        try {
            while (offset < bytes.size) {
                val end = minOf(offset + chunkSize, bytes.size)
                write(LapwingUuids.OTA_DATA, bytes.copyOfRange(offset, end))
                otaReplies.tryReceive().getOrNull()?.let { reply ->
                    throw GattException("Update failed: ${OtaCommand.errorText(reply.getOrElse(1) { 0 }.toInt() and 0xFF)}")
                }
                offset = end
                onProgress(offset.toFloat() / bytes.size)
            }
            write(LapwingUuids.OTA_CTRL, byteArrayOf(OtaCommand.END))
            expectOtaReply(OtaCommand.END, 30_000)
        } catch (e: Exception) {
            runCatching { write(LapwingUuids.OTA_CTRL, byteArrayOf(OtaCommand.ABORT)) }
            throw e
        } finally {
            g.requestConnectionPriority(BluetoothGatt.CONNECTION_PRIORITY_BALANCED)
        }
    }

    private suspend fun expectOtaReply(command: Byte, timeoutMs: Long) {
        val reply = try {
            withTimeout(timeoutMs) { otaReplies.receive() }
        } catch (e: TimeoutCancellationException) {
            throw GattException("The timer did not answer")
        }
        val status = reply.getOrElse(1) { -1 }.toInt() and 0xFF
        if (reply[0] != command || status != 0) {
            throw GattException("Update failed: ${OtaCommand.errorText(status)}")
        }
    }

    // ---- connection setup -------------------------------------------------------------------

    private suspend fun setup(g: BluetoothGatt) {
        gattOp("mtu") { g.requestMtu(517) }
        gattOp("discover") { g.discoverServices() }
        if (g.getService(LapwingUuids.SERVICE) == null) throw GattException("Not a compatible timer")
        setNotify(LapwingUuids.LAP, true)
        setNotify(LapwingUuids.STATUS, true)
        setNotify(LapwingUuids.OTA_CTRL, true)
        if (rssiStreaming) setNotify(LapwingUuids.RSSI, true)
        _info.value = DeviceInfo.parse(String(read(LapwingUuids.INFO)))
        _status.value = TimerStatus.parse(read(LapwingUuids.STATUS))
        refreshConfig()
    }

    private fun characteristic(uuid: UUID): BluetoothGattCharacteristic {
        val g = gatt ?: throw GattException("Not connected")
        return g.getService(LapwingUuids.SERVICE)?.getCharacteristic(uuid)
            ?: throw GattException("Characteristic $uuid missing, is the firmware up to date?")
    }

    private suspend fun read(uuid: UUID): ByteArray {
        val c = characteristic(uuid)
        return gattOp("read") { gatt?.readCharacteristic(c) ?: false }
    }

    private suspend fun write(uuid: UUID, value: ByteArray) {
        val c = characteristic(uuid)
        gattOp("write") {
            val g = gatt ?: return@gattOp false
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeCharacteristic(c, value, BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                c.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                @Suppress("DEPRECATION")
                c.value = value
                @Suppress("DEPRECATION")
                g.writeCharacteristic(c)
            }
        }
    }

    private suspend fun setNotify(uuid: UUID, enabled: Boolean) {
        val c = characteristic(uuid)
        val g = gatt ?: throw GattException("Not connected")
        g.setCharacteristicNotification(c, enabled)
        val d = c.getDescriptor(LapwingUuids.CCCD) ?: throw GattException("CCCD missing")
        val value = if (enabled) BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE else BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
        gattOp("descriptor") {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                g.writeDescriptor(d, value) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                d.value = value
                @Suppress("DEPRECATION")
                g.writeDescriptor(d)
            }
        }
    }

    /** Runs one GATT operation and waits for its callback. Retries a few times if the stack is busy. */
    private suspend fun gattOp(name: String, start: () -> Boolean): ByteArray = opLock.withLock {
        val deferred = CompletableDeferred<ByteArray>()
        pending = deferred
        try {
            var attempts = 0
            while (!start()) {
                if (++attempts >= 5 || gatt == null) throw GattException("GATT $name could not be started")
                delay(50)
            }
            withTimeout(10_000) { deferred.await() }
        } catch (e: TimeoutCancellationException) {
            throw GattException("GATT $name timed out")
        } finally {
            pending = null
        }
    }

    private fun completePending(status: Int, value: ByteArray = ByteArray(0)) {
        val p = pending ?: return
        if (status == BluetoothGatt.GATT_SUCCESS) p.complete(value) else p.completeExceptionally(GattException("GATT error $status"))
    }

    private fun failPending(reason: String) {
        pending?.completeExceptionally(GattException(reason))
    }

    private fun onNotification(uuid: UUID, value: ByteArray) {
        when (uuid) {
            LapwingUuids.LAP -> LapEvent.parse(value)?.let { _laps.tryEmit(it) }
            LapwingUuids.RSSI -> if (value.isNotEmpty()) _rssi.tryEmit(value[0].toInt() and 0xFF)
            LapwingUuids.STATUS -> TimerStatus.parse(value)?.let { _status.value = it }
            LapwingUuids.OTA_CTRL -> otaReplies.trySend(value)
        }
    }

    private val callback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, status: Int, newState: Int) {
            if (g != gatt) {
                g.close() // a stale connection from before a disconnect()/reconnect
                return
            }
            val name = deviceName
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                Log.i(TAG, "connected to $name")
                _state.value = ConnectionState.Connecting(name)
                setupJob = scope.launch {
                    try {
                        setup(g)
                        _state.value = ConnectionState.Connected(name)
                    } catch (e: Exception) {
                        Log.w(TAG, "setup failed", e)
                        if (g == gatt) g.disconnect()
                    }
                }
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                Log.i(TAG, "disconnected from $name (status $status)")
                setupJob?.cancel()
                failPending("disconnected")
                g.close()
                gatt = null
                if (userDisconnect) {
                    _state.value = ConnectionState.Disconnected
                } else {
                    // Out of range, timer rebooted (e.g. after an update)...: let Android reconnect in the background.
                    _state.value = ConnectionState.Reconnecting(name)
                    val dev = device ?: return
                    scope.launch {
                        delay(1_000)
                        if (!userDisconnect && gatt == null) openGatt(dev, autoConnect = true)
                    }
                }
            }
        }

        override fun onMtuChanged(g: BluetoothGatt, newMtu: Int, status: Int) {
            if (status == BluetoothGatt.GATT_SUCCESS) mtu = newMtu
            completePending(BluetoothGatt.GATT_SUCCESS) // keep the default MTU if the request failed
        }

        override fun onServicesDiscovered(g: BluetoothGatt, status: Int) = completePending(status)

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, status: Int) =
            completePending(status, value)

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) {
            // On API 33+ Android calls both variants, only handle the legacy one on older releases
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) completePending(status, c.value ?: ByteArray(0))
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, status: Int) = completePending(status)

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, status: Int) = completePending(status)

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) =
            onNotification(c.uuid, value)

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            @Suppress("DEPRECATION")
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) onNotification(c.uuid, c.value ?: ByteArray(0))
        }
    }

    companion object {
        private const val TAG = "TimerConnection"
    }
}
