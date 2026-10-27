package net.pono.droid.overlay

import android.graphics.BitmapFactory
import android.media.MediaMetadataRetriever
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import net.pono.baseware.saori.MediaInfo
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** 内蔵 SAORI のうち Android の機能を使うもの（データの保護・メディアの情報・Windows のタイムゾーン名） */
object SaoriPlatform {
    private const val KEY_ALIAS = "pono_saori_protect"
    private const val VERSION: Byte = 1
    private const val GCM_TAG_BITS = 128

    @Synchronized
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }

    /**
     * internal_crypto の Protect。この端末のこのアプリの鍵（Android Keystore。取り出せない）で包む。
     * 形は 版（1 バイト）＋ IV の長さ（1 バイト）＋ IV ＋暗号文と認証タグ。ほかの端末やアプリを入れ直した後は戻せない
     */
    fun protect(data: ByteArray): ByteArray? = runCatching {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val body = cipher.doFinal(data)
        byteArrayOf(VERSION, cipher.iv.size.toByte()) + cipher.iv + body
    }.getOrNull()

    fun unprotect(data: ByteArray): ByteArray? = runCatching {
        if (data.size < 2 || data[0] != VERSION) return null
        val ivLen = data[1].toInt()
        if (ivLen !in 12..16 || data.size < 2 + ivLen + GCM_TAG_BITS / 8) return null
        val iv = data.copyOfRange(2, 2 + ivLen)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv)) }
        cipher.doFinal(data, 2 + ivLen, data.size - 2 - ivLen)
    }.getOrNull()

    /** internal_file の MediaInfo。画像は大きさだけを読み、音声・動画は MediaMetadataRetriever でタグ・長さ・映像の大きさを読む */
    fun mediaInfo(file: File): MediaInfo? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        runCatching { BitmapFactory.decodeFile(file.path, bounds) }
        if (bounds.outWidth > 0 && bounds.outHeight > 0) {
            return MediaInfo("image", mapOf("width" to bounds.outWidth.toString(), "height" to bounds.outHeight.toString()))
        }
        val r = MediaMetadataRetriever()
        return try {
            r.setDataSource(file.absolutePath)
            fun get(key: Int) = r.extractMetadata(key)?.trim()?.takeIf { it.isNotEmpty() }
            // アルバムの絵だけのものは映像と数えない（MediaMetadataRetriever は HAS_VIDEO にしない）
            val video = get(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO) == "yes"
            val audio = get(MediaMetadataRetriever.METADATA_KEY_HAS_AUDIO) == "yes"
            val values = LinkedHashMap<String, String>()
            get(MediaMetadataRetriever.METADATA_KEY_TITLE)?.let { values["title"] = it }
            get(MediaMetadataRetriever.METADATA_KEY_ARTIST)?.let { values["artist"] = it }
            get(MediaMetadataRetriever.METADATA_KEY_ALBUMARTIST)?.let { values["albumartist"] = it }
            get(MediaMetadataRetriever.METADATA_KEY_ALBUM)?.let { values["album"] = it }
            get(MediaMetadataRetriever.METADATA_KEY_GENRE)?.let { values["genre"] = it }
            // 年は先頭の 4 桁（日付全体のこともある）
            (get(MediaMetadataRetriever.METADATA_KEY_YEAR) ?: get(MediaMetadataRetriever.METADATA_KEY_DATE))
                ?.take(4)?.takeIf { y -> y.length == 4 && y.all(Char::isDigit) }?.let { values["year"] = it }
            get(MediaMetadataRetriever.METADATA_KEY_CD_TRACK_NUMBER)?.substringBefore('/')?.trim()?.toIntOrNull()?.let { values["track"] = it.toString() }
            if (video || audio) get(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()?.let { values["duration"] = it.toString() }
            if (video) {
                get(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.let { values["width"] = it }
                get(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.let { values["height"] = it }
            }
            MediaInfo(if (video) "video" else if (audio) "audio" else "", values)
        } catch (e: Exception) {
            null
        } finally {
            runCatching { r.release() }
        }
    }

    /** Windows のタイムゾーン名（小文字）→ ICU の正規の綴り */
    private val windowsNames: Map<String, String> by lazy {
        android.icu.util.TimeZone.getAvailableIDs()
            .mapNotNull { id -> android.icu.util.TimeZone.getWindowsID(id) }
            .associateBy { it.lowercase() }
    }

    /** Windows のタイムゾーン名（大文字小文字は見ない）を IANA の名前にする（Unicode CLDR の対応表。ICU が持っている） */
    fun windowsZone(name: String): String? =
        windowsNames[name.trim().lowercase()]?.let { android.icu.util.TimeZone.getIDForWindowsID(it, null) }
}
