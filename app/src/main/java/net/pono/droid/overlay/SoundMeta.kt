package net.pono.droid.overlay

import android.media.MediaMetadataRetriever
import java.io.File

/**
 * 音のファイルのタグ（サウンドプロパティの meta.*）。MediaMetadataRetriever で読み、無いタグは入れない。
 * アルバムの絵は [cacheDir] の下に書き出して、そのパスを artwork にする。読んだものはファイルごとに覚えておく
 */
object SoundMeta {
    private val cache = HashMap<String, Map<String, String>>()

    @Synchronized
    fun read(file: File, cacheDir: File): Map<String, String> = cache.getOrPut(file.absolutePath + ":" + file.lastModified()) {
        val r = MediaMetadataRetriever()
        try {
            r.setDataSource(file.absolutePath)
            val out = LinkedHashMap<String, String>()
            fun put(name: String, key: Int) {
                r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }?.let { out[name] = it }
            }
            put("title", MediaMetadataRetriever.METADATA_KEY_TITLE)
            put("artist", MediaMetadataRetriever.METADATA_KEY_ARTIST)
            put("album", MediaMetadataRetriever.METADATA_KEY_ALBUM)
            put("albumartist", MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)
            put("genre", MediaMetadataRetriever.METADATA_KEY_GENRE)
            put("year", MediaMetadataRetriever.METADATA_KEY_YEAR)
            // 「3/12」のように全体の数が付くことがあるので、番号だけ
            r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.takeIf { it.isNotEmpty() }?.let { out["track"] = it }
            r.embeddedPicture?.takeIf { it.size <= MAX_ARTWORK }?.let { bytes ->
                val dir = File(cacheDir, "artwork").apply { mkdirs() }
                val art = File(dir, "%08x.img".format(file.absolutePath.hashCode()))
                runCatching { art.writeBytes(bytes) }.onSuccess { out["artwork"] = art.absolutePath }
            }
            out
        } catch (e: Exception) {
            emptyMap()
        } finally {
            runCatching { r.release() }
        }
    }

    /** 書き出すアルバムの絵の大きさの上限 */
    private const val MAX_ARTWORK = 8 * 1024 * 1024
}
