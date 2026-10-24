package net.pono.baseware.ghost

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withTimeoutOrNull

/**
 * \![set,syncobject] / \![reset,syncobject] / \![wait,syncobject] の名前つきの合図。
 * SSP では Windows のイベントなどだが、pono-droid ではアプリの中だけの合図にする（立てたら下ろすまで立ったまま）
 */
object SyncObjects {
    private val signaled = MutableStateFlow<Set<String>>(emptySet())

    /** 名前の数の上限（スクリプトに無数の名前を作らせない） */
    private const val MAX_NAMES = 256

    fun set(name: String, on: Boolean) {
        signaled.update { if (on) (if (it.size < MAX_NAMES || name in it) it + name else it) else it - name }
    }

    /** 合図が立つまで待つ。[timeoutMs] が 0 以下なら止められるまで待つ。立ったら true */
    suspend fun await(name: String, timeoutMs: Long): Boolean {
        if (timeoutMs <= 0) {
            signaled.first { name in it }
            return true
        }
        return withTimeoutOrNull(timeoutMs) { signaled.first { name in it } } != null
    }
}
