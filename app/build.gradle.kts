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
            Entry(
                "LLVM libc++", "Apache-2.0 WITH LLVM-exception", "https://libcxx.llvm.org/",
                "Part of the LLVM Project, under the Apache License v2.0 with LLVM Exceptions.\n" +
                    "See https://llvm.org/LICENSE.txt for license information.",
            ),
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

android {
    namespace = "net.pono.droid"
    compileSdk = 37

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
        // 動きの速さを確かめるための版。中身はリリースと同じ（デバッグできない）で、デバッグ用の鍵で署名するので、
        // 端末に入っているデバッグ版の上にデータを残したまま入れられる
        create("benchmark") {
            initWith(getByName("release"))
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
