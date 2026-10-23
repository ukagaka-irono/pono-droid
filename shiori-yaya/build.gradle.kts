import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

plugins {
    alias(libs.plugins.android.library)
}

val yayaDir = layout.projectDirectory.dir("src/main/cpp/yaya")
val generatedDir = layout.buildDirectory.dir("generated/yaya")

// YAYA のソースは Shift_JIS(CP932) なので、clang に渡す前に UTF-8 へ変換する。
// #line で元のファイルを指すので、エラー位置はサブモジュール側のファイルになる。
val convertYayaSources = tasks.register("convertYayaSources") {
    val sources = yayaDir.asFileTree.matching { include("*.cpp") }
    inputs.files(sources)
    outputs.dir(generatedDir.map { it.dir("src") })
    doLast {
        val out = generatedDir.get().dir("src").asFile
        out.deleteRecursively()
        out.mkdirs()
        val cp932 = Charset.forName("windows-31j")
        sources.forEach { src ->
            val text = cp932.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(src.readBytes()))
            val path = src.absolutePath.replace('\\', '/')
            out.resolve(src.name).writeText("#line 1 \"$path\"\n$text", Charsets.UTF_8)
        }
    }
}

// 里々のソース（satori・_・uka_regexp の .cpp と .h）も Shift_JIS(CP932)。同じく UTF-8 にして、ディレクトリの形を保って写す
val satoriDir = layout.projectDirectory.dir("src/main/cpp/satori/satoriya")
val satoriGeneratedDir = layout.buildDirectory.dir("generated/satori")
val convertSatoriSources = tasks.register("convertSatoriSources") {
    val subdirs = listOf("satori", "_", "uka_regexp")
    val sources = satoriDir.asFileTree.matching { subdirs.forEach { include("$it/*.cpp", "$it/*.h") } }
    inputs.files(sources)
    outputs.dir(satoriGeneratedDir)
    doLast {
        val out = satoriGeneratedDir.get().asFile
        out.deleteRecursively()
        val cp932 = Charset.forName("windows-31j")
        val root = satoriDir.asFile
        sources.forEach { src ->
            val text = cp932.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(src.readBytes()))
            val dest = out.resolve(src.relativeTo(root).path)
            dest.parentFile.mkdirs()
            val path = src.absolutePath.replace('\\', '/')
            // ヘッダに #line を入れると #pragma once が効かなくなることがあるので、.cpp だけに付ける
            dest.writeText(if (src.extension == "cpp") "#line 1 \"$path\"\n$text" else text.toString(), Charsets.UTF_8)
        }
    }
}

// 華和梨は 32bit 時代のコードで、string::find の結果を unsigned int に入れてから string::npos と比べている。
// 64bit では npos が unsigned int に収まらず比較が常に偽になり、文字列の外を読んで落ちる。
// サブモジュールは書き換えず、写したソースの該当する宣言だけを size_t にする（Debian の 009_kawari8-64bit-fix と同じ直し）
val kawariDir = layout.projectDirectory.dir("src/main/cpp/kawari/build/src")
val kawariGeneratedDir = layout.buildDirectory.dir("generated/kawari")
val prepareKawariSources = tasks.register("prepareKawariSources") {
    val sources = kawariDir.asFileTree.matching { include("**/*.cpp", "**/*.h", "**/*.inc"); exclude("**/old/**") }
    inputs.files(sources)
    outputs.dir(kawariGeneratedDir)
    doLast {
        val out = kawariGeneratedDir.get().asFile
        out.deleteRecursively()
        val root = kawariDir.asFile
        sources.forEach { src -> src.copyTo(out.resolve(src.relativeTo(root).path)) }
        // ファイル → 直す宣言（"unsigned int " を "size_t " にする）と、その宣言がいくつあるはずか
        val fixes = mapOf(
            "kis/kis_file.cpp" to listOf("unsigned int pos=ctow(dirname).rfind(FILE_SEPARATOR);" to 1),
            "kis/kis_saori.cpp" to listOf(
                "unsigned int pos=statusline.find(' ');" to 1,
                "unsigned int npos=statusline.find(' ', pos+1);" to 1,
            ),
            "kis/kis_split.cpp" to listOf("unsigned int idx;" to 1),
            "kis/kis_string.cpp" to listOf(
                "unsigned int pos=(f)?" to 1,
                "unsigned int pos=tmp;" to 1,
                "unsigned int pos=0;" to 1,
                "unsigned int index=search.find(target[pos]);" to 1,
            ),
            "libkawari/kawari_code.cpp" to listOf("unsigned int pos2=ws.find_first_of(illchs, pos);" to 1),
            "misc/misc.cpp" to listOf(
                "unsigned int pos=bp.rfind(SEPARATOR);" to 1,
                "unsigned int pos=path.rfind(SEPARATOR);" to 2,
            ),
            "misc/phttp.cpp" to listOf("unsigned int pos=buff.find(':');" to 1),
            "saori/saori_native.cpp" to listOf("unsigned int pos=path.find_last_of(FILE_SEPARATOR);" to 1),
        )
        // ソースは EUC-JP だが、直す行は ASCII だけなので ISO-8859-1 で読み書きしてバイトを変えない
        for ((path, list) in fixes) {
            val file = out.resolve(path)
            var text = file.readText(Charsets.ISO_8859_1)
            for ((needle, expected) in list) {
                val count = text.split(needle).size - 1
                if (count != expected) throw GradleException("kawari 64bit fix: '$needle' appears $count times in $path (expected $expected)")
                text = text.replace(needle, "size_t " + needle.removePrefix("unsigned int "))
            }
            file.writeText(text, Charsets.ISO_8859_1)
        }
    }
}

