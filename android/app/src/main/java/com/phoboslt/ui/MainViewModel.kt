package com.phoboslt.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phoboslt.PhobosApplication
import com.phoboslt.ble.FirmwareImage
import com.phoboslt.ble.FoundTimer
import com.phoboslt.ble.TimerConfig
import com.phoboslt.ble.scanTimers
import com.phoboslt.race.RacePhase
import com.phoboslt.service.TimerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

sealed interface OtaState {
    data object Idle : OtaState
    data class Ready(val image: FirmwareImage, val fileName: String, val warning: String?) : OtaState
    data class Running(val progress: Float) : OtaState
    data object Done : OtaState
    data class Failed(val message: String) : OtaState
}

class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as PhobosApplication).container
    private val prefs = app.getSharedPreferences("phoboslt", Context.MODE_PRIVATE)

    val connection = container.connection
    val race = container.race
    val races = container.database.raceDao().observeRaces()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _messages = MutableStateFlow<String?>(null)
    val messages: StateFlow<String?> = _messages.asStateFlow()
    fun messageShown() {
        _messages.value = null
    }

    // ---- scanning / connection ----

    private val _found = MutableStateFlow<List<FoundTimer>>(emptyList())
    val found: StateFlow<List<FoundTimer>> = _found.asStateFlow()
    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()
    private var scanJob: Job? = null

    val lastDevice: Pair<String, String>?
        get() {
            val address = prefs.getString("lastAddress", null) ?: return null
            return address to (prefs.getString("lastName", null) ?: address)
        }

    fun startScan() {
        scanJob?.cancel()
        _found.value = emptyList()
        _scanning.value = true
        scanJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(15_000) {
                    scanTimers(getApplication()).collect { t ->
                        _found.update { list -> (list.filter { it.address != t.address } + t).sortedByDescending { it.rssi } }
                    }
                }
            } catch (e: Exception) {
                _messages.value = e.message
            } finally {
                _scanning.value = false
            }
        }
    }

    fun stopScan() {
        scanJob?.cancel()
    }

    fun connect(address: String, name: String) {
        stopScan()
        prefs.edit().putString("lastAddress", address).putString("lastName", name).apply()
        connection.connect(address)
        TimerService.start(getApplication())
    }

    fun disconnect() {
        if (race.state.value.phase != RacePhase.IDLE) race.stop()
        connection.disconnect()
    }

    fun deleteRace(id: Long) {
        container.scope.launch { container.database.raceDao().deleteRace(id) }
    }

    // ---- configuration ----

    fun saveConfig(config: TimerConfig) {
        container.scope.launch {
            _messages.value = try {
                connection.writeConfig(config)
                "Configuration saved"
            } catch (e: Exception) {
                "Could not save: ${e.message}"
            }
        }
    }

    fun testVoice(config: TimerConfig) {
        container.scope.launch {
            val a = container.announcer
            a.rate = config.announcerRate
            val pilot = config.pilotName.ifBlank { "" }
            a.speak("Testing sound for pilot $pilot")
            for (i in 1..3) a.speak("$i")
        }
    }

    // ---- calibration ----

    private val _rssiSamples = MutableStateFlow<List<Int>>(emptyList())
    val rssiSamples: StateFlow<List<Int>> = _rssiSamples.asStateFlow()

    init {
        viewModelScope.launch {
            connection.rssi.collect { v -> _rssiSamples.update { (it + v).takeLast(RSSI_HISTORY) } }
        }
    }

    fun setRssiStreaming(enabled: Boolean) {
        if (enabled) _rssiSamples.value = emptyList()
        // app scope: the unsubscribe must also go out when the screen is torn down
        container.scope.launch { runCatching { connection.setRssiStreaming(enabled) } }
    }

    // ---- firmware update ----

    private val _ota = MutableStateFlow<OtaState>(OtaState.Idle)
    val ota: StateFlow<OtaState> = _ota.asStateFlow()

    fun loadFirmware(uri: Uri) {
        viewModelScope.launch {
            _ota.value = try {
                val ctx = getApplication<Application>()
                val bytes = withContext(Dispatchers.IO) {
                    ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: error("Cannot read the file")
                }
                val image = FirmwareImage.parse(bytes)
                val chip = connection.info.value?.chip.orEmpty()
                val expected = FirmwareImage.expectedChipId(chip)
                if (expected != null && expected != image.chipId) {
                    OtaState.Failed("This image is built for ${image.chipName} but the timer is an $chip")
                } else {
                    val warning = if (race.state.value.phase != RacePhase.IDLE) "The running race will be stopped." else null
                    OtaState.Ready(image, uri.lastPathSegment?.substringAfterLast('/') ?: "firmware.bin", warning)
                }
            } catch (e: Exception) {
                OtaState.Failed(e.message ?: "Invalid file")
            }
        }
    }

    fun flashFirmware(image: FirmwareImage) {
        if (race.state.value.phase != RacePhase.IDLE) race.stop()
        _ota.value = OtaState.Running(0f)
        viewModelScope.launch {
            _ota.value = try {
                connection.updateFirmware(image) { p -> _ota.value = OtaState.Running(p) }
                OtaState.Done
            } catch (e: Exception) {
                OtaState.Failed(e.message ?: "Update failed")
            }
        }
    }

    fun resetOta() {
        _ota.value = OtaState.Idle
    }

    companion object {
        const val RSSI_HISTORY = 300 // 30 s at 10 Hz
    }
}
