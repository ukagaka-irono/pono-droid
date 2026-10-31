import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * 同梱しているサードパーティのライセンス表記を assets/licenses に集める。
 * 文面は手で写さず、実際にビルドに入るソースから切り出すので、サブモジュールを更新しても食い違わない。
 * 切り出せなかったら（ソースの書き方が変わったら）ビルドを止める。
 */
abstract class GenerateLicenseAssets : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val cppDir: DirectoryProperty

    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val apacheLicense: RegularFileProperty

    /** Rust の SAORI が入れる crate のライセンス（:shiori-yaya:collectRustSaoriLicenses が書いたもの） */
    @get:InputFile
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val rustLicenses: RegularFileProperty

    @get:OutputDirectory
    abstract val outputDir: DirectoryProperty

    private class Entry(val name: String, val license: String, val url: String, val text: String)

    @TaskAction
    fun generate() {
        // shiori-yaya/src/main/cpp。SHIORI とその中で使っているもののサブモジュールが並ぶ
        val cpp = cppDir.get().asFile
        fun read(path: String): String {
            val bytes = cpp.resolve(path).readBytes()
            // YAYA・里々のソースは CP932、ほかは UTF-8 / ASCII
            return runCatching {
                Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes)).toString()
            }.getOrElse { String(bytes, Charset.forName("windows-31j")) }
                .replace("\r\n", "\n").removePrefix("﻿")
        }

        /** [from] を含む行から [to] を含む行まで。コメントの記号と共通の字下げは落とす */
        fun cut(path: String, from: String, to: String): String {
            val lines = read(path).lines()
            val start = lines.indexOfFirst { from in it }
            val end = lines.indexOfFirst { to in it }
            if (start < 0 || end < start) throw GradleException("license text not found in $path: '$from' .. '$to'")
            return lines.subList(start, end + 1)
                .joinToString("\n") { it.replace(Regex("""^\s*(//|\*\*|\*(?=\s|$))"""), "").trimEnd() }
                .trimIndent()
        }

        // ==== <名前> <版> の見出しの後に License: と URL: の行、空行、文面
        val rustEntries = rustLicenses.get().asFile.readText().replace("\r\n", "\n").split("\n==== ").drop(1).map { block ->
            val title = block.substringBefore('\n')
            val license = block.substringAfter("\nLicense: ").substringBefore('\n')
            val url = block.substringAfter("\nURL: ").substringBefore('\n')
            Entry(title.removePrefix("~"), license, url, block.substringAfter("\n\n").trim())
        }
        if (rustEntries.isEmpty()) throw GradleException("no entries in ${rustLicenses.get().asFile}")

        val entries = listOf(
            Entry(
                "pono-droid", "Apache-2.0", "https://github.com/ukagaka-irono/pono-droid",
                "Copyright 2026 ukagaka-irono\n\nLicensed under the Apache License, Version 2.0.",
            ),
            Entry("YAYA", "BSD 3-Clause", "https://github.com/YAYA-shiori/yaya-shiori", read("yaya/LICENSE").trim()),
            Entry(
                "Mersenne Twister (MT19937)", "BSD 3-Clause", "http://www.math.sci.hiroshima-u.ac.jp/m-mat/MT/emt.html",
                cut("yaya/mt19937ar.cpp", "Copyright (C) 1997 - 2002", "SUCH DAMAGE."),
            ),
            Entry("parson", "MIT", "https://github.com/kgabis/parson", read("yaya/parson/LICENSE").trim()),
            Entry(
                "TinyXML-2", "zlib", "https://github.com/leethomason/tinyxml2",
                cut("yaya/tinyxml2/tinyxml2.cpp", "Original code by Lee Thomason", "distribution."),
            ),
            Entry(
                "Gumbo", "Apache-2.0", "https://github.com/google/gumbo-parser",
                cut("yaya/gumbo/src/parser.c", "Copyright 2010 Google Inc.", "limitations under the License.") +
                    "\n\nGumbo contains the UTF-8 decoder below.\n\n" +
                    cut("yaya/gumbo/src/utf8.c", "Copyright (c) 2008-2009 Bjoern Hoehrmann", "substantial portions of the Software."),
            ),
            Entry("uka_regexp", "Unlicense", "https://github.com/ukatech/uka_regexp", read("yaya/uka_regexp/LICENSE").trim()),
            Entry("里々（SATORI）", "BSD 2-Clause", "https://github.com/ukatech/satoriya-shiori", read("satori/LICENSE").trim()),
            Entry("華和梨（KAWARI）", "BSD", "https://github.com/kawari/kawari", read("kawari/LICENSE").trim()),
            Entry("Kagari", "MIT", "https://github.com/Tatakinov/kagari_shiori", read("kagari/LICENSE").trim()),
            Entry("QuickShiori", "BSD-3-Clause", "https://github.com/cronfox/quickshiori", read("quickshiori/LICENSE").trim()),
            Entry("QuickJS-ng", "MIT", "https://github.com/quickjs-ng/quickjs", read("quickshiori/3rd/quickjs/LICENSE").trim()),
            Entry("qjs-ng-sqlite3", "MIT", "https://github.com/cronfox/qjs-ng-sqlite3", read("quickshiori/3rd/qjs-ng-sqlite3/LICENSE.txt").trim()),
            // Kagari に入れる Lua の C のモジュール（luamod/）
            Entry("luautf8", "MIT", "https://github.com/starwing/luautf8", read("luamod/luautf8/LICENSE").trim()),
            Entry("lua-iconv", "MIT", "https://github.com/ittner/lua-iconv", read("luamod/lua-iconv/COPYING").trim()),
            Entry("luachild", "Unlicense", "https://github.com/Tatakinov/luachild", read("luamod/luachild/COPYING.txt").trim()),
            Entry("LuaSocket", "MIT", "https://github.com/lunarmodules/luasocket", read("luamod/luasocket/LICENSE").trim()),
            Entry("Lua Lanes", "MIT", "https://github.com/LuaLanes/lanes", read("luamod/lanes/COPYRIGHT").trim()),
            Entry("LuaSec", "MIT", "https://github.com/lunarmodules/luasec", read("luamod/luasec/LICENSE").trim()),
            Entry("BoringSSL", "Apache-2.0", "https://boringssl.googlesource.com/boringssl", read("luamod/boringssl/LICENSE").trim()),
            // ゴーストに同梱の SAORI の代わりに、ソースから作るもの（saori/）
            Entry("mahjong_nodoka", "MIT", "https://github.com/Tatakinov/mahjong_nodoka", read("saori/mahjong_nodoka/LICENSE").trim()),
            Entry("TexasHoldemUtil", "MIT", "https://github.com/Tatakinov/TexasHoldemUtil", read("saori/TexasHoldemUtil/LICENSE").trim()),
            Entry("saori_budoux", "MIT", "https://github.com/Tatakinov/saori_budoux", read("saori/saori_budoux/LICENSE").trim()),
            // 伺かゴースト用ライセンスと修正 BSD ライセンスの両方が書いてある（csaori.h も同じ配布物のもの）
            Entry("MahjongUtil", "BSD-3-Clause", "https://github.com/nikolat/MahjongUtil", read("saori/MahjongUtil/license.txt").trim()),
            // Rust の SAORI（中で使う crate と Rust の標準ライブラリは後ろにまとめて並ぶ）
            Entry("saori-calcs", "MIT", "https://github.com/tukinami/saori-calcs", read("saori/saori-calcs/LICENSE").trim()),
            Entry("saori-japanese-gengou", "MIT", "https://github.com/tukinami/saori-japanese-gengou", read("saori/saori-japanese-gengou/LICENSE").trim()),
            Entry("saori-resized-png-mini", "MIT", "https://github.com/tukinami/saori-resized-png-mini", read("saori/saori-resized-png-mini/LICENSE").trim()),
            Entry("saori-resized-png", "MIT", "https://github.com/tukinami/saori-resized-png", read("saori/saori-resized-png/LICENSE").trim()),
            Entry("saori-pie-chart", "MIT", "https://github.com/tukinami/saori-pie-chart", read("saori/saori-pie-chart/LICENSE").trim()),
            Entry("saori-qrcode", "CC0-1.0", "https://github.com/nikolat/saori-qrcode", read("saori/saori-qrcode/LICENSE").trim()),
            // json.hpp の先頭には SPDX の印しか無いので、文面は MIT の LICENSE.MIT と同じものを書く
            Entry(
                "JSON for Modern C++", "MIT", "https://github.com/nlohmann/json",
                """
                Copyright (c) 2013-2025 Niels Lohmann

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
                """.trimIndent(),
            ),
            Entry("Lua", "MIT", "https://www.lua.org/", cut("lua/lua.h", "Lua.org, PUC-Rio.", "SOFTWARE.")),
            Entry("sol2", "MIT", "https://github.com/ThePhD/sol2", read("sol2/LICENSE.txt").trim()),
            Entry(
                "SQLite", "Public Domain", "https://www.sqlite.org/",
                cut("yaya/sqlite/sqlite3.c", "The author disclaims copyright", "never taking more than you give."),
            ),
            Entry(
                "Android Jetpack (AndroidX, Jetpack Compose)", "Apache-2.0", "https://developer.android.com/jetpack",
                "Copyright The Android Open Source Project\n\nLicensed under the Apache License, Version 2.0.",
            ),
            Entry(
                "Kotlin Standard Library", "Apache-2.0", "https://kotlinlang.org/",
                "Copyright JetBrains s.r.o. and Kotlin Programming Language contributors\n\nLicensed under the Apache License, Version 2.0.",
            ),
            Entry(
                "kotlinx.coroutines", "Apache-2.0", "https://github.com/Kotlin/kotlinx.coroutines",
                "Copyright JetBrains s.r.o. and contributors\n\nLicensed under the Apache License, Version 2.0.",
            ),
            Entry(
                "Zip4j", "Apache-2.0", "https://github.com/srikanth-lingala/zip4j",
                "Copyright 2010 Srikanth Reddy Lingala\n\nLicensed under the Apache License, Version 2.0.",
            ),
            // 文面は jar の中の org.bouncycastle.LICENSE（licenseText）と同じ
            Entry(
                "Bouncy Castle", "MIT", "https://www.bouncycastle.org/",
                """
                Copyright (c) 2000-2023 The Legion of the Bouncy Castle Inc. (https://www.bouncycastle.org)

                Permission is hereby granted, free of charge, to any person obtaining a copy of this software
                and associated documentation files (the "Software"), to deal in the Software without restriction,
                including without limitation the rights to use, copy, modify, merge, publish, distribute, sublicense,
                and/or sell copies of the Software, and to permit persons to whom the Software is furnished to do so,
                subject to the following conditions:

                The above copyright notice and this permission notice shall be included in all copies or substantial
                portions of the Software.

                THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR IMPLIED,
                INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY, FITNESS FOR A PARTICULAR
                PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE AUTHORS OR COPYRIGHT HOLDERS BE
                LIABLE FOR ANY CLAIM, DAMAGES OR OTHER LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR
                OTHERWISE, ARISING FROM, OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER
                DEALINGS IN THE SOFTWARE.
                """.trimIndent(),
            ),
            // 文面は google/brotli の LICENSE と同じ（jar には入っていない）
            Entry(
                "Brotli", "MIT", "https://github.com/google/brotli",
                """
                Copyright (c) 2009, 2010, 2013-2016 by the Brotli Authors.

                Permission is hereby granted, free of charge, to any person obtaining a copy
                of this software and associated documentation files (the "Software"), to deal
                in the Software without restriction, including without limitation the rights
                to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
                copies of the Software, and to permit persons to whom the Software is
                furnished to do so, subject to the following conditions:

                The above copyright notice and this permission notice shall be included in
                all copies or substantial portions of the Software.

                THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
                IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
                FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT.  IN NO EVENT SHALL THE
                AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
                LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
                OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
                THE SOFTWARE.
                """.trimIndent(),
            ),
            // 文面は xz-java の COPYING と同じ
            Entry(
                "XZ for Java", "0BSD", "https://tukaani.org/xz/java.html",
                """
                Copyright (C) The XZ for Java authors and contributors

                Permission to use, copy, modify, and/or distribute this
                software for any purpose with or without fee is hereby granted.

                THE SOFTWARE IS PROVIDED "AS IS" AND THE AUTHOR DISCLAIMS ALL
                WARRANTIES WITH REGARD TO THIS SOFTWARE INCLUDING ALL IMPLIED
                WARRANTIES OF MERCHANTABILITY AND FITNESS. IN NO EVENT SHALL
                THE AUTHOR BE LIABLE FOR ANY SPECIAL, DIRECT, INDIRECT, OR
                CONSEQUENTIAL DAMAGES OR ANY DAMAGES WHATSOEVER RESULTING FROM
                LOSS OF USE, DATA OR PROFITS, WHETHER IN AN ACTION OF CONTRACT,
                NEGLIGENCE OR OTHER TORTIOUS ACTION, ARISING OUT OF OR IN
                CONNECTION WITH THE USE OR PERFORMANCE OF THIS SOFTWARE.
                """.trimIndent(),
            ),
            // 文面は zstd-jni の LICENSE と同じ
            Entry(
                "zstd-jni", "BSD 2-Clause", "https://github.com/luben/zstd-jni",
                """
                Copyright (c) 2015-present, Luben Karavelov/ All rights reserved.

                BSD-2-Clause License https://opensource.org/license/bsd-2-clause

                Redistribution and use in source and binary forms, with or without modification,
                are permitted provided that the following conditions are met:

                * Redistributions of source code must retain the above copyright notice, this
                  list of conditions and the following disclaimer.

                * Redistributions in binary form must reproduce the above copyright notice, this
                  list of conditions and the following disclaimer in the documentation and/or
                  other materials provided with the distribution.

                THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
                ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
                WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
                DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
                ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
                (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
                LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
                ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
                (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
                SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
                """.trimIndent(),
            ),
            // zstd-jni の中のネイティブの部品。文面は zstd の LICENSE と同じ
            Entry(
                "Zstandard", "BSD 3-Clause", "https://github.com/facebook/zstd",
                """
                BSD License

                For Zstandard software

                Copyright (c) Meta Platforms, Inc. and affiliates. All rights reserved.

                Redistribution and use in source and binary forms, with or without modification,
                are permitted provided that the following conditions are met:

                 * Redistributions of source code must retain the above copyright notice, this
                   list of conditions and the following disclaimer.

                 * Redistributions in binary form must reproduce the above copyright notice,
                   this list of conditions and the following disclaimer in the documentation
                   and/or other materials provided with the distribution.

                 * Neither the name Facebook, nor Meta, nor the names of its contributors may
                   be used to endorse or promote products derived from this software without
                   specific prior written permission.

                THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
                ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
                WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
                DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
                ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
                (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
                LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
                ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
                (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
                SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
                """.trimIndent(),
            ),
            Entry(
                "LLVM libc++", "Apache-2.0 WITH LLVM-exception", "https://libcxx.llvm.org/",
                "Part of the LLVM Project, under the Apache License v2.0 with LLVM Exceptions.\n" +
                    "See https://llvm.org/LICENSE.txt for license information.",
            ),
            *rustEntries.toTypedArray(),
            Entry("Apache License 2.0", "Apache-2.0", "https://www.apache.org/licenses/LICENSE-2.0", apacheLicense.get().asFile.readText().trim()),
        )

        val dir = outputDir.get().asFile.resolve("licenses")
        dir.deleteRecursively()
        dir.mkdirs()
        fun json(s: String) = buildString {
            append('"')
            for (c in s) when {
                c == '"' -> append("\\\"")
                c == '\\' -> append("\\\\")
                c == '\n' -> append("\\n")
                c < ' ' -> append("\\u%04x".format(c.code))
                else -> append(c)
            }
            append('"')
        }
        dir.resolve("licenses.json").writeText(
            entries.joinToString(",\n", "[\n", "\n]\n") {
                """{"name": ${json(it.name)}, "license": ${json(it.license)}, "url": ${json(it.url)}, "text": ${json(it.text)}}"""
            },
            Charsets.UTF_8,
        )
    }
}

