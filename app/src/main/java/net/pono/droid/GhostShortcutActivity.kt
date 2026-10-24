package net.pono.droid

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import net.pono.droid.overlay.GhostOverlayService

/**
 * ホーム画面のショートカット（ゴーストの \![create,shortcut] で置いたもの）から、そのゴーストを起こす。画面は出さず、すぐ閉じる。
 * 他のアプリからは呼べない（exported でない）
 */
class GhostShortcutActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val dirName = intent.getStringExtra(EXTRA_GHOST)?.takeIf { it.isNotBlank() && '/' !in it && it != ".." }
        val ghost = dirName?.let { GhostStore.repository(this).find(it) }
        when {
            ghost == null -> Toast.makeText(this, "このゴーストはもう入っていません", Toast.LENGTH_LONG).show()
            // 他のアプリの上に出す許可が無ければ、許可を出せるアプリの画面を開く
            !Settings.canDrawOverlays(this) -> startActivity(Intent(this, MainActivity::class.java))
            else -> GhostOverlayService.start(this, ghost.dir.name)
        }
        finish()
    }

    companion object {
        const val EXTRA_GHOST = "ghost"
    }
}
