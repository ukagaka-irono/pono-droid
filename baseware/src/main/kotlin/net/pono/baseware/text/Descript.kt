package net.pono.baseware.text

import java.io.File

/** descript.txt / install.txt 形式（`key,value` の行）の設定。 */
class Descript(val entries: Map<String, String>) {
    operator fun get(key: String): String? = entries[key]

    fun int(key: String): Int? = entries[key]?.trim()?.toIntOrNull()

    companion object {
        fun parse(text: String): Descript {
            val map = LinkedHashMap<String, String>()
            for (raw in text.lineSequence()) {
                val line = raw.trim()
                if (line.isEmpty() || line.startsWith("//")) continue
                val comma = line.indexOf(',')
                if (comma <= 0) continue
                map.putIfAbsent(line.substring(0, comma).trim(), line.substring(comma + 1).trim())
            }
            return Descript(map)
        }

        fun read(file: File): Descript =
            if (file.isFile) parse(TextCharsets.decode(file.readBytes())) else Descript(emptyMap())
    }
}
