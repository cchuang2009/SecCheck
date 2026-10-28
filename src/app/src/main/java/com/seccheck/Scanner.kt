package com.seccheck

import android.app.KeyguardManager
import android.app.admin.DevicePolicyManager
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.wifi.WifiInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import java.io.File
import java.security.KeyStore
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.TimeUnit

/** 全部在本機執行(App 沒有 INTERNET 權限)。zh=true 用中文,否則英文。 */
@Suppress("DEPRECATION")
class Scanner(internal val c: Context, internal val zh: Boolean) {
    internal val pm: PackageManager = c.packageManager
    private val dayMs = TimeUnit.DAYS.toMillis(1)
    internal fun t(z: String, e: String) = if (zh) z else e

    fun runAll(): List<Finding> =
        listOf<() -> List<Finding>>({ malware() }, { system() }, { apps() }, { network() }, { browser() })
            .flatMap { step ->
                runCatching { step() }.getOrElse {
                    listOf(Finding(Cat.ERROR, t("檢測失敗", "Check failed"), it.message ?: "", Level.INFO))
                }
            }

    // ---------- 共用 ----------
    internal val stores = setOf(
        "com.android.vending", "com.sec.android.app.samsungapps", "com.huawei.appmarket",
        "com.xiaomi.mipicks", "com.amazon.venezia", "org.fdroid.fdroid",
    )

    internal fun label(pkg: String) =
        runCatching { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() }.getOrDefault(pkg)

    internal fun installer(p: String): String? = runCatching {
        if (Build.VERSION.SDK_INT >= 30) pm.getInstallSourceInfo(p).installingPackageName
        else pm.getInstallerPackageName(p)
    }.getOrNull()

    internal fun pkgsOf(flat: String?) =
        flat.orEmpty().split(':').filter { it.contains('/') }.map { it.substringBefore('/') }.distinct()

    internal val userApps: List<PackageInfo> by lazy {
        pm.getInstalledPackages(PackageManager.GET_PERMISSIONS).filter {
            ((it.applicationInfo?.flags ?: 0) and ApplicationInfo.FLAG_SYSTEM) == 0 && it.packageName != c.packageName
        }
    }

    internal fun granted(p: PackageInfo): Set<String> {
        val perms = p.requestedPermissions ?: return emptySet()
        val flags = p.requestedPermissionsFlags ?: return emptySet()
        return perms.indices.filter { (flags[it] and PackageInfo.REQUESTED_PERMISSION_GRANTED) != 0 }.map { perms[it] }.toSet()
    }

    internal val accessibilityPkgs: List<String> by lazy {
        pkgsOf(Settings.Secure.getString(c.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES))
    }
    internal val listenerPkgs: List<String> by lazy {
        pkgsOf(Settings.Secure.getString(c.contentResolver, "enabled_notification_listeners"))
    }
    internal val adminPkgs: List<String> by lazy {
        c.getSystemService(DevicePolicyManager::class.java).activeAdmins.orEmpty().map { it.packageName }
    }

    private fun sys(action: String, z: String, e: String) = Fix(t(z, e), Intent(action))

