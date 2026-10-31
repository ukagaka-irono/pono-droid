import java.nio.ByteBuffer
import java.nio.CharBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

plugins {
    alias(libs.plugins.android.library)
}

val yayaDir = layout.projectDirectory.dir("src/main/cpp/yaya")
val generatedDir = layout.buildDirectory.dir("generated/yaya")

/**
 * 写したソースに当てるパッチ（サブモジュールは書き換えない）。[needle] がちょうど [expected] 回あることを確かめてから置き換える。
 * 改行は LF でも CRLF でもよい（[needle] と [replacement] は LF で書く）
 */
fun patchSource(text: String, name: String, needle: String, replacement: String, expected: Int = 1): String {
    val crlf = "\r\n" in text
    val n = if (crlf) needle.replace("\n", "\r\n") else needle
    val r = if (crlf) replacement.replace("\n", "\r\n") else replacement
    val count = text.split(n).size - 1
    if (count != expected) throw GradleException("patch: '${needle.lineSequence().first()}' appears $count times in $name (expected $expected)")
    return text.replace(n, r)
}

/**
 * [out] の中を [files]（相対パス → 中身）と同じにする。中身の変わらないファイルは書き直さず（cargo が全部作り直さないように）、
 * [files] に無いファイルは消す
 */
fun syncFiles(out: File, files: Map<String, ByteArray>) {
    files.forEach { (rel, bytes) ->
        val f = out.resolve(rel)
        if (!f.isFile || !f.readBytes().contentEquals(bytes)) f.apply { parentFile.mkdirs() }.writeBytes(bytes)
    }
    if (!out.isDirectory) return
    out.walkBottomUp().forEach { f ->
        val rel = f.relativeTo(out).invariantSeparatorsPath
        if (f.isFile && rel !in files) f.delete()
        if (f.isDirectory && f != out && f.listFiles().isNullOrEmpty()) f.delete()
    }
}

/** [needle] の [indices] 番目（0 から）の出現だけを [replacement] にする。全部で [total] 回あるはず */
fun patchOccurrences(text: String, name: String, needle: String, replacement: String, total: Int, indices: Set<Int>): String {
    val parts = text.split(needle)
    if (parts.size - 1 != total) throw GradleException("patch: '$needle' appears ${parts.size - 1} times in $name (expected $total)")
    val sb = StringBuilder(parts[0])
    for (i in 1 until parts.size) sb.append(if (i - 1 in indices) replacement else needle).append(parts[i])
    return sb.toString()
}

// YAYA: 拡張子の無い SAORI は SAORI-basic（実行ファイル）として扱われるが、internal_* は本体の内蔵モジュール
// （SAORI over DirectSSTP）なので DLL として読ませる。読む dlopen は shim/pono_sandbox.cpp が本体に渡す
fun patchYaya(name: String, text: String): String = when (name) {
    "lib1.cpp" -> patchSource(
        text, name,
        """	if ( ext == L".so" || ext == L".dylib" || ext == L".bundle" ) {
		return false;
	}
""",
        """	if ( ext == L".so" || ext == L".dylib" || ext == L".bundle" ) {
		return false;
	}
	// pono-droid: 拡張子の無い internal_* は本体の内蔵モジュール（SAORI over DirectSSTP）
	if ( ext.empty() && fname.size() > 9 ) {
		yaya::string_t head = fname.substr(0, 9);
		for ( size_t i = 0; i < head.size(); ++i ) {
			if ( head[i] >= L'A' && head[i] <= L'Z' ) {
				head[i] = static_cast<yaya::char_t>(head[i] - L'A' + L'a');
			}
		}
		if ( head == L"internal_" ) {
			return false;
		}
	}
""",
    )
    else -> text
}

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
            out.resolve(src.name).writeText("#line 1 \"$path\"\n${patchYaya(src.name, text.toString())}", Charsets.UTF_8)
        }
    }
}

