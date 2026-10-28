package com.seccheck

import android.app.Activity
import android.content.Intent
import android.net.VpnService
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import java.time.ZoneId

/** Net 版:含 INTERNET 權限(只用於轉送 DNS 查詢)與 DNS 分析 VPN。 */
object DomainFeature { const val AVAILABLE = true }

object DomainUtil {
    private val SLD = setOf("co", "com", "net", "org", "gov", "edu", "ac", "ne", "or")
    private val YT = listOf(
        "youtube.com", "youtu.be", "googlevideo.com", "ytimg.com",
        "youtube-nocookie.com", "youtubei.googleapis.com", "yt3.ggpht.com",
    )

    /** 粗略取「主網域」:a.b.example.com → example.com;x.example.com.tw → example.com.tw */
    fun registrable(d: String): String {
        val l = d.split('.').filter { it.isNotEmpty() }
        val n = l.size
        if (n <= 2) return d
        return if (n >= 3 && l[n - 1].length == 2 && l[n - 2] in SLD) l.takeLast(3).joinToString(".")
        else l.takeLast(2).joinToString(".")
    }

    fun isYouTube(d: String) = YT.any { d == it || d.endsWith(".$it") }
}

private fun top(counts: Map<String, Int>, n: Int, label: (String) -> String = { it }): List<Petal> {
    val s = counts.entries.sortedByDescending { it.value }
    val head = s.take(n).mapIndexed { i, e -> Petal(label(e.key), e.value.toDouble(), PALETTE[i % PALETTE.size], e.value.toString()) }
    val rest = s.drop(n).sumOf { it.value }
    return if (rest > 0) head + Petal("…", rest.toDouble(), Color.Gray, rest.toString()) else head
}

@Composable
fun DomainPanel(hours: Int) {
    val ctx = LocalContext.current
    var on by remember { mutableStateOf(DnsVpnService.active) }
    var tick by remember { mutableIntStateOf(0) }
    var entries by remember { mutableStateOf<List<Entry>>(emptyList()) }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { res ->
        if (res.resultCode == Activity.RESULT_OK) {
            ctx.startService(Intent(ctx, DnsVpnService::class.java)); on = true
        }
    }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { on = DnsVpnService.active; tick++ }
    LaunchedEffect(hours, tick) {
        entries = withContext(Dispatchers.IO) { DomainLog.read(ctx, System.currentTimeMillis() - hours * 3_600_000L) }
    }

    Card(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(tr("目的地(網域)分析", "Destination (domain) analysis"), style = MaterialTheme.typography.titleSmall)
            Text(
                tr("用只攔截 DNS 的本機 VPN,記錄哪個 App 查詢了哪些網域。其他流量不經過 VPN。紀錄只存在本機、保留 7 天,可隨時清除。此處統計的是 DNS 查詢次數,不是流量位元組;使用 DoH/DoT(如 Chrome 安全 DNS)的連線不會被記錄。",
                    "A DNS-only local VPN records which app resolved which domains; other traffic does not pass through it. Records stay on this device for 7 days and can be cleared anytime. Counts are DNS lookups, not bytes; connections using DoH/DoT (e.g. Chrome Secure DNS) are not recorded."),
                style = MaterialTheme.typography.bodySmall
            )
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                Button(onClick = {
                    if (on) {
                        ctx.startService(Intent(ctx, DnsVpnService::class.java).setAction(DnsVpnService.ACTION_STOP)); on = false
                    } else {
                        val i = VpnService.prepare(ctx)
                        if (i != null) launcher.launch(i)
                        else { ctx.startService(Intent(ctx, DnsVpnService::class.java)); on = true }
                    }
                }) { Text(if (on) tr("停止分析", "Stop") else tr("開始分析", "Start")) }
                TextButton(onClick = { tick++ }) { Text(tr("重新整理", "Refresh")) }
                TextButton(onClick = { DomainLog.clear(ctx); tick++ }) { Text(tr("清除紀錄", "Clear log")) }
            }

            if (entries.isEmpty()) {
                Text(
                    tr("尚無紀錄。按「開始分析」後使用瀏覽器或 YouTube,再回來重新整理。", "No records yet. Tap Start, use your browser or YouTube, then refresh."),
                    style = MaterialTheme.typography.bodySmall
                )
            } else {
                val br = remember { Traffic.browserPkgs(ctx) }
                val browserDomains = entries.filter { it.pkg in br }.groupingBy { DomainUtil.registrable(it.domain) }.eachCount()
                RoseBlock(tr("瀏覽器連線的主要網域(查詢次數)", "Top domains from browsers (lookups)"), top(browserDomains, 10))

                val yt = entries.filter { DomainUtil.isYouTube(it.domain) }
                RoseBlock(
                    tr("誰在存取 YouTube(依 App,查詢次數)", "Who accesses YouTube (by app, lookups)"),
                    top(yt.groupingBy { it.pkg }.eachCount(), 8) { Traffic.appLabel(ctx, it) }
                )
                val hourly = DoubleArray(24)
                val zone = ZoneId.systemDefault()
                yt.forEach { hourly[Instant.ofEpochMilli(it.ts).atZone(zone).hour] += 1.0 }
                RoseBlock(
                    tr("YouTube 存取時段(0–23 時)", "YouTube access by hour (0–23)"),
                    hourly.mapIndexed { h, v -> Petal(h.toString(), v, Color.hsv(h * 15f, 0.55f, 0.9f)) },
                    labelEvery = 3, legend = false
                )

                RoseBlock(
                    tr("各 App 的 DNS 查詢量", "DNS lookups per app"),
                    top(entries.groupingBy { it.pkg }.eachCount(), 8) { Traffic.appLabel(ctx, it) }
                )
            }
        }
    }
}
