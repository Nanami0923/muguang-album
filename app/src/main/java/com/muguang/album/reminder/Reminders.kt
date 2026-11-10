package com.muguang.album.reminder

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import com.muguang.album.MainActivity
import com.muguang.album.MuguangApplication
import com.muguang.album.R
import com.muguang.album.core.PlaySchedule
import com.muguang.album.playback.ScheduleCalculator
import java.time.Instant
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

object Reminders {
    private const val REQUEST_CODE = 410
    private const val CHANNEL = "album_schedule"
    private val immutableFlag get() = if (Build.VERSION.SDK_INT >= 23) PendingIntent.FLAG_IMMUTABLE else 0

    fun scheduleNext(context: Context, schedules: List<PlaySchedule>, enabled: Boolean) {
        val manager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val base = Intent(context, ReminderReceiver::class.java)
        val old = PendingIntent.getBroadcast(context, REQUEST_CODE, base, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag)
        manager.cancel(old)
        if (!enabled) return
        val next = schedules.filter { it.enabled }.mapNotNull { ScheduleCalculator.upcoming(it, count = 1).firstOrNull() }.minByOrNull { it.start } ?: return
        val intent = base.putExtra("occurrence", next.key)
        val pending = PendingIntent.getBroadcast(context, REQUEST_CODE, intent, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag)
        if (Build.VERSION.SDK_INT >= 23) manager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, next.start.toEpochMilli(), pending)
        else manager.set(AlarmManager.RTC_WAKEUP, next.start.toEpochMilli(), pending)
    }

    fun notifyIfCurrent(context: Context, schedules: List<PlaySchedule>, key: String?) {
        val current = ScheduleCalculator.current(schedules, Instant.now()) ?: return
        if (key != current.key) return
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) return
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        if (Build.VERSION.SDK_INT >= 26) manager.createNotificationChannel(NotificationChannel(CHANNEL, "相册播放提醒", NotificationManager.IMPORTANCE_DEFAULT))
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return
        val open = Intent(context, MainActivity::class.java).putExtra("enterDisplay", true)
            .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        val pending = PendingIntent.getActivity(context, 411, open, PendingIntent.FLAG_UPDATE_CURRENT or immutableFlag)
        val notice = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("到了相册播放时间")
            .setContentText("点击打开暮光相册，进入本次展示")
            .setContentIntent(pending).setAutoCancel(true).build()
        manager.notify(410, notice)
    }
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val pending = goAsync()
        val app = context.applicationContext as MuguangApplication
        app.applicationScope.launch {
            try {
                app.repository.initialize()
                val state = app.repository.state.first { it.initialized }
                if (state.preferences.remindersEnabled) Reminders.notifyIfCurrent(context, state.schedules, intent.getStringExtra("occurrence"))
                Reminders.scheduleNext(context, state.schedules, state.preferences.remindersEnabled)
            } finally { pending.finish() }
        }
    }
}

class RestoreReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED, Intent.ACTION_MY_PACKAGE_REPLACED, Intent.ACTION_TIME_CHANGED, Intent.ACTION_TIMEZONE_CHANGED)) return
        val pending = goAsync()
        val app = context.applicationContext as MuguangApplication
        app.applicationScope.launch {
            try {
                app.repository.initialize()
                val state = app.repository.state.first { it.initialized }
                Reminders.scheduleNext(context, state.schedules, state.preferences.remindersEnabled)
            } finally { pending.finish() }
        }
    }
}
