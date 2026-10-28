package com.seccheck

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { MaterialTheme { Screen() } }
    }
}

val LocalZh = compositionLocalOf { true }

/** 依目前語言挑中文或英文。 */
@Composable
fun tr(z: String, e: String) = if (LocalZh.current) z else e

private fun Level.color() = when (this) {
    Level.OK -> Color(0xFF2E7D32)
    Level.INFO -> Color(0xFF1565C0)
    Level.WARN -> Color(0xFFEF6C00)
    Level.RISK -> Color(0xFFC62828)
}

internal fun Cat.title(zh: Boolean) = when (this) {
    Cat.MALWARE -> if (zh) "可疑 App(病毒 / 惡意行為)" else "Suspicious apps (malware / risky behavior)"
    Cat.SYSTEM -> if (zh) "系統" else "System"
    Cat.APPS -> "Apps"
    Cat.NETWORK -> if (zh) "網路" else "Network"
    Cat.BROWSER -> if (zh) "瀏覽器" else "Browsers"
    Cat.ERROR -> if (zh) "錯誤" else "Errors"
}

private fun Context.open(i: Intent) {
    i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    try { startActivity(i) } catch (_: Exception) {
        runCatching { startActivity(Intent(Settings.ACTION_SETTINGS).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
    }
}

@Composable
fun Screen() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val prefs = remember { Prefs(ctx) }
    var lang by remember { mutableStateOf(prefs.lang) }
    var bg by remember { mutableStateOf(prefs.background) }
    var results by remember { mutableStateOf<List<Finding>>(emptyList()) }
    var scanning by remember { mutableStateOf(false) }
    var tab by remember { mutableIntStateOf(0) }
    val gen = remember { intArrayOf(0) }
    val zh = Prefs.resolveZh(lang)

    val notifPerm = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun scan() {
        val g = ++gen[0]
        val z = zh
        scanning = true
        scope.launch {
            val r = withContext(Dispatchers.Default) { Scanner(ctx, z).runAll() }
            if (g == gen[0]) { results = r; scanning = false }  // 只採用最新一次掃描結果
        }
    }
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            val n = runCatching { BlocklistStore.import(ctx, uri) }.getOrDefault(-1)
            Toast.makeText(
                ctx,
                if (n < 0) (if (zh) "匯入失敗" else "Import failed") else if (zh) "已匯入 $n 筆新項目" else "Imported $n new entries",
                Toast.LENGTH_LONG
            ).show()
            scan()
        }
    }
    // 開啟、從系統設定頁回來、切換語言時自動重掃
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { scan() }
    LaunchedEffect(zh) { scan() }
    LaunchedEffect(Unit) { Bg.apply(ctx, prefs.background) }

    val risk = results.count { it.level == Level.RISK }
    val warn = results.count { it.level == Level.WARN }
    val score = (100 - risk * 20 - warn * 6).coerceAtLeast(0)
    val scoreColor = when { score >= 80 -> Level.OK; score >= 50 -> Level.WARN; else -> Level.RISK }.color()

    CompositionLocalProvider(LocalZh provides zh) {
        Column(Modifier.systemBarsPadding().padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("SecCheck", style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
                listOf("auto" to "Auto", "zh" to "中文", "en" to "EN").forEach { (k, label) ->
                    TextButton(onClick = { lang = k; prefs.lang = k }) {
                        Text(label, fontWeight = if (lang == k) FontWeight.Bold else FontWeight.Normal)
                    }
                }
            }
            Text(
                tr(if (DomainFeature.AVAILABLE) "本機掃描,不上傳任何資料(Net 版只為轉送 DNS 查詢使用網路)。" else "本機掃描,App 沒有網路權限,不會上傳任何資料。",
                    if (DomainFeature.AVAILABLE) "Scans locally and uploads nothing (the Net edition uses the network only to forward DNS queries)." else "Scans locally. The app has no internet permission and uploads nothing.") +
                    tr(" 判斷方式為行為特徵 + 本機黑名單,不是防毒引擎,無法保證沒有病毒。",
                        " It uses behavior heuristics + a local blocklist — not an antivirus engine, so it can't guarantee a clean phone."),
                style = MaterialTheme.typography.bodySmall
            )
            TabRow(selectedTabIndex = tab) {
                Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text(tr("安全檢測", "Security")) })
                Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text(tr("流量分析", "Traffic")) })
            }
            if (tab == 1) { TrafficScreen(Modifier.weight(1f)); return@Column }
            Row(Modifier.padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text("$score", fontSize = 44.sp, color = scoreColor)
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(tr("安全分數 / 100", "Security score / 100"))
                    Text(tr("高風險 $risk · 注意 $warn", "High risk $risk · Warnings $warn"),
                        color = if (risk > 0) Level.RISK.color() else Color.Unspecified)
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                Switch(checked = bg, onCheckedChange = { on ->
                    bg = on; prefs.background = on; Bg.apply(ctx, on)
                    if (on && Build.VERSION.SDK_INT >= 33 &&
                        ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                    ) notifPerm.launch(Manifest.permission.POST_NOTIFICATIONS)
                })
                Spacer(Modifier.width(8.dp))
                Text(tr("背景自動檢查(每 12 小時,有新風險才通知)", "Background check every 12 h (notifies only on new risks)"),
                    style = MaterialTheme.typography.bodySmall)
            }
            Button(onClick = { scan() }, enabled = !scanning) {
                Text(if (scanning) tr("撈取中…", "Scanning…") else tr("撈病毒(重新掃描)", "Scan now"))
            }
            if (scanning) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top = 4.dp))
            Row(Modifier.horizontalScroll(rememberScrollState())) {
                TextButton(enabled = results.isNotEmpty(), onClick = {
                    val send = Intent(Intent.ACTION_SEND).apply {
                        type = "text/plain"
                        putExtra(Intent.EXTRA_SUBJECT, "SecCheck report")
                        putExtra(Intent.EXTRA_TEXT, Report.build(ctx, zh, results, score))
                    }
                    runCatching { ctx.startActivity(Intent.createChooser(send, null)) }
                }) { Text(tr("匯出報告", "Export report")) }
                TextButton(onClick = { importer.launch(arrayOf("text/*")) }) {
                    Text(tr("匯入黑名單", "Import blocklist") + " (${BlocklistStore.userCount(ctx)})")
                }
                TextButton(enabled = prefs.trusted.isNotEmpty(), onClick = { prefs.trusted = emptySet(); scan() }) {
                    Text(tr("清除信任名單", "Reset trusted") + " (${prefs.trusted.size})")
                }
            }

            LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val groups = results.groupBy { it.category }
                for (cat in Cat.entries) {
                    val list = groups[cat] ?: continue
                    item { Text(cat.title(zh), style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 12.dp)) }
                    items(list.sortedByDescending { it.level.ordinal }) { f ->
                        FindingCard(f) { pkg -> prefs.trusted = prefs.trusted + pkg; scan() }
                    }
                }
            }
        }
    }
}

