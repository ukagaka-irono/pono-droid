plugins {
    alias(libs.plugins.kotlin.jvm)
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(libs.kotlinx.coroutines.core)
    // パスワード付きの ZIP（\![execute,extractarchive / compressarchive] の --password）
    implementation(libs.zip4j)
    // 内蔵 SAORI（internal_crypto・internal_jwt）のハッシュ・暗号・署名・鍵の導出
    implementation(libs.bouncycastle)
    // internal_data.Decompress の brotli / zstd と、internal_file.CompressArchive の lzma
    implementation(libs.brotli.dec)
    implementation(libs.xz)
    // zstd はネイティブの部品。ここではコンパイルとテストに jar を使い、アプリは Android 用の .aar を入れる
    compileOnly(libs.zstd.jni)
    testImplementation(libs.zstd.jni)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
