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

    /** ゴーストが話した文字を読み上げるか（最初は読み上げない） */
    const val KEY_SPEECH = "speech"

    fun speech(prefs: SharedPreferences) = prefs.getBoolean(KEY_SPEECH, false)

    /** SSTP を受けるか（この端末の中のアプリから。最初は受けない） */
    const val KEY_SSTP = "sstp"

    fun sstp(prefs: SharedPreferences) = prefs.getBoolean(KEY_SSTP, false)

    /** ゴーストが端末の壁紙を変えてよいか（\![set,wallpaper]。最初は変えさせない。元の壁紙には戻せない） */
    const val KEY_WALLPAPER = "wallpaper"

    fun wallpaper(prefs: SharedPreferences) = prefs.getBoolean(KEY_WALLPAPER, false)

    /** 開発者向けの機能（\![open,editor] で書き換えられるように開く・開発用の画面）。最初は使わない */
    const val KEY_DEVELOPER = "developer"

    fun developer(prefs: SharedPreferences) = prefs.getBoolean(KEY_DEVELOPER, false)

    /** 端末のカレンダーの今日の予定をゴーストに伝えるか（OnScheduleTodayNotify。読む許可も要る。最初は伝えない） */
    const val KEY_CALENDAR = "calendar"

    fun calendar(prefs: SharedPreferences) = prefs.getBoolean(KEY_CALENDAR, false)

    /** マイクで声を聞き、名前を呼ばれたらゴーストに伝えるか（OnVoiceRecognitionWord。マイクの許可も要る。最初は聞かない） */
    const val KEY_VOICE = "voice"

    fun voice(prefs: SharedPreferences) = prefs.getBoolean(KEY_VOICE, false)

    /** USB 機器をつないだり外したりしたらゴーストに伝えるか（OnDeviceArrival / OnDeviceRemove。最初は伝えない） */
    const val KEY_USB = "usb"

    fun usb(prefs: SharedPreferences) = prefs.getBoolean(KEY_USB, false)
}
