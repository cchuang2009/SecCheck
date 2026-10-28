package com.seccheck

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.Locale
import java.util.concurrent.TimeUnit

class Prefs(c: Context) {
    private val sp = c.getSharedPreferences("seccheck", Context.MODE_PRIVATE)

    /** "auto" | "zh" | "en" */
    var lang: String
        get() = sp.getString("lang", "auto") ?: "auto"
        set(v) { sp.edit().putString("lang", v).apply() }

    var background: Boolean
        get() = sp.getBoolean("bg", false)
        set(v) { sp.edit().putBoolean("bg", v).apply() }

    var notified: Set<String>
        get() = sp.getStringSet("notified", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("notified", v).apply() }

    /** 使用者標記為「信任」的 App,不再列為可疑(黑名單命中者除外)。 */
    var trusted: Set<String>
        get() = sp.getStringSet("trusted", emptySet()) ?: emptySet()
        set(v) { sp.edit().putStringSet("trusted", v).apply() }

    val zh: Boolean get() = resolveZh(lang)

    companion object {
        fun resolveZh(lang: String) = when (lang) {
            "zh" -> true
            "en" -> false
            else -> Locale.getDefault().language == "zh"
        }
    }
}

object Bg {
    private const val NAME = "seccheck-periodic"
    fun apply(c: Context, on: Boolean) {
        val wm = WorkManager.getInstance(c)
        if (on) wm.enqueueUniquePeriodicWork(
            NAME, ExistingPeriodicWorkPolicy.UPDATE,
            PeriodicWorkRequestBuilder<ScanWorker>(12, TimeUnit.HOURS).build()
        ) else wm.cancelUniqueWork(NAME)
    }
}

/** 每 12 小時掃一次;只有「新出現」的高風險 / 可疑 App 才通知,不重複打擾。 */
class ScanWorker(ctx: Context, p: WorkerParameters) : CoroutineWorker(ctx, p) {
    override suspend fun doWork(): Result {
        val c = applicationContext
        val prefs = Prefs(c)
        val zh = prefs.zh
        val alerts = Scanner(c, zh).runAll().filter {
            it.level == Level.RISK || (it.category == Cat.MALWARE && it.level == Level.WARN)
        }
        fun key(f: Finding) = "${f.category}|${f.title}|${f.level}"
        val fresh = alerts.filter { key(it) !in prefs.notified }
        prefs.notified = alerts.map(::key).toSet()
        if (fresh.isNotEmpty()) Notifier.post(c, zh, fresh)
        return Result.success()
    }
}

object Notifier {
    private const val CH = "alerts"

    @SuppressLint("MissingPermission")
    fun post(c: Context, zh: Boolean, fresh: List<Finding>) {
        val nm = NotificationManagerCompat.from(c)
        if (!nm.areNotificationsEnabled()) return
        c.getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CH, if (zh) "安全警示" else "Security alerts", NotificationManager.IMPORTANCE_HIGH)
        )
        val title = if (zh) "SecCheck:發現 ${fresh.size} 項新風險" else "SecCheck: ${fresh.size} new risk(s) found"
        val pi = PendingIntent.getActivity(
            c, 0, Intent(c, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(c, CH)
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle(title)
            .setContentText(fresh.first().title)
            .setStyle(NotificationCompat.BigTextStyle().bigText(fresh.take(5).joinToString("\n") { "• ${it.title}" }))
            .setContentIntent(pi)
            .setAutoCancel(true)
            .build()
        nm.notify(1001, n)
    }
}
