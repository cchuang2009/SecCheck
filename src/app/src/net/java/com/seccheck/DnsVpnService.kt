package com.seccheck

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.VpnService
import android.os.Build
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import androidx.core.app.NotificationCompat
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * 只攔截 DNS 的本機 VPN(分流):路由表只有一個假的 DNS 位址,其他流量完全不經過本 VPN。
 * 收到 DNS 查詢 → 記錄「哪個 App 查了哪個網域」→ 以受保護的 socket 轉送給原本網路的 DNS → 把回應寫回。
 */
class DnsVpnService : VpnService() {
    companion object {
        const val ACTION_STOP = "com.seccheck.STOP_DNS"
        private const val CLIENT_IP = "10.77.0.1"
        private const val DNS_IP = "10.77.0.2"
        @Volatile var active = false
    }

    @Volatile private var running = false
    private var tun: ParcelFileDescriptor? = null
    private var pool: ExecutorService? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopVpn(); stopSelf(); return START_NOT_STICKY }
        startVpn()
        return START_STICKY
    }

    override fun onRevoke() { stopVpn(); super.onRevoke() }
    override fun onDestroy() { stopVpn(); super.onDestroy() }

    private fun startVpn() {
        if (running) return
        foreground()
        DomainLog.purge(this, 7L * 24 * 3600 * 1000)
        val fd = Builder().setSession("SecCheck DNS")
            .addAddress(CLIENT_IP, 32).addDnsServer(DNS_IP).addRoute(DNS_IP, 32)
            .setBlocking(true).establish() ?: run { stopSelf(); return }
        tun = fd; running = true; active = true
        val log = DomainLog(this)
        val ex = Executors.newFixedThreadPool(4); pool = ex
        Thread({ loop(fd, ex, log) }, "dns-vpn").start()
    }

    private fun stopVpn() {
        running = false; active = false
        runCatching { tun?.close() }; tun = null
        pool?.shutdownNow(); pool = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
    }

    private fun foreground() {
        val zh = Prefs(this).zh
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel("dns", if (zh) "DNS 分析" else "DNS analysis", NotificationManager.IMPORTANCE_LOW)
        )
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, "dns")
            .setSmallIcon(R.drawable.ic_launcher_monochrome)
            .setContentTitle("SecCheck")
            .setContentText(if (zh) "正在本機記錄 DNS 查詢(目的地網域)" else "Recording DNS queries (destination domains) on this device")
            .setContentIntent(pi).setOngoing(true).build()
        runCatching {
            if (Build.VERSION.SDK_INT >= 34) startForeground(2001, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            else startForeground(2001, n)
        }
    }

    private fun loop(fd: ParcelFileDescriptor, ex: ExecutorService, log: DomainLog) {
        val input = FileInputStream(fd.fileDescriptor)
        val output = FileOutputStream(fd.fileDescriptor)
        val cm = getSystemService(ConnectivityManager::class.java)
        val buf = ByteArray(32767)
        while (running) {
            val n = try { input.read(buf) } catch (e: Exception) { break }
            if (n < 0) break
            if (n == 0) continue
            val pkt = buf.copyOf(n)
            runCatching { ex.execute { runCatching { handle(pkt, output, cm, log) } } }
        }
    }

    private fun u16(b: ByteArray, o: Int) = ((b[o].toInt() and 0xFF) shl 8) or (b[o + 1].toInt() and 0xFF)

    private fun handle(p: ByteArray, out: FileOutputStream, cm: ConnectivityManager, log: DomainLog) {
        if (p.size < 28 || (p[0].toInt() shr 4) != 4) return                 // 只處理 IPv4
        val ihl = (p[0].toInt() and 0x0F) * 4
        if (p[9].toInt() != 17 || p.size < ihl + 8) return                   // 只處理 UDP
        val srcPort = u16(p, ihl)
        val dstPort = u16(p, ihl + 2)
        if (dstPort != 53) return
        val dns = p.copyOfRange(ihl + 8, p.size)
        if (dns.size < 13 || (dns[2].toInt() and 0x80) != 0) return          // 只處理查詢
        val src = InetAddress.getByAddress(p.copyOfRange(12, 16))
        val dst = InetAddress.getByAddress(p.copyOfRange(16, 20))

        qname(dns)?.let { name ->
            val uid = if (Build.VERSION.SDK_INT >= 29) runCatching {
                cm.getConnectionOwnerUid(OsConstants.IPPROTO_UDP, InetSocketAddress(src, srcPort), InetSocketAddress(dst, dstPort))
            }.getOrDefault(-1) else -1
            val pkg = if (uid >= 0) packageManager.getPackagesForUid(uid)?.firstOrNull() ?: "uid:$uid" else "?"
            log.add(System.currentTimeMillis(), pkg, name)
        }

        val reply = runCatching {
            DatagramSocket().use { s ->
                protect(s)                                                   // 繞過本 VPN,直接走原本網路
                s.soTimeout = 4000
                s.send(DatagramPacket(dns, dns.size, upstream(cm), 53))
                val rb = ByteArray(4096)
                val rp = DatagramPacket(rb, rb.size)
                s.receive(rp)
                rb.copyOf(rp.length)
            }
        }.getOrNull() ?: return
        val resp = response(p, ihl, reply)
        synchronized(out) { out.write(resp) }
    }

    /** 找「非 VPN」網路原本使用的 DNS;找不到就用 1.1.1.1。 */
    private fun upstream(cm: ConnectivityManager): InetAddress {
        val net = cm.allNetworks.firstOrNull { n ->
            cm.getNetworkCapabilities(n)?.let {
                it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN)
            } == true
        }
        val dns = net?.let { cm.getLinkProperties(it)?.dnsServers }.orEmpty()
        return dns.firstOrNull { it is Inet4Address && !it.isLoopbackAddress } ?: dns.firstOrNull()
            ?: InetAddress.getByAddress(byteArrayOf(1, 1, 1, 1))
    }

    private fun qname(d: ByteArray): String? {
        val sb = StringBuilder()
        var i = 12
        while (i < d.size) {
            val l = d[i].toInt() and 0xFF
            if (l == 0) break
            if (l > 63 || i + 1 + l > d.size) return null
            if (sb.isNotEmpty()) sb.append('.')
            sb.append(String(d, i + 1, l, Charsets.US_ASCII))
            i += 1 + l
        }
        return sb.toString().lowercase().ifEmpty { null }
    }

    private fun csum(b: ByteArray, off: Int, len: Int): Int {
        var s = 0
        var i = off
        while (i < off + len - 1) { s += ((b[i].toInt() and 0xFF) shl 8) or (b[i + 1].toInt() and 0xFF); i += 2 }
        while ((s shr 16) != 0) s = (s and 0xFFFF) + (s shr 16)
        return s.inv() and 0xFFFF
    }

    /** 組出 IPv4 + UDP 回應封包(來源/目的對調,UDP checksum 在 IPv4 可為 0)。 */
    private fun response(req: ByteArray, ihl: Int, dns: ByteArray): ByteArray {
        val total = 28 + dns.size
        val r = ByteArray(total)
        r[0] = 0x45; r[2] = (total shr 8).toByte(); r[3] = total.toByte()
        r[6] = 0x40; r[8] = 64; r[9] = 17
        System.arraycopy(req, 16, r, 12, 4)
        System.arraycopy(req, 12, r, 16, 4)
        val ck = csum(r, 0, 20)
        r[10] = (ck shr 8).toByte(); r[11] = ck.toByte()
        r[21] = 53                                                           // 來源埠 53
        System.arraycopy(req, ihl, r, 22, 2)                                 // 目的埠 = 原本的來源埠
        val ulen = 8 + dns.size
        r[24] = (ulen shr 8).toByte(); r[25] = ulen.toByte()
        System.arraycopy(dns, 0, r, 28, dns.size)
        return r
    }
}
