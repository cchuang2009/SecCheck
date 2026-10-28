package com.seccheck

import android.content.Context
import android.net.Uri
import android.os.Build
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 使用者匯入的黑名單,只存在 App 私有空間(filesDir)。 */
object BlocklistStore {
    private val VALID = Regex("^(pkg:[A-Za-z0-9_.]+|sha256:[0-9a-fA-F]{64})$")
    private fun file(c: Context) = File(c.filesDir, "blocklist_user.txt")

    fun userCount(c: Context): Int =
        runCatching { file(c).readLines().count { it.isNotBlank() } }.getOrDefault(0)

    /** 回傳新增筆數;格式錯誤的行會被忽略。 */
    fun import(c: Context, uri: Uri): Int {
        val incoming = c.contentResolver.openInputStream(uri)!!.bufferedReader().useLines { seq ->
            seq.take(100_000).map { it.trim() }.filter { VALID.matches(it) }
                .map { if (it.startsWith("sha256:", ignoreCase = true)) "sha256:" + it.substring(7).lowercase() else it }
                .toList()
        }
        val f = file(c)
        val existing = if (f.exists()) f.readLines().map { it.trim() }.filter { it.isNotEmpty() } else emptyList()
        val merged = (existing + incoming).distinct()
        f.writeText(merged.joinToString("\n") + "\n")
        return merged.size - existing.size
    }
}

/** 純文字報告,透過系統分享送出,由使用者決定要給誰。 */
object Report {
    fun build(c: Context, zh: Boolean, results: List<Finding>, score: Int): String {
        fun t(z: String, e: String) = if (zh) z else e
        val pm = c.packageManager
        val ver = runCatching { pm.getPackageInfo(c.packageName, 0).versionName }.getOrNull() ?: "?"
        val sb = StringBuilder()
        sb.appendLine("SecCheck $ver — ${t("安全檢測報告", "Security report")}")
        sb.appendLine(SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.US).format(Date()))
        sb.appendLine("${Build.MANUFACTURER} ${Build.MODEL} · Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.VERSION.SECURITY_PATCH}")
        val risk = results.count { it.level == Level.RISK }
        val warn = results.count { it.level == Level.WARN }
        sb.appendLine(t("安全分數", "Score") + ": $score/100 · " + t("高風險 $risk · 注意 $warn", "High risk $risk · Warnings $warn"))
        for (cat in Cat.entries) {
            val list = results.filter { it.category == cat }.sortedByDescending { it.level.ordinal }
            if (list.isEmpty()) continue
            sb.appendLine().appendLine("## ${cat.title(zh)}")
            for (f in list) {
                val mark = when (f.level) { Level.RISK -> "[!!]"; Level.WARN -> "[! ]"; Level.INFO -> "[i ]"; Level.OK -> "[ok]" }
                sb.appendLine("$mark ${f.title} — ${f.detail}")
                if (f.level != Level.OK) {
                    if (f.advice.isNotEmpty()) sb.appendLine("     " + t("建議:", "Advice: ") + f.advice)
                    f.pkgs.take(30).forEach { p ->
                        val name = runCatching { pm.getApplicationLabel(pm.getApplicationInfo(p, 0)).toString() }.getOrDefault(p)
                        sb.appendLine("     • $name ($p)")
                    }
                }
            }
        }
        sb.appendLine().appendLine(t("由 SecCheck 在本機產生,未上傳任何資料。", "Generated locally by SecCheck; nothing was uploaded."))
        return sb.toString()
    }
}
