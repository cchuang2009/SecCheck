<p align="center"><img src="docs/icon-preview.png" width="128" alt="SecCheck icon"></p>

<h1 align="center">SecCheck 撈毒網</h1>

<p align="center">
  <a href="#english">English</a> · <a href="#繁體中文">繁體中文</a>
</p>

<p align="center">
  <img alt="build" src="https://github.com/YOUR_USER/YOUR_REPO/actions/workflows/build.yml/badge.svg">
  <img alt="platform" src="https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-3DDC84">
  <img alt="target" src="https://img.shields.io/badge/targetSdk-36%20(Android%2016)-blue">
  <img alt="license" src="https://img.shields.io/badge/license-MIT-lightgrey">
  <img alt="network" src="https://img.shields.io/badge/INTERNET-none%20(Std%20edition)-brightgreen">
</p>

---

## English

**SecCheck** is a local, offline security checker for Android. It audits your phone's **apps, system, network and browsers**, flags suspicious apps, and gives you advice plus one‑tap shortcuts to fix each issue.

> **It is not an antivirus engine.** Android does not let normal apps read other apps' internals, so SecCheck uses *behavior heuristics + a local blocklist*. It finds apps that *look* malicious; it cannot guarantee a phone is clean.

### Privacy first
- The default **Std** edition declares **no `INTERNET` permission** — it technically cannot send your data anywhere. The optional **Net** edition adds `INTERNET` *only* to forward DNS queries (see *Traffic analysis*).
- No analytics, no ads, no account.

### Features
| Area | Checks |
|---|---|
| **Suspicious apps** | Risk score 0–100 per app: sideloaded, accessibility service, device admin, notification access, SMS, overlay, hidden launcher icon, debug signature, name impersonation, dangerous combinations (banking‑trojan pattern), local blocklist (package name / APK SHA‑256) |
| **System** | Security patch age, screen lock, USB debugging, developer options, root signs, Play Protect |
| **Apps** | Granted dangerous permissions, non‑store installs, outdated `targetSdk`, accessibility services, device admins, notification listeners |
| **Network** | VPN, proxy, private DNS, user‑installed CA certificates, **Wi‑Fi security type (open / WEP / WPA2 / WPA3)**, captive‑portal hotspots |
| **Browsers** | Default browser, version freshness of every installed browser |

**Fix options** – every finding has advice, per‑app *Settings* / *Uninstall* buttons, and shortcuts to the right system screen (accessibility, device admin, notification access, certificates, Wi‑Fi, updates…). Android does not allow silent removal of other apps, so you always confirm in the system dialog. The app re‑scans when you come back, so fixed items disappear.

**Background check** – optional, every 12 h via WorkManager; notifies **only about newly appearing** risks.

**Bilingual UI** – Auto (follows system) / 中文 / English, switchable in the app.

**Export report** – share a plain‑text report (score, findings, advice, app list) through Android's share sheet; you choose who gets it.

**Trusted apps** – tap *Trust* on a flagged app to stop it being listed (blocklisted apps are never skipped). *Reset trusted* clears the list.

### Traffic analysis (v0.5) · rose charts

Open the **Traffic** tab. Charts are **rose (Nightingale) charts** – petal *area* is proportional to the value.

- **Per‑app traffic** (Wi‑Fi + mobile, last 24 h / 7 d) with an *upload‑heavy apps* warning.
- **YouTube access** – traffic by hour of day (24‑petal clock rose), foreground time, Wi‑Fi vs mobile, and a warning if an unofficial YouTube mod (ReVanced/Vanced) is installed.
- **Browsers** – traffic per browser.
- **Destinations (Net edition only)** – a DNS‑only local VPN logs which domains each app resolves: top destination domains of your browsers, *who* talks to YouTube (YouTube app vs browsers vs other apps) and when, and lookups per app.

Needs *Usage access* (Settings → Special app access) for the traffic counters. Per‑app mobile data needs Android 10+.