// Android の libc の iconv は CP932 を持たないので、Java の windows-31j から変換表を作る
val generateCp932Table = tasks.register("generateCp932Table") {
    outputs.dir(generatedDir.map { it.dir("include") })
    doLast {
        val out = generatedDir.get().dir("include").asFile
        out.mkdirs()
        val cp932 = Charset.forName("windows-31j")
        val decode = sortedMapOf<Int, Int>()
        for (lead in (0x81..0x9F) + (0xE0..0xFC)) {
            for (trail in (0x40..0x7E) + (0x80..0xFC)) {
                val s = cp932.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .runCatching { decode(ByteBuffer.wrap(byteArrayOf(lead.toByte(), trail.toByte()))).toString() }
                    .getOrNull() ?: continue
                if (s.length == 1) decode[(lead shl 8) or trail] = s[0].code
            }
        }
        // 同じ文字に複数のコードがある（NEC/IBM 拡張の重複）ので、エンコード側は Java のエンコーダの選択に合わせる
        val encode = sortedMapOf<Int, Int>()
        for (ucs in decode.values.toSet()) {
            val bytes = cp932.newEncoder().encode(CharBuffer.wrap(charArrayOf(ucs.toChar())))
            if (bytes.remaining() == 2) {
                encode[ucs] = ((bytes.get().toInt() and 0xFF) shl 8) or (bytes.get().toInt() and 0xFF)
            }
        }
        out.resolve("cp932_table.inc").writeText(buildString {
            appendLine("// generated by :shiori-yaya:generateCp932Table")
            appendLine("static const uint16_t kCp932Decode[][2] = {")
            decode.forEach { (k, v) -> appendLine("{0x%04X,0x%04X},".format(k, v)) }
            appendLine("};")
            appendLine("static const uint16_t kCp932Encode[][2] = {")
            encode.forEach { (k, v) -> appendLine("{0x%04X,0x%04X},".format(k, v)) }
            appendLine("};")
        })
    }
}

android {
    namespace = "net.pono.shiori.yaya"
    compileSdk = 37
    ndkVersion = "29.0.14206865"

    defaultConfig {
        minSdk = 26
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        externalNativeBuild {
            cmake {
                arguments += listOf(
                    "-DYAYA_DIR=${yayaDir.asFile.absolutePath.replace('\\', '/')}",
                    "-DYAYA_GENERATED_DIR=${generatedDir.get().asFile.absolutePath.replace('\\', '/')}",
                    "-DSATORI_GENERATED_DIR=${satoriGeneratedDir.get().asFile.absolutePath.replace('\\', '/')}",
                    "-DKAWARI_GENERATED_DIR=${kawariGeneratedDir.get().asFile.absolutePath.replace('\\', '/')}",
                    "-DANDROID_STL=c++_shared",
                )
            }
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        aidl = true
    }
}

tasks.matching { it.name.startsWith("configureCMake") || it.name.startsWith("buildCMake") }.configureEach {
    dependsOn(convertYayaSources, convertSatoriSources, prepareKawariSources, generateCp932Table)
}

dependencies {
    implementation(project(":baseware"))
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
