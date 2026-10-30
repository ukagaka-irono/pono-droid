package net.pono.droid

import androidx.compose.ui.res.stringResource
import android.content.Context
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray

/** 同梱しているソフトウェアのライセンス。中身はビルド時に作る assets/licenses/licenses.json。 */
private data class LicenseEntry(val name: String, val license: String, val url: String, val text: String)

private fun loadLicenses(context: Context): List<LicenseEntry> {
    val json = context.assets.open("licenses/licenses.json").bufferedReader().use { it.readText() }
    val array = JSONArray(json)
    return List(array.length()) { i ->
        val o = array.getJSONObject(i)
        LicenseEntry(o.getString("name"), o.getString("license"), o.getString("url"), o.getString("text"))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LicensesScreen(onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val entries by produceState(emptyList<LicenseEntry>()) {
        value = withContext(Dispatchers.IO) { loadLicenses(context) }
    }
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("ライセンス") },
                navigationIcon = { TextButton(onClick = onBack) { Text("戻る") } },
            )
        },
    ) { padding ->
        LazyColumn(
            Modifier.padding(padding).fillMaxSize().padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(
                    stringResource(R.string.product_name) + " バージョン ${AppVersion.of(context)}",
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
            item {
                Text(
                    stringResource(R.string.product_name) + " と、利用しているソフトウェアのライセンスです。名前をタップすると全文を表示します。",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(vertical = 8.dp),
                )
            }
            items(entries, key = { it.name }) { LicenseCard(it) }
        }
    }
}

@Composable
private fun LicenseCard(entry: LicenseEntry) {
    var expanded by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth().clickable { expanded = !expanded }) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(entry.name, style = MaterialTheme.typography.titleMedium)
            Text("${entry.license}  ${entry.url}", style = MaterialTheme.typography.bodySmall)
            if (expanded) {
                // 原文の改行を崩さないよう、等幅で横にスクロールさせる
                Text(
                    entry.text,
                    fontFamily = FontFamily.Monospace,
                    fontSize = 11.sp,
                    lineHeight = 15.sp,
                    softWrap = false,
                    modifier = Modifier.padding(top = 8.dp).horizontalScroll(rememberScrollState()),
                )
            }
        }
    }
}