| Edition | Package | INTERNET | Destination analysis |
|---|---|---|---|
| **Std** (default) | `com.seccheck` | none | no |
| **Net** (optional) | `com.seccheck.net` | only to forward DNS queries to your network's own DNS server | yes |

Both can be installed side by side. DNS records stay on the device (7 days, clearable). **Limits:** it counts DNS lookups, not bytes per domain; apps using DoH/DoT (e.g. Chrome *Secure DNS*) bypass it; the hour resolution depends on system traffic stats (~1–2 h).

### Install
Download the latest APK from **[Releases](../../releases)** and install it (allow "install unknown apps" for your browser/file manager once). Requires Android 8.0+.

### Build
```bash
# JDK 17 + Android SDK (platform 36) + Gradle 8.11+
gradle assembleRelease          # → app/build/outputs/apk/{std,net}/release/
```
GitHub Actions builds on every push to `main`; pushing a tag `v*` publishes the APK to Releases:
```bash
git tag v0.5.0 && git push --tags
```

#### Stable signing (recommended)
Without a keystore, release builds are debug‑signed, so each CI APK has a different signature and cannot update the previous install.
```bash
keytool -genkey -v -keystore release.jks -alias seccheck -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # copy into the secret below
```
Add repository secrets: `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD`. **Never commit the `.jks`.**

### Permissions
| Permission | Why |
|---|---|
| `QUERY_ALL_PACKAGES` | List installed apps for the audit. Google Play restricts this; fine for GitHub distribution. |
| `PACKAGE_USAGE_STATS` | Read per-app traffic counters and YouTube foreground time (you grant it in *Usage access*). |
| `REQUEST_DELETE_PACKAGES` | Show the system uninstall prompt (you confirm). |
| `ACCESS_NETWORK_STATE`, `ACCESS_WIFI_STATE` | Detect VPN/proxy and read the Wi‑Fi security type. |
| `POST_NOTIFICATIONS` | Optional background alerts (Android 13+). |
| *(Net edition)* `INTERNET`, `FOREGROUND_SERVICE` | Forward DNS queries; keep the DNS-analysis VPN running with a visible notification. |

### Blocklist
Edit `app/src/main/assets/blocklist.txt` (`pkg:<package>` or `sha256:<apk hash>`), or import a `.txt` inside the app (kept in app‑private storage; invalid lines are ignored). No samples are bundled; add entries from sources you trust (e.g. VirusTotal, CERT advisories). Get an APK hash with `adb shell pm path <pkg>` → `adb pull` → `sha256sum`.

### Project layout
```
app/src/main/java/com/seccheck/
  Scanner.kt      system / apps / network / browser checks (zh + en)
  Malware.kt      heuristic scoring + blocklist
  Background.kt   preferences, WorkManager schedule, notifications
  Tools.kt        report export + blocklist import
  Traffic*.kt     per-app / YouTube traffic stats + UI    Rose.kt  rose chart
  MainActivity.kt Compose UI, language switch, fix buttons
  Model.kt        Finding / Fix / Level / Cat
docs/icon.svg     app icon (net catching viruses)
.github/workflows/build.yml
```

### Limitations
- Cannot read other apps' files, browsing history or cookies (Android sandbox).
- Heuristics can produce false positives (e.g. password managers use accessibility) and false negatives.
- The *draw over other apps* detection can be inaccurate.
- Wi‑Fi security type needs Android 12+.
- Android only (iOS sandboxing prevents this kind of audit).

### Roadmap
Optional VirusTotal hash lookup (opt‑in build flavor, would need `INTERNET`) · more languages · scan history / diff.

### Contributing
Issues and PRs welcome. Please keep the "no INTERNET permission" promise unless a feature is strictly opt‑in and clearly disclosed.

### License
[MIT](LICENSE)

---

## 繁體中文

**SecCheck 撈毒網** 是 Android 本機離線的手機安全檢測工具,檢查 **App、系統、網路、瀏覽器**,找出可疑 App,並提供建議與一鍵修復捷徑。