val generateLicenseAssets = tasks.register<GenerateLicenseAssets>("generateLicenseAssets") {
    cppDir.set(rootProject.layout.projectDirectory.dir("shiori-yaya/src/main/cpp"))
    // pono-droid 自体のライセンスファイル（Apache License 2.0 の全文）をそのまま使う
    apacheLicense.set(rootProject.layout.projectDirectory.file("LICENSE"))
    rustLicenses.set(rootProject.layout.projectDirectory.file("shiori-yaya/src/main/rust/licenses.txt"))
    outputDir.set(layout.buildDirectory.dir("generated/licenses"))
}

androidComponents {
    onVariants { variant ->
        variant.sources.assets?.addGeneratedSourceDirectory(generateLicenseAssets, GenerateLicenseAssets::outputDir)
    }
}

/**
 * リリース用の署名。鍵と合言葉はリポジトリに置かず、環境変数で渡す（GitHub Actions ではシークレットから）。
 * 4つ揃っていなければ release は署名なしでビルドされる（そのままでは端末に入れられない）。
 */
val releaseKeystore = System.getenv("PONO_KEYSTORE_FILE")?.takeIf { it.isNotBlank() }?.let(::file)
val releaseSigningReady = releaseKeystore?.isFile == true &&
    listOf("PONO_KEYSTORE_PASSWORD", "PONO_KEY_ALIAS", "PONO_KEY_PASSWORD").all { !System.getenv(it).isNullOrEmpty() }

