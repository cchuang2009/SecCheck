package com.seccheck

import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

private fun fmtDur(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}h ${m % 60}m" else "${m}m"
}

private fun appPetals(list: List<AppTraffic>, others: String): List<Petal> {
    fun disp(a: AppTraffic) = "${fmtBytes(a.total)} (↓${fmtBytes(a.rx)} ↑${fmtBytes(a.tx)})"
    val head = list.take(8).mapIndexed { i, a -> Petal(a.label, a.total.toDouble(), PALETTE[i % PALETTE.size], disp(a)) }
    val rest = list.drop(8).sumOf { it.total }
    return if (rest > 0) head + Petal(others, rest.toDouble(), Color.Gray, fmtBytes(rest)) else head
}

@Composable
fun TrafficScreen(modifier: Modifier = Modifier) {
    val ctx = LocalContext.current
    var access by remember { mutableStateOf(Traffic.hasUsageAccess(ctx)) }
    var hours by remember { mutableIntStateOf(24) }
    var report by remember { mutableStateOf<TrafficReport?>(null) }
    var loading by remember { mutableStateOf(false) }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { access = Traffic.hasUsageAccess(ctx) }
    LaunchedEffect(access, hours) {
        if (access) {
            loading = true
            report = withContext(Dispatchers.Default) { Traffic.report(ctx, hours) }
            loading = false
        }
    }

    Column(modifier.verticalScroll(rememberScrollState())) {
        if (!access) {
            Text(
                tr("需要「使用情況存取權」才能讀取各 App 的流量統計與 YouTube 使用時間。資料只在本機處理,不會上傳。",
                    "Usage access is required to read per-app traffic counters and YouTube usage time. Data is processed on-device only."),
                style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(vertical = 12.dp)
            )
            Button(onClick = {
                runCatching { ctx.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
            }) { Text(tr("開啟使用情況存取權設定", "Open usage access settings")) }
            return@Column
        }

        Row {
            listOf(24 to "24 h", 168 to "7 d").forEach { (h, label) ->
                TextButton(onClick = { hours = h }) {
                    Text(label, fontWeight = if (hours == h) FontWeight.Bold else FontWeight.Normal)
                }
            }
        }
        if (loading) LinearProgressIndicator(Modifier.fillMaxWidth())
        Text(
            tr("玫瑰圖:花瓣面積正比於數值。", "Rose charts: petal area is proportional to the value."),
            style = MaterialTheme.typography.bodySmall
        )

        report?.let { r ->
            // 1. 各 App
            RoseBlock(tr("各 App 流量(Wi-Fi + 行動數據)", "Per-app traffic (Wi-Fi + mobile)"), appPetals(r.apps, tr("其他", "Others")))
            val heavy = r.apps.filter { it.tx > 20L * 1024 * 1024 && it.tx > it.rx }
            if (heavy.isNotEmpty()) {
                Text(
                    tr("⚠ 上傳量大於下載量的 App:", "⚠ Upload-heavy apps: ") + heavy.joinToString("、") { "${it.label} ↑${fmtBytes(it.tx)}" } +
                        tr("。雲端備份、直播屬正常;不認得的 App 請檢查。", ". Normal for cloud backup or live streaming; check any app you don't recognize."),
                    style = MaterialTheme.typography.bodySmall, color = Level.WARN.let { Color(0xFFEF6C00) },
                    modifier = Modifier.padding(top = 6.dp)
                )
            }

            // 2. YouTube
            val y = r.youtube
            Text(tr("YouTube 存取分析", "YouTube access analysis"), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 20.dp))
            if (y.pkgs.isEmpty()) {
                Text(tr("未安裝 YouTube 相關 App。", "No YouTube app installed."), style = MaterialTheme.typography.bodySmall)
            } else {
                Text(
                    tr("流量 ${fmtBytes(y.rx + y.tx)}(↓${fmtBytes(y.rx)} ↑${fmtBytes(y.tx)})· Wi-Fi ${fmtBytes(y.wifi)} · 行動數據 ${fmtBytes(y.mobile)} · 前景使用 ${fmtDur(y.fgMs)}",
                        "Traffic ${fmtBytes(y.rx + y.tx)} (↓${fmtBytes(y.rx)} ↑${fmtBytes(y.tx)}) · Wi-Fi ${fmtBytes(y.wifi)} · Mobile ${fmtBytes(y.mobile)} · Foreground ${fmtDur(y.fgMs)}"),
                    style = MaterialTheme.typography.bodySmall
                )
                RoseBlock(
                    tr("一天中各時段的 YouTube 流量(0–23 時)", "YouTube traffic by hour of day (0–23)"),
                    y.hourly.mapIndexed { h, v -> Petal(h.toString(), v, Color.hsv(h * 15f, 0.55f, 0.9f)) },
                    labelEvery = 3, legend = false
                )
                Text(
                    tr("時段由系統流量統計(約 1–2 小時一格)按比例推算。", "Hours are estimated from system traffic stats (about 1–2 h granularity)."),
                    style = MaterialTheme.typography.bodySmall
                )
                if (y.mods.isNotEmpty()) {
                    val ctxLocal = LocalContext.current
                    Text(
                        tr("⚠ 偵測到非官方 YouTube 修改版:", "⚠ Unofficial YouTube mod detected: ") + y.mods.joinToString("、") { Traffic.appLabel(ctxLocal, it) } +
                            tr("。來源不明的修改版可能夾帶惡意程式,建議改用官方版。", ". Mods from unknown sources may carry malware; prefer the official app."),
                        style = MaterialTheme.typography.bodySmall, color = Color(0xFFC62828), modifier = Modifier.padding(top = 6.dp)
                    )
                }
            }

            // 3. 瀏覽器
            RoseBlock(tr("各瀏覽器流量", "Traffic per browser"), appPetals(r.browsers, tr("其他", "Others")))
        }

        // 4. 目的地網域(Net 版才有)
        DomainPanel(hours)
        Spacer(Modifier.height(24.dp))
    }
}
