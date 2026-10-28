package com.seccheck

import androidx.compose.foundation.layout.*
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/** Std 版:沒有 INTERNET 權限,因此無法做網域(DNS)分析。 */
object DomainFeature { const val AVAILABLE = false }

@Composable
@Suppress("UNUSED_PARAMETER")
fun DomainPanel(hours: Int) {
    Card(Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Column(Modifier.padding(12.dp)) {
            Text(tr("瀏覽器目的地(網域)分析", "Browser destination (domain) analysis"), style = MaterialTheme.typography.titleSmall)
            Text(
                tr("Android 不提供「某 App 連到哪些網域」的資料,必須用本機 VPN 記錄 DNS 查詢,而轉送查詢需要網路權限。為了讓預設版維持完全沒有網路權限,這項功能放在另外安裝的「SecCheck Net」版(可與本版並存)。",
                    "Android doesn't expose which domains an app connects to. It needs a local VPN that logs DNS queries, and forwarding them requires network permission. To keep this edition free of network permission, the feature lives in the separate “SecCheck Net” edition (can be installed side by side)."),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
