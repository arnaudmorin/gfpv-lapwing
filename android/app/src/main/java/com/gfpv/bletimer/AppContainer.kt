package com.gfpv.bletimer

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import com.gfpv.bletimer.audio.Announcer
import com.gfpv.bletimer.audio.VoicePrefs
import com.gfpv.bletimer.ble.TimerConnection
import com.gfpv.bletimer.data.RaceDatabase
import com.gfpv.bletimer.race.RaceController
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Application wide singletons: the BLE link and the race outlive activities so racing works with the screen off. */
class AppContainer(app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val database = RaceDatabase.create(app)
    val connection = TimerConnection(app, scope)
    val voicePrefs = VoicePrefs(app)
    val announcer = Announcer(app, voicePrefs)
    val race = RaceController(scope, connection, announcer, database.raceDao())
}

class PhobosApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(NOTIFICATION_CHANNEL, getString(R.string.channel_name), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val NOTIFICATION_CHANNEL = "timer"
    }
}
