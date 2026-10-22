package net.pono.baseware.ghost

import net.pono.baseware.text.Descript
import net.pono.baseware.text.TextCharsets
import java.io.File
import java.nio.charset.Charset

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

    val isValid: Boolean get() = File(masterDir, "descript.txt").isFile
}

class GhostRepository(val root: File) {
    init {
        root.mkdirs()
    }

    fun list(): List<Ghost> =
        root.listFiles { f -> f.isDirectory }?.map(::Ghost)?.filter { it.isValid }?.sortedBy { it.name }.orEmpty()

    fun find(dirName: String): Ghost? = Ghost(File(root, dirName)).takeIf { it.isValid }

    fun installer() = NarInstaller(root)
}
