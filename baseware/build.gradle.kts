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
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
