package com.seccheck

import android.content.Intent

enum class Cat { MALWARE, SYSTEM, APPS, NETWORK, BROWSER, ERROR }

enum class Level { OK, INFO, WARN, RISK }

/** 一鍵修復:實際上是開啟對應的系統設定頁,由使用者自己確認。 */
data class Fix(val label: String, val intent: Intent)

data class Finding(
    val category: Cat,
    val title: String,
    val detail: String,
    val level: Level,
    val advice: String = "",
    val fix: Fix? = null,
    val pkgs: List<String> = emptyList(),
)
