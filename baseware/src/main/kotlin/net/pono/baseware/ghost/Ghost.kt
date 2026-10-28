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

    /** 既定のシェル。descript.txt の seriko.defaultsurfacedirectoryname（既定は master）、無ければ最初のもの */
    fun defaultShellDir(): File? {
        val preferred = descript["seriko.defaultsurfacedirectoryname"]?.trim()?.takeIf { it.isNotEmpty() } ?: "master"
        return shellDirs.firstOrNull { it.name == preferred } ?: shellDirs.firstOrNull { it.name == "master" } ?: shellDirs.firstOrNull()
    }

    /** descript.txt の readme（無ければ readme.txt）。ghost/master か、ゴーストのフォルダの直下 */
    fun readmeFile(): File? {
        val name = descript["readme"]?.takeIf { it.isNotBlank() } ?: "readme.txt"
        return TextCharsets.fileIn(masterDir, name) ?: TextCharsets.fileIn(dir, name)
    }

    /** readme の中身（readme.charset があればその文字コードで読む）。無ければ null */
    fun readmeText(): String? = readmeFile()?.let { f -> runCatching { TextCharsets.decode(f.readBytes(), descript["readme.charset"]) }.getOrNull() }

    /** 選んであるシェル（無ければ既定のシェル） */
    fun shellDir(): File? =
        loadProfile().getProperty(PROFILE_SHELL)?.let { name -> shellDirs.firstOrNull { it.name == name } } ?: defaultShellDir()

    /** 次の起動から使うシェルを選ぶ。[shellDirName] は shell/ の下のディレクトリ名 */
    fun selectShell(shellDirName: String) {
        saveProfile(loadProfile().apply { setProperty(PROFILE_SHELL, shellDirName) })
    }

    /**
     * 使うバルーン。選んであればそれ、無ければ [recommendedBalloon]、次に descript.txt の default.balloon.path、balloon 指定。
     * どれもインストールされていなければ null（pono-droid の簡易バルーンを使う）。
     */
    fun balloon(balloons: BalloonRepository): Balloon? =
        when (val selected = loadProfile().getProperty(PROFILE_BALLOON)) {
            BALLOON_SIMPLE -> null
            null -> recommendedBalloon(balloons)
                ?: descript["default.balloon.path"]?.let { balloonByPath(balloons, it) }
                ?: descript["balloon"]?.let(balloons::findByName)
            else -> balloons.find(selected)
        }

    /** descript.txt の recommended.balloon.path か recommended.balloon（名前）のバルーン。入っていなければ null */
    fun recommendedBalloon(balloons: BalloonRepository): Balloon? =
        descript["recommended.balloon.path"]?.let { balloonByPath(balloons, it) } ?: descript["recommended.balloon"]?.let(balloons::findByName)

    /** recommended.balloon などで推奨のバルーンを決めているか */
    val hasRecommendedBalloon: Boolean get() = descript["recommended.balloon.path"] != null || descript["recommended.balloon"] != null

    /** balloon\名前 のような相対パスの、最後のディレクトリ名でバルーンを探す */
    private fun balloonByPath(balloons: BalloonRepository, path: String): Balloon? =
        path.replace('\\', '/').trim('/', ' ').substringAfterLast('/').takeIf { it.isNotEmpty() && it != ".." && it != "." }?.let(balloons::find)

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