// 里々: SAORI over DirectSSTP（本体の内蔵モジュール internal_* を ＠SAORI に書かずに呼ぶ）は Windows の WM_COPYDATA で
// 送るので POSIX では外してある。PONO_SAORI_DIRECT のときは有効にし、送る先を shim/pono_saori.cpp（本体）にする
fun patchSatori(path: String, text: String): String {
    val enable = "#if !defined(POSIX) || defined(PONO_SAORI_DIRECT)"
    return when (path) {
        // 0 番目は windows.h なのでそのまま
        "satori/shiori_plugin.h" -> patchOccurrences(text, path, "#ifndef POSIX", enable, total = 4, indices = setOf(1, 2, 3))
        // 5 番目は Windows の .dl2 の名前の付け替えなのでそのまま
        "satori/shiori_plugin.cpp" -> patchOccurrences(text, path, "#ifndef POSIX", enable, total = 6, indices = setOf(0, 1, 2, 3, 4))
        "satori/SaoriClient.cpp", "satori/SaoriClient.h" -> patchOccurrences(text, path, "#ifndef POSIX", enable, total = 1, indices = setOf(0))
        "satori/satori.h" -> patchSource(
            patchSource(text, path, "#ifndef SATORI_H\n#define SATORI_H\n", "#ifndef SATORI_H\n#define SATORI_H\n#ifdef PONO_SAORI_DIRECT\n#include \"pono_saori.h\"\n#endif\n"),
            path,
            """	bool	CanSendSaoriDirectSSTP() const
	{
""",
            """	bool	CanSendSaoriDirectSSTP() const
	{
#ifdef PONO_SAORI_DIRECT
		if ( pono_saori_direct_available() ) {
			return true;
		}
#endif
""",
        )
        "satori/satori_builtin.cpp" -> patchSource(
            text, path,
            """bool Satori::SendSaoriDirectSSTP(const std::string& i_request, std::string& o_response)
{
	o_response.erase();
""",
            """bool Satori::SendSaoriDirectSSTP(const std::string& i_request, std::string& o_response)
{
	o_response.erase();
#ifdef PONO_SAORI_DIRECT
	{
		long out_len = 0;
		char* out = pono_saori_direct(NULL, i_request.data(), (long)i_request.size(), &out_len);
		if ( out != NULL ) {
			o_response.assign(out, out_len);
			free(out);
			return true;
		}
	}
#endif
""",
        )
        else -> text
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
            val rel = src.relativeTo(root).invariantSeparatorsPath
            val dest = out.resolve(rel)
            dest.parentFile.mkdirs()
            val path = src.absolutePath.replace('\\', '/')
            val patched = patchSatori(rel, text.toString())
            // ヘッダに #line を入れると #pragma once が効かなくなることがあるので、.cpp だけに付ける
            dest.writeText(if (src.extension == "cpp") "#line 1 \"$path\"\n$patched" else patched, Charsets.UTF_8)
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

// tukinami さんたちの Rust の SAORI（サブモジュール saori/saori-*）。Windows だけの lib.rs（DllMain と HGLOBAL）と
// chars.rs（MultiByteToWideChar）を src/main/rust の pono_entry.rs・pono_chars.rs に替え、ほかのソースはそのまま写す。
// Cargo.toml と Cargo.lock は src/main/rust/<名前>/ のもの。ビルドは CMake が cargo を呼ぶ
val rustSaoris = listOf(
    "saori-calcs", "saori-japanese-gengou", "saori-resized-png-mini", "saori-qrcode", "saori-resized-png", "saori-pie-chart",
)
// 元の procedure が DLL のディレクトリに足すパス（SAORI の引数）の数。Windows の区切り（\）を / にする
val rustSaoriPathArgs = mapOf(
    "saori-resized-png-mini" to 4, "saori-qrcode" to 1, "saori-resized-png" to 3, "saori-pie-chart" to 1,
)
val rustDir = layout.projectDirectory.dir("src/main/rust")
val saoriDir = layout.projectDirectory.dir("src/main/cpp/saori")
val rustSaoriGeneratedDir = layout.buildDirectory.dir("generated/rust-saori")
val prepareRustSaoriSources = tasks.register("prepareRustSaoriSources") {
    val sources = saoriDir.asFileTree.matching { rustSaoris.forEach { include("$it/src/**/*.rs") } }
    inputs.files(sources)
    // licenses.txt は写さない（collectRustSaoriLicenses が書く）
    inputs.files(rustDir.asFileTree.matching { exclude("licenses.txt") })
    outputs.dir(rustSaoriGeneratedDir)
    doLast {
        val out = rustSaoriGeneratedDir.get().asFile
        // 写すもの（out からの相対パス → 中身）。中身の変わらないファイルは書き直さない（cargo が全部作り直さないように）
        val files = sortedMapOf<String, ByteArray>()
        val joinPath = Regex("""\.join\(([a-z_]+)\)""")
        for (name in rustSaoris) {
            val src = saoriDir.dir("$name/src").asFile
            val dest = "$name/src"
            src.walkTopDown().filter { it.isFile && it.extension == "rs" }.forEach { file ->
                val rel = file.relativeTo(src).invariantSeparatorsPath
                if (rel == "lib.rs" || rel == "chars.rs") return@forEach
                var text = file.readText()
                if (rel == "procedure.rs") {
                    val expected = rustSaoriPathArgs[name] ?: 0
                    val count = joinPath.findAll(text).count()
                    if (count != expected) throw GradleException("$name: $count path joins in procedure.rs (expected $expected)")
                    text = text.replace(joinPath, ".join(crate::pono_entry::native_path(\$1))")
                }
                files["$dest/$rel"] = text.toByteArray()
            }
            // lib.rs は元のモジュールの並びだけを使う
            val mods = saoriDir.file("$name/src/lib.rs").asFile.readLines().filter { it.matches(Regex("""mod [a-z_]+;""")) }
            if ("mod chars;" !in mods || "mod procedure;" !in mods) throw GradleException("$name: unexpected lib.rs modules $mods")
            files["$dest/lib.rs"] =
                ("// generated by :shiori-yaya:prepareRustSaoriSources\n" + (mods + "mod pono_entry;").joinToString("\n") + "\n").toByteArray()
            files["$dest/chars.rs"] = rustDir.file("pono_chars.rs").asFile.readBytes()
            files["$dest/pono_entry.rs"] = rustDir.file("pono_entry.rs").asFile.readBytes()
            rustDir.dir(name).asFile.listFiles()!!.forEach { files["$name/${it.name}"] = it.readBytes() }
        }
        syncFiles(out, files)
    }
}

// ---- Rust の SHIORI ----
// 湊（minato）: Windows の winapi（GlobalAlloc と LoadLibraryW）だけを使うので、src/main/rust/pono-winapi（同じ名前の関数を
// libc で作ったもの）に替える。ソースはそのまま（構文チェッカーの src/bin は写さない）。
// pasta: SHIORI に要る 4 つの crate を写し、ワークスペースの Cargo.toml を src/main/rust/pasta のもの（Lua を LuaJIT から
// PUC Lua 5.4 にしたもの）に替える。LuaJIT にしか無いライブラリ（jit・ffi・bit）の指定は、何も開かないことにする
val minatoDir = layout.projectDirectory.dir("src/main/cpp/minato")
val pastaDir = layout.projectDirectory.dir("src/main/cpp/pasta")
val pastaCrates = listOf("pasta_core", "pasta_dsl", "pasta_lua", "pasta_shiori")
val rustShioriGeneratedDir = layout.buildDirectory.dir("generated/rust-shiori")
val prepareRustShioriSources = tasks.register("prepareRustShioriSources") {
    val minatoSources = minatoDir.asFileTree.matching { include("src/**"); exclude("src/bin/**") }
    val pastaSources = pastaDir.asFileTree.matching { pastaCrates.forEach { include("crates/$it/**"); exclude("crates/$it/tests/**") } }
    inputs.files(minatoSources, pastaSources)
    inputs.files(rustDir.asFileTree.matching { include("minato/**", "pono-winapi/**", "pasta/**") })
    outputs.dir(rustShioriGeneratedDir)
    doLast {
        val files = sortedMapOf<String, ByteArray>()
        fun add(prefix: String, base: File, tree: FileTree) = tree.forEach { files["$prefix/${it.relativeTo(base).invariantSeparatorsPath}"] = it.readBytes() }
        add("minato", minatoDir.asFile, minatoSources)
        add("minato", rustDir.dir("minato").asFile, rustDir.dir("minato").asFileTree)
        add("pono-winapi", rustDir.dir("pono-winapi").asFile, rustDir.dir("pono-winapi").asFileTree)
        add("pasta", pastaDir.asFile, pastaSources)
        add("pasta", rustDir.dir("pasta").asFile, rustDir.dir("pasta").asFileTree)
        fun patch(rel: String, needle: String, replacement: String) {
            val text = String(files.getValue(rel), Charsets.UTF_8)
            files[rel] = patchSource(text, rel, needle, replacement).toByteArray(Charsets.UTF_8)
        }
        // SHIORI の .so に入れるので staticlib にする
        patch("pasta/crates/pasta_shiori/Cargo.toml", "crate-type = [\"cdylib\", \"rlib\"]", "crate-type = [\"staticlib\"]")
        patch("pasta/crates/pasta_lua/src/runtime/runtime_config.rs", "\"std_jit\" => Ok(StdLib::JIT),", "\"std_jit\" => Ok(StdLib::NONE),")
        patch("pasta/crates/pasta_lua/src/runtime/runtime_config.rs", "\"std_ffi\" => Ok(StdLib::FFI),", "\"std_ffi\" => Ok(StdLib::NONE),")
        patch("pasta/crates/pasta_lua/src/runtime/runtime_config.rs", "\"std_bit\" => Ok(StdLib::BIT),", "\"std_bit\" => Ok(StdLib::NONE),")
        // LuaJIT の package.loaders は、Lua 5.4 では package.searchers（中身の並びは同じ）
        patch("pasta/crates/pasta_lua/src/runtime/searcher.rs", "let loaders: Table = package.get(\"loaders\")?;", "let loaders: Table = package.get(\"searchers\")?;")
        syncFiles(rustShioriGeneratedDir.get().asFile, files)
    }
}

// cargo（Rust）。CARGO があればそれ、無ければ ~/.cargo/bin、それも無ければ PATH の cargo
val cargoPath: String = System.getenv("CARGO")
    ?: File(System.getProperty("user.home"), ".cargo/bin/cargo" + if (System.getProperty("os.name").startsWith("Windows")) ".exe" else "")
        .takeIf { it.isFile }?.absolutePath
    ?: "cargo"

// Rust の SAORI と SHIORI が Android に入れる crate（proc-macro と build 用を除く）のライセンスの文面を src/main/rust/licenses.txt にまとめる。
// アプリのライセンス表記（:app:generateLicenseAssets）が読む。Cargo.lock を変えたら、一度ビルドして crate を落としてから手で回す。
// 複数のライセンスから選べる crate は MIT（無ければ BSD・Zlib・CC0 の順）の文面を使う。文面のファイルが無い MIT の crate は、
// Cargo.toml の authors で MIT の定型文を書く
tasks.register("collectRustSaoriLicenses") {
    dependsOn(prepareRustSaoriSources, prepareRustShioriSources)
    doLast {
        fun cargo(vararg args: String, dir: File): String {
            val p = ProcessBuilder(listOf(cargoPath) + args).directory(dir).redirectError(ProcessBuilder.Redirect.INHERIT).start()
            val out = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) throw GradleException("cargo ${args.first()} failed in $dir")
            return out
        }
        val preference = listOf("MIT", "BSD-3-Clause", "BSD-2-Clause", "Zlib", "CC0-1.0", "Unlicense", "Unicode-3.0", "Apache-2.0")
        val keywords = mapOf(
            "MIT" to "MIT", "BSD-3-Clause" to "BSD", "BSD-2-Clause" to "BSD", "Zlib" to "ZLIB", "CC0-1.0" to "CC0",
            "Unlicense" to "UNLICENSE", "Unicode-3.0" to "UNICODE", "Apache-2.0" to "APACHE",
        )
        // 名前 版 → ライセンス・URL・文面
        val result = sortedMapOf<String, String>()
        // ワークスペース → cargo tree に足す引数
        val workspaces = rustSaoris.map { rustSaoriGeneratedDir.get().asFile.resolve(it) to emptyList<String>() } + listOf(
            rustShioriGeneratedDir.get().asFile.resolve("minato") to emptyList(),
            rustShioriGeneratedDir.get().asFile.resolve("pasta") to listOf("-p", "pasta_shiori"),
        )
        for ((dir, extra) in workspaces) {
            // 自分たちのもの（ワークスペースの中と path の依存。pono-winapi など）は除く。git の依存は残す
            val used = cargo(
                *(listOf("tree", "--target", "aarch64-linux-android", "-e", "normal,no-proc-macro", "--prefix", "none",
                    "--locked", "--offline", "--format", "{p}") + extra).toTypedArray(), dir = dir,
            ).lines().map { it.removeSuffix(" (*)").split(" ") }
                .filter { it.size == 2 || (it.size >= 3 && it[2].startsWith("(https://")) }
                .map { it[0] to it[1].removePrefix("v") }.toSet()
            @Suppress("UNCHECKED_CAST")
            val packages = (groovy.json.JsonSlurper().parseText(
                cargo("metadata", "--format-version", "1", "--locked", "--offline", "--filter-platform", "aarch64-linux-android", dir = dir),
            ) as Map<String, Any?>)["packages"] as List<Map<String, Any?>>
            for (pkg in packages) {
                val key = pkg["name"] as String to pkg["version"] as String
                if (key !in used) continue
                // license が無く license-file だけの crate（seahorse）は、その文面をそのまま載せる
                val licenseFile = (pkg["license_file"] as String?)?.let { File(File(pkg["manifest_path"] as String).parentFile, it) }
                if (pkg["license"] == null && licenseFile != null && licenseFile.isFile) {
                    val text = licenseFile.readText().replace("\r\n", "\n").trim()
                    val label = if ("Permission is hereby granted, free of charge" in text) "MIT" else "See the license text"
                    val url = (pkg["repository"] as String?) ?: "https://crates.io/crates/${key.first}"
                    result["${key.first} ${key.second}"] = "License: $label\nURL: $url\n\n$text"
                    continue
                }
                val expr = pkg["license"] as String? ?: throw GradleException("${key.first}: no license in Cargo.toml")
                // 「(A OR B) AND C」のように AND で並ぶものは、それぞれから 1 つ選んで全部の文面を載せる（encoding_rs など）
                val chosenList = expr.split(" AND ").map { part ->
                    val options = part.trim().removePrefix("(").removeSuffix(")").split(" OR ", "/").map { it.trim() }
                    preference.firstOrNull { it in options } ?: throw GradleException("${key.first}: unknown license $expr")
                }
                val chosen = chosenList.joinToString(" AND ")
                val crateDir = File(pkg["manifest_path"] as String).parentFile
                val files = crateDir.listFiles()!!.filter { it.isFile && Regex("(?i)^(licen[cs]e|copying|unlicense)").containsMatchIn(it.name) }
                val generic = files.filter { f -> keywords.values.none { it in f.name.uppercase() } }
                val texts = chosenList.flatMap { one ->
                    files.filter { f -> keywords.getValue(one) in f.name.uppercase().replace("0BSD", "") }.ifEmpty { generic }
                }.distinct().sortedBy { it.name }.map { it.readText().replace("\r\n", "\n").trim() }
                val notices = crateDir.listFiles()!!.filter { it.isFile && it.name.uppercase().startsWith("COPYRIGHT") }
                    .map { it.readText().replace("\r\n", "\n").trim() }
                val text = when {
                    texts.isNotEmpty() -> (texts + notices).joinToString("\n\n")
                    chosen == "MIT" -> {
                        @Suppress("UNCHECKED_CAST")
                        val authors = (pkg["authors"] as List<String>).joinToString(", ") { it.substringBefore(" <") }
                        "Copyright (c) ${authors.ifEmpty { "the ${key.first} authors" }}\n\n" + """
                            Permission is hereby granted, free of charge, to any person obtaining a copy
                            of this software and associated documentation files (the "Software"), to deal
                            in the Software without restriction, including without limitation the rights
                            to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
                            copies of the Software, and to permit persons to whom the Software is
                            furnished to do so, subject to the following conditions:

                            The above copyright notice and this permission notice shall be included in all
                            copies or substantial portions of the Software.

                            THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
                            IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
                            FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
                            AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
                            LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
                            OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
                            SOFTWARE.
                        """.trimIndent()
                    }
                    else -> throw GradleException("${key.first}: no license file for $chosen")
                }
                // git から取る crate（svg-pie-chart）は、その git の URL
                val git = (pkg["source"] as String?)?.takeIf { it.startsWith("git+") }?.removePrefix("git+")?.substringBefore('#')?.removeSuffix(".git")
                val url = (pkg["repository"] as String?) ?: git ?: "https://crates.io/crates/${key.first}"
                result["${key.first} ${key.second}"] = "License: $chosen\nURL: $url\n\n$text"
            }
        }
        // Rust の標準ライブラリ（staticlib に入る）。文面はツールチェーンの share/doc/rust/COPYRIGHT-library.html から切り出す:
        // 全体の説明と、標準ライブラリが Android で使う crate（rustlib/<target>/lib の rlib のうち、テスト用の getopts・test などを
        // 除いたもの）の MIT の文面
        run {
            val rustc = File(cargoPath).parentFile?.let { File(it, "rustc").path } ?: "rustc"
            val sysroot = File(
                ProcessBuilder(rustc, "--print", "sysroot").start()
                    .inputStream.bufferedReader().readText().trim(),
            )
            val version = ProcessBuilder(rustc, "--version").start()
                .inputStream.bufferedReader().readText().trim().split(" ")[1]
            val html = sysroot.resolve("share/doc/rust/COPYRIGHT-library.html").readText().replace("\r\n", "\n")
            fun plain(s: String) = s.replace(Regex("<[^>]*>"), "").replace("&#34;", "\"").replace("&#39;", "'")
                .replace("&#60;", "<").replace("&#62;", ">").replace("&amp;", "&")
            val head = plain(html.substringAfter("<h2 id=\"short-version\">", "").substringBefore("<h2 id=\"out-of-tree-dependencies\">"))
                .lines().map { it.trim() }.filter { it.isNotEmpty() }.joinToString("\n")
            if (head.isEmpty()) throw GradleException("COPYRIGHT-library.html: unexpected layout")
            val stdCrates = listOf("addr2line", "adler2", "cfg-if", "gimli", "hashbrown", "libc", "memchr", "miniz_oxide", "object", "rustc-demangle")
            val sections = html.split("<h3>📦 ").drop(1).mapNotNull { s ->
                val title = s.substringBefore("</h3>")
                val crate = title.substringBeforeLast("-")
                if (crate !in stdCrates) return@mapNotNull null
                val details = Regex("<summary><code>([^<]*)</code></summary>\\s*<pre>(.*?)</pre>", RegexOption.DOT_MATCHES_ALL).findAll(s).toList()
                val mit = details.filter { "MIT" in it.groupValues[1].uppercase() }.ifEmpty { details }
                "-- $title\n\n" + mit.joinToString("\n\n") { plain(it.groupValues[2]).trim() }
            }
            val mitText = sysroot.resolve("share/doc/rust/licenses/MIT.txt").readText().replace("\r\n", "\n").trim()
            // library/core/src/unicode の表は Unicode-3.0
            val unicodeText = sysroot.resolve("share/doc/rust/licenses/Unicode-3.0.txt").readText().replace("\r\n", "\n").trim()
            result["~Rust standard library $version"] = "License: MIT\nURL: https://github.com/rust-lang/rust\n\n" +
                head + "\n\n" + mitText + "\n\n-- Unicode-3.0 (library/core/src/unicode)\n\n" + unicodeText + (if (sections.isEmpty()) "" else "\n\n" + sections.joinToString("\n\n"))
        }
        rustDir.file("licenses.txt").asFile.writeText(buildString {
            append("# generated by :shiori-yaya:collectRustSaoriLicenses（Rust の SAORI が Android に入れる crate のライセンス）\n")
            result.forEach { (k, v) -> append("\n==== $k\n").append(v).append("\n") }
        })
        logger.lifecycle("collectRustSaoriLicenses: ${result.size} crates")
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
                    "-DRUST_SAORI_DIR=${rustSaoriGeneratedDir.get().asFile.absolutePath.replace('\\', '/')}",
                    "-DRUST_SHIORI_DIR=${rustShioriGeneratedDir.get().asFile.absolutePath.replace('\\', '/')}",
                    // cargo の出力は ABI と debug / release で分けず 1 つにする（どれも --release で、target ごとに分かれる）
                    "-DRUST_TARGET_DIR=${layout.buildDirectory.dir("rust-target").get().asFile.absolutePath.replace('\\', '/')}",
                    "-DCARGO=${cargoPath.replace('\\', '/')}",
                    // libc++ を各 .so に入れる。c++_shared だと std::fstream などが libc++_shared.so の中から fopen を呼び、
                    // --wrap（pono_sandbox）を通らない
                    "-DANDROID_STL=c++_static",
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
    dependsOn(convertYayaSources, convertSatoriSources, prepareKawariSources, generateCp932Table, prepareRustSaoriSources, prepareRustShioriSources)
}

dependencies {
    implementation(project(":baseware"))
    androidTestImplementation(libs.kotlinx.coroutines.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
    androidTestImplementation(libs.junit)
}
