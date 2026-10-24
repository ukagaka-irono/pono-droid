package net.pono.droid.overlay

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import net.pono.baseware.net.MailAccount
import org.json.JSONArray
import org.json.JSONObject
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * メールの確認（\![biff]）のアカウント。パスワードは Android の鍵（Android Keystore の AES-GCM、端末の外に出せない鍵）で
 * 暗号にしてから保存する。アカウントはアプリの設定の画面で入れる（最初は無い）
 */
object MailAccounts {
    private const val PREFS = "mail"
    private const val KEY_ACCOUNTS = "accounts"
    private const val KEY_ALIAS = "pono-mail"
    private const val GCM_TAG_BITS = 128

    /** 何分ごとに確かめるか（0 なら確かめない） */
    const val KEY_INTERVAL = "intervalMinutes"

    fun load(context: Context): List<MailAccount> {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val array = runCatching { JSONArray(prefs.getString(KEY_ACCOUNTS, "[]")) }.getOrNull() ?: return emptyList()
        return (0 until array.length()).mapNotNull { i ->
            runCatching {
                val o = array.getJSONObject(i)
                MailAccount(
                    o.getString("name"), o.getString("protocol"), o.getString("host"), o.getInt("port"), o.getString("user"),
                    decrypt(o.getString("password")),
                )
            }.getOrNull()
        }
    }

    /** アカウントを置き換えて保存する（設定の画面から） */
    fun save(context: Context, accounts: List<MailAccount>) {
        val array = JSONArray()
        for (a in accounts) {
            array.put(
                JSONObject().put("name", a.name).put("protocol", a.protocol).put("host", a.host).put("port", a.port)
                    .put("user", a.user).put("password", encrypt(a.password)),
            )
        }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(KEY_ACCOUNTS, array.toString()).apply()
    }

    fun intervalMinutes(context: Context): Int =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getInt(KEY_INTERVAL, 0).coerceIn(0, 24 * 60)

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

    private fun encrypt(plain: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(cipher.iv, Base64.NO_WRAP) + ":" + Base64.encodeToString(data, Base64.NO_WRAP)
    }

    private fun decrypt(stored: String): String {
        val (iv, data) = stored.split(':', limit = 2).map { Base64.decode(it, Base64.NO_WRAP) }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(GCM_TAG_BITS, iv)) }
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }
}
