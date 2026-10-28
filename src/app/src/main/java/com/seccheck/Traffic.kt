package com.seccheck

import android.app.AppOpsManager
import android.app.usage.NetworkStats
import android.app.usage.NetworkStatsManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Process
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

data class AppTraffic(val pkg: String, val label: String, val rx: Long, val tx: Long, val wifi: Long, val mobile: Long) {
    val total get() = rx + tx
}

class YtReport(
    val pkgs: List<String>, val hourly: DoubleArray, val rx: Long, val tx: Long,
    val wifi: Long, val mobile: Long, val fgMs: Long, val mods: List<String>,
)

class TrafficReport(val apps: List<AppTraffic>, val youtube: YtReport, val browsers: List<AppTraffic>)

private val YT_PKGS = listOf(
    "com.google.android.youtube", "com.google.android.apps.youtube.music", "com.google.android.apps.youtube.kids",
)
private val YT_MODS = listOf(
    "app.revanced.android.youtube", "com.vanced.android.youtube",
    "app.revanced.android.apps.youtube.music", "com.vanced.android.apps.youtube.music",
)

/** 全部讀系統的流量統計(NetworkStatsManager),不需要網路權限。 */
@Suppress("DEPRECATION")
object Traffic {
    fun hasUsageAccess(c: Context): Boolean {
        val ao = c.getSystemService(AppOpsManager::class.java)
        val mode = if (Build.VERSION.SDK_INT >= 29)
            ao.unsafeCheckOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        else ao.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), c.packageName)
        return mode == AppOpsManager.MODE_ALLOWED
    }

    fun appLabel(c: Context, pkg: String): String = when {
        pkg == "?" -> "未知 / Unknown"
        pkg == "android" -> "Android System"
        pkg == "uid:removed" -> "已移除的 App / Removed apps"
        pkg == "uid:tethering" -> "網路共享 / Tethering"
        pkg.startsWith("uid:") -> pkg
        else -> runCatching {
            c.packageManager.getApplicationLabel(c.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)
    }

    fun browserPkgs(c: Context): Set<String> {
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        return c.packageManager.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.toSet()
    }

    private fun installed(c: Context, p: String) = runCatching { c.packageManager.getPackageInfo(p, 0) }.isSuccess

    private val TYPES = listOf(ConnectivityManager.TYPE_WIFI, ConnectivityManager.TYPE_MOBILE)

    private fun perApp(c: Context, start: Long, end: Long): List<AppTraffic> {
        val nsm = c.getSystemService(NetworkStatsManager::class.java)
        val pm = c.packageManager
        val acc = HashMap<Int, LongArray>()          // uid -> [rxWifi, txWifi, rxMobile, txMobile]
        TYPES.forEachIndexed { idx, type ->
            val st = runCatching { nsm.querySummary(type, null, start, end) }.getOrNull() ?: return@forEachIndexed
            try {
                val b = NetworkStats.Bucket()
                while (st.hasNextBucket()) {
                    st.getNextBucket(b)
                    val a = acc.getOrPut(b.uid) { LongArray(4) }
                    a[idx * 2] += b.rxBytes
                    a[idx * 2 + 1] += b.txBytes
                }
            } finally { st.close() }
        }
        val byPkg = HashMap<String, LongArray>()
        for ((uid, a) in acc) {
            val pkg = when (uid) {
                NetworkStats.Bucket.UID_REMOVED -> "uid:removed"
                NetworkStats.Bucket.UID_TETHERING -> "uid:tethering"
                1000 -> "android"
                else -> pm.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid"
            }
            val t = byPkg.getOrPut(pkg) { LongArray(4) }
            for (k in 0..3) t[k] += a[k]
        }
        return byPkg.map { (p, a) -> AppTraffic(p, appLabel(c, p), a[0] + a[2], a[1] + a[3], a[0] + a[1], a[2] + a[3]) }
            .filter { it.total > 0 }.sortedByDescending { it.total }
    }

    /** 把某個 App 的流量依「一天中的小時」累加(系統統計通常是 1–2 小時一格,按比例攤到各小時)。 */
    private fun addHourly(c: Context, uid: Int, start: Long, end: Long, out: DoubleArray) {
        val nsm = c.getSystemService(NetworkStatsManager::class.java)
        val zone = ZoneId.systemDefault()
        for (type in TYPES) {
            val st = runCatching { nsm.queryDetailsForUid(type, null, start, end, uid) }.getOrNull() ?: continue
            try {
                val b = NetworkStats.Bucket()
                while (st.hasNextBucket()) {
                    st.getNextBucket(b)
                    val bytes = (b.rxBytes + b.txBytes).toDouble()
                    if (bytes <= 0) continue
                    val bs = b.startTimeStamp
                    val be = b.endTimeStamp
                    if (be <= bs) { out[Instant.ofEpochMilli(bs).atZone(zone).hour] += bytes; continue }
                    var t = bs
                    while (t < be) {
                        val z = Instant.ofEpochMilli(t).atZone(zone)
                        val next = z.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant().toEpochMilli()
                        val e = minOf(be, next)
                        out[z.hour] += bytes * (e - t).toDouble() / (be - bs)
                        t = e
                    }
                }
            } finally { st.close() }
        }
    }

    fun report(c: Context, hours: Int): TrafficReport {
        val end = System.currentTimeMillis()
        val start = end - hours * 3_600_000L
        val apps = perApp(c, start, end)
        val ytPkgs = (YT_PKGS + YT_MODS).filter { installed(c, it) }
        val hourly = DoubleArray(24)
        for (p in ytPkgs) runCatching { c.packageManager.getApplicationInfo(p, 0).uid }.getOrNull()
            ?.let { addHourly(c, it, start, end, hourly) }
        val fg = runCatching {
            c.getSystemService(UsageStatsManager::class.java).queryAndAggregateUsageStats(start, end)
        }.getOrNull()
        val fgMs = ytPkgs.sumOf { fg?.get(it)?.totalTimeInForeground ?: 0L }
        val yt = apps.filter { it.pkg in ytPkgs }
        val br = browserPkgs(c)
        return TrafficReport(
            apps,
            YtReport(ytPkgs, hourly, yt.sumOf { it.rx }, yt.sumOf { it.tx }, yt.sumOf { it.wifi }, yt.sumOf { it.mobile },
                fgMs, ytPkgs.filter { it in YT_MODS }),
            apps.filter { it.pkg in br },
        )
    }
}