> **這不是防毒引擎。** Android 不允許一般 App 讀取其他 App 的內部檔案,所以 SecCheck 採用「行為特徵 + 本機黑名單」,只能找出「長得像惡意軟體」的 App,無法保證手機沒有病毒。

### 隱私優先
- 預設的 **Std** 版**沒有 `INTERNET` 權限**,技術上不可能把資料傳出去。選用的 **Net** 版僅為轉送 DNS 查詢才加入 `INTERNET`(見「流量分析」)。
- 無追蹤、無廣告、無需帳號。

### 功能
| 範圍 | 檢查項目 |
|---|---|
| **可疑 App** | 每個 App 風險分 0–100:非商店來源、無障礙服務、裝置管理員、通知存取、簡訊、懸浮視窗、隱藏桌面圖示、debug 簽章、名稱冒充、危險組合(銀行木馬模式)、本機黑名單(套件名稱 / APK SHA‑256) |
| **系統** | 安全性修補日期、螢幕鎖、USB 偵錯、開發者選項、Root 跡象、Play Protect |
| **Apps** | 已授予的危險權限、非商店安裝、過舊 `targetSdk`、無障礙服務、裝置管理員、通知讀取 |
| **網路** | VPN、Proxy、私人 DNS、使用者安裝的憑證、**Wi‑Fi 加密類型(開放 / WEP / WPA2 / WPA3)**、公共熱點登入頁 |
| **瀏覽器** | 預設瀏覽器、各瀏覽器版本是否過舊 |

**修復選項**:每項都有建議、每個 App 的「設定」/「解除安裝」按鈕,以及直達對應系統頁面的捷徑(無障礙、裝置管理員、通知存取、憑證、Wi‑Fi、系統更新…)。Android 不允許靜默移除其他 App,所以最後一步由你在系統對話框確認。回到 App 會自動重掃,修好的項目就消失。

**背景檢查**:選用,透過 WorkManager 每 12 小時一次,**只通知新出現的風險**。

**中英介面**:Auto(跟隨系統)/ 中文 / English,可在 App 內切換。

**匯出報告**:用系統分享送出純文字報告(分數、項目、建議、App 清單),要給誰由你決定。

**信任名單**:對被標記的 App 按「信任」就不再列為可疑(黑名單命中者不會被略過)。「清除信任名單」可還原。

### 流量分析(v0.5)· 玫瑰圖

開啟 **流量分析** 分頁。圖表是**玫瑰圖(南丁格爾圖)**:花瓣「面積」正比於數值。

- **各 App 流量**(Wi‑Fi + 行動數據,近 24 小時 / 7 天),並警示「上傳量大於下載量」的 App。
- **YouTube 存取**:一天中各時段流量(24 瓣時鐘玫瑰圖)、前景使用時間、Wi‑Fi 與行動數據比例;若安裝非官方修改版(ReVanced/Vanced)會警告。
- **瀏覽器**:各瀏覽器流量。
- **目的地分析(僅 Net 版)**:只攔截 DNS 的本機 VPN,記錄每個 App 查詢了哪些網域:瀏覽器的主要目的網域、*誰*在存取 YouTube(YouTube App / 瀏覽器 / 其他 App)與時段、各 App 的查詢量。

讀取流量統計需要「使用情況存取權」(設定 → 特殊應用程式存取權)。各 App 行動數據需 Android 10 以上。

| 版本 | 套件名稱 | INTERNET | 目的地分析 |
|---|---|---|---|
| **Std**(預設) | `com.seccheck` | 無 | 無 |
| **Net**(選用) | `com.seccheck.net` | 僅用於把 DNS 查詢轉送給你網路原本的 DNS 伺服器 | 有 |

兩版可並存安裝。DNS 紀錄只存在本機(保留 7 天,可清除)。**限制:**統計的是 DNS 查詢次數,不是各網域的流量位元組;使用 DoH/DoT 的連線(如 Chrome「安全 DNS」)不會被記錄;時段精度取決於系統流量統計(約 1–2 小時)。

