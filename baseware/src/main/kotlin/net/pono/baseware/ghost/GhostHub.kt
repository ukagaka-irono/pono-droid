package net.pono.baseware.ghost

import net.pono.baseware.net.SstpRequest
import java.util.concurrent.CopyOnWriteArrayList

/**
 * 同時に動かしているゴースト（複数起動）。アプリが1つ持ち、どのゴーストにも同じものを渡す。
 * ゴーストは SHIORI を読み込んだら入り、終わるときに抜ける。ゴーストどうしのイベント（\![raiseother]、OnOtherGhost* など）はここを通して届ける
 */
class GhostHub {
    private val ghosts = CopyOnWriteArrayList<GhostController>()

    /** 動いているゴースト（入った順） */
    val running: List<GhostController> get() = ghosts.toList()

    internal fun join(c: GhostController) {
        ghosts.addIfAbsent(c)
    }

    internal fun leave(c: GhostController) {
        ghosts.remove(c)
    }

    /** [c] 以外の、動いているゴースト */
    internal fun others(c: GhostController): List<GhostController> = ghosts.filter { it !== c }

    /** [dirName] のゴーストが動いているか */
    fun isRunning(dirName: String): Boolean = ghosts.any { it.ghost.dir.name == dirName }

    /**
     * SSTP を受けるゴースト。ReceiverGhostName / Ghost があればそのゴースト（動いていなければ null）、uniqueid の ID ならそのゴースト、
     * IfGhost に名前のあるゴーストが動いていればそのゴースト、どれでもなければ最初に起動したもの（SSP と同じ。
     * descript.txt の sstp.allowunspecifiedsend が 0 のゴーストは飛ばす）
     */
    fun sstpTarget(req: SstpRequest): GhostController? {
        val all = running.ifEmpty { return null }
        // Sender: First は最初に起動したゴースト
        if (req["Sender"].equals("First", ignoreCase = true)) return all.first()
        val named = req["ReceiverGhostName"] ?: req["Ghost"]
        if (named != null) return all.firstOrNull { it.ghost.sakuraName == named || it.ghost.name == named }
        req["ID"]?.let { id -> all.firstOrNull { it.uniqueId == id }?.let { return it } }
        for ((name, value) in req.headers) {
            if (!name.equals("IfGhost", ignoreCase = true)) continue
            val names = value.split(',').map { it.trim() }
            all.firstOrNull { names[0] == it.ghost.sakuraName && (names.size < 2 || names[1] == it.ghost.keroName) }?.let { return it }
        }
        return all.firstOrNull { it.acceptsUnspecified } ?: all.first()
    }
}