    // ---------- 系統 / System ----------
    private fun system(): List<Finding> {
        val k = Cat.SYSTEM
        val r = mutableListOf<Finding>()
        val g = c.contentResolver

        val patch = Build.VERSION.SECURITY_PATCH
        val age = runCatching {
            (System.currentTimeMillis() - SimpleDateFormat("yyyy-MM-dd", Locale.US).parse(patch)!!.time) / dayMs
        }.getOrNull()
        val pT = t("安全性修補", "Security patch")
        val pA = t("更新系統;若廠商已停止更新,避免用這支手機做網銀/支付,或考慮換機。",
            "Install system updates. If the vendor no longer ships them, avoid banking/payments on this phone or consider replacing it.")
        val pF = sys("android.settings.SYSTEM_UPDATE_SETTINGS", "開啟系統更新", "Open system update")
        val pD = t("$patch(已 $age 天未更新)", "$patch ($age days old)")
        r += when {
            age == null -> Finding(k, pT, t("無法讀取", "Unavailable"), Level.INFO)
            age > 365 -> Finding(k, pT, pD, Level.RISK, pA, pF)
            age > 180 -> Finding(k, pT, pD, Level.WARN, pA, pF)
            else -> Finding(k, pT, t("$patch(正常)", "$patch (OK)"), Level.OK)
        }

        val secure = c.getSystemService(KeyguardManager::class.java).isDeviceSecure
        r += Finding(k, t("螢幕鎖", "Screen lock"),
            if (secure) t("已設定", "Set") else t("未設定 PIN/密碼/圖形", "Not set (no PIN/password/pattern)"),
            if (secure) Level.OK else Level.RISK,
            t("設定 6 位數以上 PIN 或強密碼,手機遺失時才擋得住。", "Use a PIN of 6+ digits or a strong password so a lost phone stays locked."),
            sys(Settings.ACTION_SECURITY_SETTINGS, "設定螢幕鎖", "Set screen lock"))

        val adb = Settings.Global.getInt(g, Settings.Global.ADB_ENABLED, 0) == 1
        val dev = Settings.Global.getInt(g, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, 0) == 1
        val devFix = sys(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS, "開發者選項", "Developer options")
        r += Finding(k, t("USB 偵錯", "USB debugging"), if (adb) t("已開啟", "Enabled") else t("關閉", "Off"),
            if (adb) Level.WARN else Level.OK,
            t("不使用時關閉,並在開發者選項按「撤銷 USB 偵錯授權」。", "Turn it off when unused and tap 'Revoke USB debugging authorizations' in Developer options."), devFix)
        r += Finding(k, t("開發者選項", "Developer options"), if (dev) t("已開啟", "Enabled") else t("關閉", "Off"),
            if (dev) Level.INFO else Level.OK, t("不是開發者就關掉。", "Turn off unless you are a developer."), devFix)

        val su = listOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/su/bin/su", "/data/local/xbin/su", "/data/local/bin/su")
            .any { File(it).exists() }
        val testKeys = Build.TAGS?.contains("test-keys") == true
        r += if (su || testKeys)
            Finding(k, "Root", t("疑似已 Root(su=$su, test-keys=$testKeys)", "Possibly rooted (su=$su, test-keys=$testKeys)"), Level.RISK,
                t("Root 會讓任何惡意 App 取得最高權限。備份資料後刷回原廠韌體;若不是你自己 Root 的,手機可能已遭入侵。",
                    "Root gives any malicious app full control. Back up and reflash stock firmware; if you did not root it yourself the phone may already be compromised."))
        else Finding(k, "Root", t("未發現", "Not found"), Level.OK)

        val verifier = Settings.Global.getInt(g, "package_verifier_enable", 1) == 1
        r += Finding(k, t("安裝前掃描 (Play Protect)", "Pre-install scanning (Play Protect)"),
            if (verifier) t("已啟用", "Enabled") else t("已關閉", "Disabled"),
            if (verifier) Level.OK else Level.WARN,
            t("Google Play → 右上頭像 → Play 保護機制 → 開啟「掃描應用程式」。",
                "Google Play → profile icon → Play Protect → turn on 'Scan apps with Play Protect'."),
            pm.getLaunchIntentForPackage("com.android.vending")?.let { Fix(t("開啟 Play 商店", "Open Play Store"), it) })
        return r
    }