@Composable
fun FindingCard(f: Finding, onTrust: (String) -> Unit = {}) {
    val ctx = LocalContext.current
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp)) {
            Text("● ${f.title}", color = f.level.color(), style = MaterialTheme.typography.titleSmall)
            Text(f.detail, style = MaterialTheme.typography.bodySmall)
            if (f.level != Level.OK) {
                if (f.advice.isNotEmpty())
                    Text(tr("建議:", "Advice: ") + f.advice, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 4.dp))
                f.pkgs.take(12).forEach { p -> AppRow(p, if (f.category == Cat.MALWARE) ({ onTrust(p) }) else null) }
                if (f.pkgs.size > 12) Text(tr("…另有 ${f.pkgs.size - 12} 個", "…and ${f.pkgs.size - 12} more"), style = MaterialTheme.typography.bodySmall)
                f.fix?.let { fx -> OutlinedButton(onClick = { ctx.open(fx.intent) }) { Text(fx.label) } }
            }
        }
    }
}

@Composable
fun AppRow(pkg: String, onTrust: (() -> Unit)? = null) {
    val ctx = LocalContext.current
    val name = remember(pkg) {
        runCatching { ctx.packageManager.getApplicationLabel(ctx.packageManager.getApplicationInfo(pkg, 0)).toString() }
            .getOrDefault(pkg)
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(name, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium, maxLines = 1)
        TextButton(onClick = {
            ctx.open(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$pkg")))
        }) { Text(tr("設定", "Settings")) }
        TextButton(onClick = {
            ctx.open(Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg")))
        }) { Text(tr("解除安裝", "Uninstall")) }
        onTrust?.let { cb -> TextButton(onClick = cb) { Text(tr("信任", "Trust")) } }
    }
}