### 安裝
到 **[Releases](../../releases)** 下載最新 APK 安裝(第一次需允許瀏覽器/檔案管理員「安裝未知應用程式」)。需要 Android 8.0 以上。

### 編譯
```bash
# JDK 17 + Android SDK(platform 36)+ Gradle 8.11+
gradle assembleRelease          # → app/build/outputs/apk/{std,net}/release/
```
推送到 `main` 會由 GitHub Actions 自動編譯;推送 `v*` tag 會把 APK 發佈到 Releases:
```bash
git tag v0.5.0 && git push --tags
```

#### 固定簽章(建議)
沒有 keystore 時,release 會用 debug 簽章,每次 CI 的 APK 簽章都不同,無法覆蓋更新舊版。
```bash
keytool -genkey -v -keystore release.jks -alias seccheck -keyalg RSA -keysize 2048 -validity 10000
base64 -w0 release.jks   # 貼到下面的 secret
```
在 repo 的 Secrets 新增:`KEYSTORE_BASE64`、`KEYSTORE_PASSWORD`、`KEY_ALIAS`、`KEY_PASSWORD`。**不要把 `.jks` commit 進 repo。**

### 權限說明
| 權限 | 用途 |
|---|---|
| `QUERY_ALL_PACKAGES` | 列出已安裝 App 以進行審計。Google Play 會限制此權限,GitHub 發佈沒問題。 |
| `PACKAGE_USAGE_STATS` | Read per-app traffic counters and YouTube foreground time (you grant it in *Usage access*). |
| `REQUEST_DELETE_PACKAGES` | 顯示系統的解除安裝確認視窗(由你確認)。 |
| `ACCESS_NETWORK_STATE`、`ACCESS_WIFI_STATE` | 偵測 VPN/Proxy、讀取 Wi‑Fi 加密類型。 |
| `POST_NOTIFICATIONS` | 選用的背景警示通知(Android 13+)。 |
| *(Net 版)* `INTERNET`、`FOREGROUND_SERVICE` | 轉送 DNS 查詢;以可見的通知維持 DNS 分析 VPN 運作。 |

### 黑名單
編輯 `app/src/main/assets/blocklist.txt`(`pkg:<套件名稱>` 或 `sha256:<APK 雜湊>`),或在 App 內匯入 `.txt`(只存在 App 私有空間,格式錯誤的行會被忽略)。內建不附任何樣本,請自行加入可信來源(如 VirusTotal、CERT 通報)的資料。取得 APK 雜湊:`adb shell pm path <pkg>` → `adb pull` → `sha256sum`。

### 專案結構
```
app/src/main/java/com/seccheck/
  Scanner.kt      系統 / Apps / 網路 / 瀏覽器檢查(中英)
  Malware.kt      行為特徵評分 + 黑名單
  Background.kt   設定儲存、WorkManager 排程、通知
  Tools.kt        報告匯出 + 黑名單匯入
  Traffic*.kt     各 App / YouTube 流量統計與介面    Rose.kt  玫瑰圖
  MainActivity.kt Compose 介面、語言切換、修復按鈕
  Model.kt        Finding / Fix / Level / Cat
docs/icon.svg     App 圖示(大網撈病毒)
.github/workflows/build.yml
```

### 限制
- 無法讀取其他 App 的檔案、瀏覽紀錄或 cookie(Android 沙盒)。
- 行為特徵可能誤報(例如密碼管理器會用無障礙)或漏報。
- 「懸浮視窗」偵測可能不準。
- Wi‑Fi 加密類型需要 Android 12 以上。
- 只支援 Android(iOS 沙盒限制讓這類審計無法實現)。

### 規劃
選用的 VirusTotal 雜湊查詢(獨立的選用版本,需要 `INTERNET`)· 更多語言 · 掃描歷史與差異比對。

### 參與貢獻
歡迎 Issue 與 PR。請維持「不使用 INTERNET 權限」的承諾,除非功能完全為選用且清楚告知。

### 授權
[MIT](LICENSE)