    // ---------- Apps ----------
    private val danger: Map<String, Triple<String, Level, String>> by lazy {
        mapOf(
            "android.permission.READ_SMS" to Triple(t("簡訊", "SMS"), Level.WARN,
                t("簡訊可被用來攔截驗證碼。只有簡訊 App 需要;其他請關閉權限或解除安裝。",
                    "SMS can be used to intercept verification codes. Only messaging apps need it; revoke it or uninstall the rest.")),
            "android.permission.RECEIVE_SMS" to Triple(t("簡訊", "SMS"), Level.WARN, ""),
            "android.permission.SEND_SMS" to Triple(t("簡訊", "SMS"), Level.WARN, ""),
            "android.permission.READ_CALL_LOG" to Triple(t("通話紀錄", "Call log"), Level.WARN,
                t("非電話類 App 建議關閉。", "Sensitive; revoke for non-phone apps.")),
            "android.permission.RECORD_AUDIO" to Triple(t("麥克風", "Microphone"), Level.INFO,
                t("確認是通訊/錄音類 App,否則關閉。", "Make sure it's a communication/recording app, otherwise revoke.")),
            "android.permission.ACCESS_BACKGROUND_LOCATION" to Triple(t("背景定位", "Background location"), Level.WARN,
                t("非導航/運動類建議改成「僅使用期間」。", "Unless it's navigation/fitness, set it to 'While using the app'.")),
            "android.permission.SYSTEM_ALERT_WINDOW" to Triple(t("懸浮視窗(偵測可能不準)", "Draw over other apps (detection may be inaccurate)"), Level.WARN,
                t("可蓋住其他畫面偽造登入頁(釣魚),不需要請關閉。", "Can cover other screens with fake login pages (phishing). Revoke if not needed.")),
            "android.permission.REQUEST_INSTALL_PACKAGES" to Triple(t("可安裝其他 App", "Install other apps"), Level.INFO,
                t("不是安裝器類 App 建議關閉。", "Revoke unless it is an installer/app store.")),
        )
    }

    private fun apps(): List<Finding> {
        val k = Cat.APPS
        val r = mutableListOf<Finding>()
        fun count(n: Int) = t("共 $n 個 App", "$n app(s)")
        r += Finding(k, t("已安裝的使用者 App", "Installed user apps"), t("${userApps.size} 個", "${userApps.size}"), Level.INFO)

        val hit = linkedMapOf<String, MutableSet<String>>()
        for (p in userApps) for (perm in granted(p)) danger[perm]?.let { hit.getOrPut(it.first) { linkedSetOf() }.add(p.packageName) }
        val meta = danger.values.filter { it.third.isNotEmpty() }.associateBy { it.first }
        for ((name, set) in hit) {
            val m = meta.getValue(name)
            r += Finding(k, t("已授予「$name」", "Granted: $name"), count(set.size), m.second, m.third, null, set.toList())
        }

        val side = userApps.map { it.packageName }.filter { installer(it) !in stores }
        r += Finding(k, t("非商店來源安裝", "Installed outside app stores"), if (side.isEmpty()) t("無", "None") else count(side.size),
            if (side.size > 5) Level.WARN else if (side.isEmpty()) Level.OK else Level.INFO,
            t("來路不明的 APK 是中毒主因。不認得的請解除安裝。", "Unknown APKs are the main source of infections. Uninstall anything you don't recognize."), null, side)

        val old = userApps.filter { (it.applicationInfo?.targetSdkVersion ?: 99) < 28 }.map { it.packageName }
        r += Finding(k, t("過舊 targetSdk (<28)", "Outdated targetSdk (<28)"), if (old.isEmpty()) t("無", "None") else count(old.size),
            if (old.isEmpty()) Level.OK else Level.WARN,
            t("久未更新的 App 常有已知漏洞,建議更新或移除。", "Apps not updated for years often have known flaws; update or remove them."), null, old)

        r += Finding(k, t("啟用中的無障礙服務", "Enabled accessibility services"),
            if (accessibilityPkgs.isEmpty()) t("無", "None") else count(accessibilityPkgs.size),
            if (accessibilityPkgs.isEmpty()) Level.OK else Level.WARN,
            t("無障礙可讀取螢幕並代你操作,銀行木馬最愛用。不認得的先關閉。",
                "Accessibility can read the screen and act for you — banking trojans love it. Turn off any you don't recognize."),
            sys(Settings.ACTION_ACCESSIBILITY_SETTINGS, "開啟無障礙設定", "Open accessibility settings"), accessibilityPkgs)

        r += Finding(k, t("裝置管理員", "Device administrators"), if (adminPkgs.isEmpty()) t("無", "None") else count(adminPkgs.size),
            if (adminPkgs.isEmpty()) Level.OK else Level.WARN,
            t("惡意 App 會用它阻止被解除安裝。要先在「裝置管理員 App」取消啟用,才能解除安裝。",
                "Malware uses this to block uninstall. Deactivate it under 'Device admin apps' first, then uninstall."),
            sys(Settings.ACTION_SECURITY_SETTINGS, "開啟安全性設定", "Open security settings"), adminPkgs)

        r += Finding(k, t("可讀取通知的 App", "Apps that can read notifications"),
            if (listenerPkgs.isEmpty()) t("無", "None") else count(listenerPkgs.size),
            if (listenerPkgs.isEmpty()) Level.OK else Level.WARN,
            t("通知中常有驗證碼。不需要的請關閉。", "Notifications often contain verification codes. Turn off what you don't need."),
            sys(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS, "開啟通知存取設定", "Open notification access"), listenerPkgs)
        return r
    }

