package com.gfpv.lapwing.ui

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.gfpv.lapwing.LapwingApplication
import com.gfpv.lapwing.audio.Callout
import com.gfpv.lapwing.audio.VoiceLanguage
import com.gfpv.lapwing.ble.BundledFirmware
import com.gfpv.lapwing.ble.DeviceInfo
import com.gfpv.lapwing.ble.FirmwareImage
import com.gfpv.lapwing.ble.FoundTimer
import com.gfpv.lapwing.ble.TimerConfig
import com.gfpv.lapwing.ble.compareVersions
import com.gfpv.lapwing.ble.scanTimers
import com.gfpv.lapwing.race.RacePhase
import com.gfpv.lapwing.service.TimerService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.mapLatest
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

/** Firmware bundled in the APK that the connected timer could install. [newer] is false for a "dev" timer. */
data class FirmwareOffer(val image: FirmwareImage, val version: String, val current: String, val newer: Boolean)

@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
class MainViewModel(app: Application) : AndroidViewModel(app) {
    private val container = (app as LapwingApplication).container
    private val prefs = app.getSharedPreferences("lapwing", Context.MODE_PRIVATE)

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

    /**
     * The configuration the screens show and edit: the timer's, plus any edit not written yet.
     * Compose state rather than a flow so text fields bound to it update synchronously.
     */
    var config: TimerConfig? by mutableStateOf(connection.config.value)
        private set

    /** True while an edit has not reached the timer yet: the timer's older value must not overwrite it. */
    private var editPending = false
    private val configEdits = MutableSharedFlow<TimerConfig>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)

    init {
        viewModelScope.launch {
            connection.config.collect { if (!editPending) config = it }
        }
        // Saved automatically once edits pause, so dragging a slider or typing is one write, not dozens.
        viewModelScope.launch {
            configEdits.debounce(CONFIG_SAVE_DELAY_MS).collect { edit ->
                try {
                    connection.writeConfig(edit)
                    if (config == edit) editPending = false
                } catch (e: Exception) {
                    editPending = false
                    config = connection.config.value
                    _messages.value = "Could not save: ${e.message}"
                }
            }
        }
    }

    fun updateConfig(edit: TimerConfig) {
        if (edit == config) return
        editPending = true
        config = edit
        configEdits.tryEmit(edit)
    }

    /**
     * Band picked in the channel selector, remembered on the phone. Needed because some
     * frequencies are in two bands (R7 and F8 are both 5880 MHz) and the timer only stores the frequency.
     */
    private val _preferredBand = MutableStateFlow(prefs.getInt("band", -1).takeIf { it >= 0 })
    val preferredBand: StateFlow<Int?> = _preferredBand.asStateFlow()

    fun setPreferredBand(band: Int) {
        _preferredBand.value = band
        prefs.edit().putInt("band", band).apply()
    }

    fun testVoice(config: TimerConfig) {
        container.scope.launch {
            val a = container.announcer
            a.rate = config.announcerRate
            a.speak(a.language.testVoice(config.pilotName))
            for (i in 1..3) a.speak("$i")
        }
    }

    // ---- voice language (phone setting) ----

    val voiceLanguage: StateFlow<VoiceLanguage> = container.voicePrefs.language

    fun setVoiceLanguage(language: VoiceLanguage) {
        container.voicePrefs.setLanguage(language)
    }

    suspend fun isVoiceAvailable(language: VoiceLanguage): Boolean = container.announcer.isAvailable(language)

    // ---- custom callout texts (phone setting, per language) ----

    val customCallouts: StateFlow<Map<VoiceLanguage, Map<Callout, String>>> = container.voicePrefs.custom

    fun setCustomCallout(language: VoiceLanguage, callout: Callout, text: String) =
        container.voicePrefs.setCustom(language, callout, text)

    fun resetCallouts(language: VoiceLanguage) = container.voicePrefs.resetCustom(language)

    /** Speaks one callout with sample values, as it would sound during a race. */
    fun testCallout(callout: Callout, config: TimerConfig) {
        container.scope.launch {
            val a = container.announcer
            a.rate = config.announcerRate
            val sampleMs = if (callout == Callout.LAPS) 70_850L else 23_450L
            a.speak(a.callouts.text(callout, pilot = config.pilotName, lap = 3, count = 3, ms = sampleMs))
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

    /** Firmware bundled in this APK for the connected timer, when it differs from what the timer runs. */
    val firmwareOffer: StateFlow<FirmwareOffer?> = connection.info
        .mapLatest { info -> info?.let { findFirmwareOffer(it) } }
        .stateIn(viewModelScope, SharingStarted.Eagerly, null)

    private suspend fun findFirmwareOffer(info: DeviceInfo): FirmwareOffer? = withContext(Dispatchers.IO) {
        val ctx = getApplication<Application>()
        val bundled = BundledFirmware.version(ctx) ?: return@withContext null
        if (bundled == info.firmware) return@withContext null
        val order = compareVersions(bundled, info.firmware)
        if (order != null && order < 0) return@withContext null // the timer is newer than this app
        val image = BundledFirmware.forChip(ctx, info.chip, info.board) ?: return@withContext null
        FirmwareOffer(image, bundled, info.firmware, newer = order != null)
    }

    private val _dismissedFirmware = MutableStateFlow(prefs.getString("dismissedFirmware", null))

    /** A newer bundled firmware to propose right away, unless this version was already declined. */
    val firmwarePrompt: StateFlow<FirmwareOffer?> = combine(firmwareOffer, _dismissedFirmware, race.state, _ota) { offer, dismissed, r, ota ->
        // Not during a race, nor during/after an update: the timer reports its new version only once it reconnects.
        offer?.takeIf { it.newer && it.version != dismissed && r.phase == RacePhase.IDLE && ota == OtaState.Idle }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, null)

    /** "Later": stop proposing this version on connect; it stays available in Settings. */
    fun dismissFirmwarePrompt(offer: FirmwareOffer) {
        _dismissedFirmware.value = offer.version
        prefs.edit().putString("dismissedFirmware", offer.version).apply()
    }

    companion object {
        const val RSSI_HISTORY = 300 // 30 s at 10 Hz
        const val CONFIG_SAVE_DELAY_MS = 400L
    }
}
