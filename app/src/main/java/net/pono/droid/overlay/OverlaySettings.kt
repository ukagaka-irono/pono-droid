package net.pono.droid.overlay

import android.content.Context
import android.content.SharedPreferences

/** オーバーレイ表示の設定。ウィンドウの位置もここに保存する。 */
object OverlaySettings {
    const val KEY_CHARACTER_SCALE = "characterScale"

    /** シェル画像の 1px を何 dp で表示するか */
    const val DEFAULT_CHARACTER_SCALE = 1.0f
    const val MIN_CHARACTER_SCALE = 0.5f
    const val MAX_CHARACTER_SCALE = 2.5f

    fun prefs(context: Context): SharedPreferences = context.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    fun characterScale(prefs: SharedPreferences) =
        prefs.getFloat(KEY_CHARACTER_SCALE, DEFAULT_CHARACTER_SCALE).coerceIn(MIN_CHARACTER_SCALE, MAX_CHARACTER_SCALE)

    const val KEY_BALLOON_SCALE = "balloonScale"

    /** バルーンの大きさ（ふつうの大きさに対する倍率） */
    const val DEFAULT_BALLOON_SCALE = 1.0f
    const val MIN_BALLOON_SCALE = 0.5f
    const val MAX_BALLOON_SCALE = 2.0f

    fun balloonScale(prefs: SharedPreferences) =
        prefs.getFloat(KEY_BALLOON_SCALE, DEFAULT_BALLOON_SCALE).coerceIn(MIN_BALLOON_SCALE, MAX_BALLOON_SCALE)
}