    // ---------- 網路 / Network ----------
    private fun network(): List<Finding> {
        val k = Cat.NETWORK
        val r = mutableListOf<Finding>()
        val cm = c.getSystemService(ConnectivityManager::class.java)
        val wifiFix = sys(Settings.ACTION_WIFI_SETTINGS, "開啟 Wi-Fi 設定", "Open Wi-Fi settings")

        val vpn = cm.getNetworkCapabilities(cm.activeNetwork)?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
        r += Finding(k, "VPN", if (vpn) t("使用中", "Active") else t("未使用", "Not used"), if (vpn) Level.INFO else Level.OK,
            t("確認是你自己安裝的 VPN;免費來路不明的 VPN 能看到所有流量。", "Make sure it's a VPN you installed — an untrusted free VPN can see all your traffic."),
            sys(Settings.ACTION_VPN_SETTINGS, "開啟 VPN 設定", "Open VPN settings"))

        val proxy = cm.defaultProxy
        r += Finding(k, "Proxy", proxy?.let { "${it.host}:${it.port}" } ?: t("無", "None"), if (proxy != null) Level.WARN else Level.OK,
            t("若不是你設定的請移除:Wi-Fi → 長按網路 → 修改 → Proxy 改為「無」。", "If you didn't set it, remove it: Wi-Fi → long-press network → Modify → Proxy: None."), wifiFix)

        val dns = Settings.Global.getString(c.contentResolver, "private_dns_mode") ?: "unknown"
        r += Finding(k, t("私人 DNS", "Private DNS"), dns, if (dns == "off") Level.INFO else Level.OK,
            t("建議設為「自動」或指定 DNS 供應商,避免 DNS 被竄改。", "Set it to Automatic or a DNS provider to resist DNS tampering."),
            sys(Settings.ACTION_WIRELESS_SETTINGS, "開啟網路設定", "Open network settings"))

        val userCerts = runCatching {
            val ks = KeyStore.getInstance("AndroidCAStore").apply { load(null) }
            ks.aliases().toList().count { it.startsWith("user:") }
        }.getOrDefault(-1)
        val cT = t("使用者安裝的憑證", "User-installed certificates")
        r += when {
            userCerts < 0 -> Finding(k, cT, t("無法讀取", "Unavailable"), Level.INFO)
            userCerts == 0 -> Finding(k, cT, t("無", "None"), Level.OK)
            else -> Finding(k, cT, t("$userCerts 張", "$userCerts"), Level.WARN,
                t("憑證可被用來攔截 HTTPS。到「加密與憑證 → 使用者憑證」移除不認得的(公司/學校配發的除外)。",
                    "Certificates can be used to intercept HTTPS. Go to Encryption & credentials → User certificates and remove ones you don't recognize (except those from your employer/school)."),
                sys(Settings.ACTION_SECURITY_SETTINGS, "開啟安全性設定", "Open security settings"))
        }

        // Wi-Fi 加密類型 / 開放熱點
        val wm = c.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val wifi = cm.allNetworks.mapNotNull { cm.getNetworkCapabilities(it) }
            .firstOrNull { it.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) && !it.hasTransport(NetworkCapabilities.TRANSPORT_VPN) }
        val wT = t("Wi-Fi 加密", "Wi-Fi security")
        if (wifi == null) {
            r += Finding(k, wT, t("未連線 Wi-Fi", "Not connected to Wi-Fi"), Level.OK)
        } else {
            if (Build.VERSION.SDK_INT >= 31) {
                val type = wm.connectionInfo.currentSecurityType
                val (name, lv) = when (type) {
                    WifiInfo.SECURITY_TYPE_OPEN -> t("開放網路(無加密)", "Open network (no encryption)") to Level.RISK
                    WifiInfo.SECURITY_TYPE_WEP -> t("WEP(已可輕易破解)", "WEP (trivially broken)") to Level.RISK
                    WifiInfo.SECURITY_TYPE_PSK -> "WPA / WPA2-PSK" to Level.INFO
                    WifiInfo.SECURITY_TYPE_SAE -> "WPA3-SAE" to Level.OK
                    WifiInfo.SECURITY_TYPE_EAP -> "WPA/WPA2/WPA3-Enterprise" to Level.OK
                    WifiInfo.SECURITY_TYPE_OWE -> "OWE (Enhanced Open)" to Level.OK
                    else -> t("其他($type)", "Other ($type)") to Level.INFO
                }
                val adv = when {
                    lv == Level.RISK -> t("不要在此網路登入帳號或網銀;改用行動數據或可信 VPN,並把路由器改成 WPA2/WPA3。",
                        "Don't sign in to accounts or banking on this network; use mobile data or a trusted VPN, and switch the router to WPA2/WPA3.")
                    type == WifiInfo.SECURITY_TYPE_PSK -> t("路由器若支援,建議升級到 WPA3。", "If your router supports it, upgrade to WPA3.")
                    else -> ""
                }
                r += Finding(k, wT, name, lv, adv, if (lv != Level.OK) wifiFix else null)
            } else {
                r += Finding(k, wT, t("Android 12 以上才能讀取加密類型", "Security type needs Android 12+"), Level.INFO)
            }
            if (wifi.hasCapability(NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL)) {
                r += Finding(k, t("Wi-Fi 登入頁(公共熱點)", "Wi-Fi login page (public hotspot)"),
                    t("此網路需要網頁登入", "This network requires a web login"), Level.WARN,
                    t("公共熱點可能被監聽或釣魚,不要輸入重要帳密。", "Public hotspots can be snooped on or used for phishing; don't enter important credentials."), wifiFix)
            } else if (!wifi.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)) {
                r += Finding(k, t("Wi-Fi 未通過驗證", "Wi-Fi not validated"),
                    t("系統判定此網路無法正常上網(可能被攔截)", "Android could not verify internet access (possible interception)"), Level.INFO, "", wifiFix)
            }
        }
        return r
    }

    // ---------- 瀏覽器 / Browsers ----------
    private fun browser(): List<Finding> {
        val k = Cat.BROWSER
        val r = mutableListOf<Finding>()
        val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")).addCategory(Intent.CATEGORY_BROWSABLE)
        val def = pm.resolveActivity(i, PackageManager.MATCH_DEFAULT_ONLY)?.activityInfo?.packageName
        r += Finding(k, t("預設瀏覽器", "Default browser"), if (def == null || def == "android") t("未設定", "Not set") else label(def), Level.INFO)

        for (p in pm.queryIntentActivities(i, 0).map { it.activityInfo.packageName }.distinct()) {
            val pi = runCatching { pm.getPackageInfo(p, 0) }.getOrNull() ?: continue
            val days = (System.currentTimeMillis() - pi.lastUpdateTime) / dayMs
            val lv = when { days > 180 -> Level.RISK; days > 90 -> Level.WARN; else -> Level.OK }
            r += Finding(k, label(p), t("版本 ${pi.versionName},$days 天前更新", "Version ${pi.versionName}, updated $days days ago"), lv,
                t("瀏覽器漏洞是最常被利用的入口,請更新到最新版。", "Browser bugs are the most common way in. Update to the latest version."),
                Fix(t("到商店更新", "Update in store"), Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$p"))))
        }
        return r
    }
}
