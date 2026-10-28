package net.pono.droid.overlay

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSession
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.service.notification.NotificationListenerService
import androidx.core.app.NotificationManagerCompat

/**
 * 端末で鳴っている曲や動画（ほかのアプリの MediaSession）を見て、再生が始まったら [onPlay] で知らせる
 * （OnMusicPlayEx / OnVideoPlayEx / OnMusicPlay）。同じ曲を止めてまた鳴らしたときは知らせない。
 * ほかのアプリのセッションを読むには、pono-droid に通知へのアクセスを許してもらう必要がある（[MediaListenerService]）。
 * 許されていなければ何もしない
 */
class MediaWatcher(private val context: Context, private val onPlay: (MediaPlay) -> Unit) {
    /** 再生が始まった曲や動画。[extras] は OnMusicPlayEx の拡張情報（album・duration など）の名前と内容 */
    data class MediaPlay(val video: Boolean, val title: String, val artist: String, val extras: List<Pair<String, String>>)

    private val manager = context.getSystemService(MediaSessionManager::class.java)
    private val component = ComponentName(context, MediaListenerService::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val watching = HashMap<MediaSession.Token, Pair<MediaController, MediaController.Callback>>()
    private var lastKey: String? = null
    private var started = false

    private val sessionsListener = MediaSessionManager.OnActiveSessionsChangedListener { list -> update(list.orEmpty()) }

    fun start() {
        if (started || manager == null) return
        try {
            manager.addOnActiveSessionsChangedListener(sessionsListener, component, handler)
            started = true
            update(manager.getActiveSessions(component))
        } catch (e: SecurityException) {
            // 通知へのアクセスが無い（取り消された）
            stop()
        }
    }

    fun stop() {
        if (started) runCatching { manager?.removeOnActiveSessionsChangedListener(sessionsListener) }
        started = false
        watching.values.forEach { (c, cb) -> c.unregisterCallback(cb) }
        watching.clear()
        lastKey = null
    }

    private fun update(list: List<MediaController>) {
        val tokens = list.map { it.sessionToken }.toSet()
        watching.keys.filter { it !in tokens }.forEach { t -> watching.remove(t)?.let { (c, cb) -> c.unregisterCallback(cb) } }
        for (c in list) {
            if (c.sessionToken in watching) continue
            val cb = object : MediaController.Callback() {
                override fun onPlaybackStateChanged(state: PlaybackState?) = check(c)

                override fun onMetadataChanged(metadata: MediaMetadata?) = check(c)
            }
            c.registerCallback(cb, handler)
            watching[c.sessionToken] = c to cb
            check(c)
        }
    }

    private fun check(c: MediaController) {
        if (c.playbackState?.state != PlaybackState.STATE_PLAYING) return
        val m = c.metadata ?: return
        fun text(key: String) = m.getString(key)?.trim()?.takeIf { it.isNotEmpty() }
        val title = text(MediaMetadata.METADATA_KEY_TITLE) ?: text(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: return
        val artist = text(MediaMetadata.METADATA_KEY_ARTIST) ?: text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST) ?: text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE).orEmpty()
        val key = "${c.packageName}\u0001$title\u0001$artist"
        if (key == lastKey) return
        lastKey = key
        val extras = buildList {
            text(MediaMetadata.METADATA_KEY_ALBUM)?.let { add("album" to it) }
            text(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)?.let { add("albumartist" to it) }
            text(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE)?.let { add("subtitle" to it) }
            add("source" to appLabel(c.packageName))
            m.getLong(MediaMetadata.METADATA_KEY_DURATION).takeIf { it > 0 }?.let { add("duration" to (it / 1000).toString()) }
            text(MediaMetadata.METADATA_KEY_GENRE)?.let { add("genre" to it) }
            text(MediaMetadata.METADATA_KEY_COMPOSER)?.let { add("composer" to it) }
        }
        onPlay(MediaPlay(isVideo(c.packageName), title, artist, extras))
    }

    private fun appInfo(pkg: String): ApplicationInfo? = runCatching { context.packageManager.getApplicationInfo(pkg, 0) }.getOrNull()

    private fun appLabel(pkg: String): String = appInfo(pkg)?.let { context.packageManager.getApplicationLabel(it).toString() } ?: pkg

    /** 動画のアプリ（アプリの種類が動画）か、ブラウザ（SSP と同じく、ブラウザで鳴っているものは動画とする） */
    private fun isVideo(pkg: String): Boolean {
        if (appInfo(pkg)?.category == ApplicationInfo.CATEGORY_VIDEO) return true
        val browsers = runCatching {
            context.packageManager.queryIntentActivities(Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com/")), PackageManager.MATCH_ALL)
        }.getOrDefault(emptyList())
        return browsers.any { it.activityInfo?.packageName == pkg }
    }

    companion object {
        /** 通知へのアクセスを許されているか */
        fun hasAccess(context: Context): Boolean = context.packageName in NotificationManagerCompat.getEnabledListenerPackages(context)
    }
}

/**
 * 通知へのアクセスを許してもらうためだけのサービス。許されていると、ほかのアプリの再生中のセッションを読める（[MediaWatcher]）。
 * 通知そのものは読まない
 */
class MediaListenerService : NotificationListenerService() {
    override fun onListenerConnected() {
        GhostOverlayService.onMediaAccessChanged()
    }

    override fun onListenerDisconnected() {
        GhostOverlayService.onMediaAccessChanged()
    }
}
