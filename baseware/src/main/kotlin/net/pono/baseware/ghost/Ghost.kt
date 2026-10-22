package net.pono.baseware.ghost

import net.pono.baseware.balloon.Balloon
import net.pono.baseware.balloon.BalloonRepository
import net.pono.baseware.text.Descript
import net.pono.baseware.text.TextCharsets
import java.io.File
import java.nio.charset.Charset
import java.util.Properties

/** インストール済みのゴースト1体（ghost/master と shell/ を持つディレクトリ）。 */
class Ghost(val dir: File) {
    val masterDir = File(dir, "ghost/master")
    val descript: Descript = Descript.read(File(masterDir, "descript.txt"))

    val name: String get() = descript["name"] ?: dir.name
    val sakuraName: String get() = descript["sakura.name"] ?: name
    val keroName: String get() = descript["kero.name"] ?: ""
    val shioriName: String get() = descript["shiori"] ?: "shiori.dll"

    /** descript.txt の charset。SHIORI とのやりとりの最初の文字コードにも使う。 */
    val charset: Charset get() = TextCharsets.resolve(descript["charset"]) ?: TextCharsets.CP932

    val shellDirs: List<File>
        get() = File(dir, "shell").listFiles { f -> f.isDirectory }?.sortedBy { it.name }.orEmpty()

    fun defaultShellDir(): File? =
        shellDirs.firstOrNull { it.name == "master" } ?: shellDirs.firstOrNull()

    /** 選んであるシェル（無ければ既定のシェル） */
    fun shellDir(): File? =
        loadProfile().getProperty(PROFILE_SHELL)?.let { name -> shellDirs.firstOrNull { it.name == name } } ?: defaultShellDir()

    /** 次の起動から使うシェルを選ぶ。[shellDirName] は shell/ の下のディレクトリ名 */
    fun selectShell(shellDirName: String) {
        saveProfile(loadProfile().apply { setProperty(PROFILE_SHELL, shellDirName) })
    }

    /**
     * 使うバルーン。選んであればそれ、無ければゴーストの descript.txt の balloon 指定。
     * どちらもインストールされていなければ null（pono-droid の簡易バルーンを使う）。
     */
    fun balloon(balloons: BalloonRepository): Balloon? =
        when (val selected = loadProfile().getProperty(PROFILE_BALLOON)) {
            BALLOON_SIMPLE -> null
            null -> descript["balloon"]?.let(balloons::findByName)
            else -> balloons.find(selected)
        }

    /** バルーンを選ぶ。null なら pono-droid の簡易バルーン */
    fun selectBalloon(balloonDirName: String?) {
        saveProfile(loadProfile().apply { setProperty(PROFILE_BALLOON, balloonDirName ?: BALLOON_SIMPLE) })
    }

    val isValid: Boolean get() = File(masterDir, "descript.txt").isFile

    // pono-droid がゴーストごとに覚えておくこと（初回起動したか、選んだシェルなど）
    private val profileFile = File(dir, "pono/profile.properties")

    fun loadProfile() = Properties().apply {
        if (profileFile.isFile) profileFile.inputStream().use(::load)
    }

    fun saveProfile(p: Properties) {
        profileFile.parentFile?.mkdirs()
        profileFile.outputStream().use { p.store(it, null) }
    }

    private companion object {
        const val PROFILE_SHELL = "shell"
        const val PROFILE_BALLOON = "balloon"

        /** 簡易バルーンを選んだ印（ディレクトリ名と重ならないよう / を含める） */
        const val BALLOON_SIMPLE = "/simple"
    }
}

/**
 * ゴースト置き場。バルーンは [balloons]（既定ではゴースト置き場と同じ階層の balloon/）に置く。
 */
class GhostRepository(val root: File, val balloons: BalloonRepository = BalloonRepository(File(root.parentFile, "balloon"))) {
    init {
        root.mkdirs()
    }

    fun list(): List<Ghost> =
        root.listFiles { f -> f.isDirectory }?.map(::Ghost)?.filter { it.isValid }?.sortedBy { it.name }.orEmpty()

    fun find(dirName: String): Ghost? = Ghost(File(root, dirName)).takeIf { it.isValid }

    fun installer() = NarInstaller(root, balloons.root)

    /** ゴーストを消す（元に戻せない）。置き場の外は消さない */
    fun uninstall(ghost: Ghost): Boolean {
        val dir = ghost.dir.canonicalFile
        if (dir.parentFile != root.canonicalFile) return false
        return dir.deleteRecursively()
    }
}
