package net.pono.droid

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp

/** ゴーストの readme.txt など、長い文を読むための画面（\![open,readme]） */
class TextViewerActivity : ComponentActivity() {
    @OptIn(ExperimentalMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val title = intent.getStringExtra(EXTRA_TITLE).orEmpty()
        val text = intent.getStringExtra(EXTRA_TEXT).orEmpty()
        setContent {
            MaterialTheme {
                Scaffold(
                    topBar = {
                        TopAppBar(
                            title = { Text(title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            actions = { TextButton(onClick = { finish() }) { Text("閉じる") } },
                        )
                    },
                ) { padding ->
                    Box(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
                        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(16.dp))
                    }
                }
            }
        }
    }

    companion object {
        private const val EXTRA_TITLE = "title"
        private const val EXTRA_TEXT = "text"

        /** Intent に載せられる大きさには限りがあるので、長すぎる文は切る */
        private const val MAX_TEXT = 200_000

        fun start(context: Context, title: String, text: String) {
            context.startActivity(
                Intent(context, TextViewerActivity::class.java)
                    .putExtra(EXTRA_TITLE, title)
                    .putExtra(EXTRA_TEXT, text.take(MAX_TEXT))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }
}
