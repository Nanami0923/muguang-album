package com.muguang.album

import android.app.Application
import com.muguang.album.data.LocalAlbumRepository
import com.muguang.album.reminder.Reminders
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch

class MuguangApplication : Application() {
    val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val repository by lazy { LocalAlbumRepository(this, applicationScope) }

    override fun onCreate() {
        super.onCreate()
        applicationScope.launch {
            repository.initialize()
            repository.state.filter { it.initialized }
                .map { it.schedules to it.preferences.remindersEnabled }
                .distinctUntilChanged()
                .collect { (schedules, enabled) -> Reminders.scheduleNext(this@MuguangApplication, schedules, enabled) }
        }
    }
}