// 一部だけ設定してあるのは打ち間違いのはずなので知らせる（値は出さず、名前だけ）
if (!releaseSigningReady) {
    val signingEnv = listOf("PONO_KEYSTORE_FILE", "PONO_KEYSTORE_PASSWORD", "PONO_KEY_ALIAS", "PONO_KEY_PASSWORD")
    val missing = signingEnv.filter { System.getenv(it).isNullOrEmpty() }
    if (missing.size < signingEnv.size) {
        val why = if (releaseKeystore != null && !releaseKeystore.isFile) "PONO_KEYSTORE_FILE points to no file" else "missing: ${missing.joinToString()}"
        logger.warn("Release signing is partly configured ($why); the release build will be unsigned.")
    }
}

android {
    namespace = "net.pono.droid"
    compileSdk = 37
    // shiori-yaya と同じ NDK。これが無いと、リリースで .so のデバッグ情報を落とせず（stripReleaseDebugSymbols）、APK がとても大きくなる
    ndkVersion = "29.0.14206865"

    defaultConfig {
        applicationId = "net.pono.droid"
        minSdk = 26
        targetSdk = 37
        // リリースのワークフローがタグから渡す（-PponoVersionName=0.2.0 -PponoVersionCode=12）
        versionCode = providers.gradleProperty("ponoVersionCode").orNull?.toInt() ?: 1
        versionName = providers.gradleProperty("ponoVersionName").orNull ?: "0.1.0"
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("PONO_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("PONO_KEY_ALIAS")
                keyPassword = System.getenv("PONO_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
        }
        // デバッグ用の鍵で署名する版は net.pono.droid.debug にして、リリースの鍵の版（net.pono.droid）と並べて入れられるようにする。
        // アプリの名前は src/debug・src/benchmark の strings.xml で見分けられるようにしている
        debug {
            applicationIdSuffix = ".debug"
        }
        // 動きの速さを確かめるための版。中身はリリースと同じ（デバッグできない）で、デバッグ版と同じ鍵・同じ
        // net.pono.droid.debug なので、端末に入っているデバッグ版の上にデータを残したまま入れられる
        create("benchmark") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(project(":baseware"))
    // baseware の zstd（internal_data.Decompress）の Android 用のネイティブの部品
    implementation(libs.zstd.jni) { artifact { type = "aar" } }
    implementation(project(":shiori-yaya"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.savedstate)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
