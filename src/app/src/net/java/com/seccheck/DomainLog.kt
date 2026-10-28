package com.seccheck

import android.content.Context
import java.io.File

data class Entry(val ts: Long, val pkg: String, val domain: String)

/** DNS 查詢紀錄:只存在 App 私有空間 filesDir,保留 7 天,可清除。格式:時間\tApp 套件\t網域 */
class DomainLog(c: Context) {
    private val f = File(c.filesDir, FILE)
    private val recent = HashMap<String, Long>()

    @Synchronized
    fun add(ts: Long, pkg: String, domain: String) {
        val k = "$pkg|$domain"
        val last = recent[k]
        if (last != null && ts - last < 3000) return        // A / AAAA / HTTPS 會連發,3 秒內只算一次
        recent[k] = ts
        if (recent.size > 5000) recent.clear()
        f.appendText("$ts\t$pkg\t$domain\n")
        if (f.length() > 8_000_000) {
            val lines = f.readLines()
            f.writeText(lines.drop(lines.size / 2).joinToString("\n") + "\n")
        }
    }

    companion object {
        private const val FILE = "dnslog.tsv"

        fun read(c: Context, sinceMs: Long): List<Entry> {
            val f = File(c.filesDir, FILE)
            if (!f.exists()) return emptyList()
            return f.useLines { seq ->
                seq.mapNotNull { ln ->
                    val p = ln.split('\t')
                    if (p.size == 3) p[0].toLongOrNull()?.let { ts -> if (ts >= sinceMs) Entry(ts, p[1], p[2]) else null } else null
                }.toList()
            }
        }

        fun clear(c: Context) { File(c.filesDir, FILE).delete() }

        fun purge(c: Context, keepMs: Long) {
            val f = File(c.filesDir, FILE)
            if (!f.exists()) return
            val min = System.currentTimeMillis() - keepMs
            val keep = f.readLines().filter { (it.substringBefore('\t').toLongOrNull() ?: 0L) >= min }
            f.writeText(if (keep.isEmpty()) "" else keep.joinToString("\n") + "\n")
        }
    }
}
