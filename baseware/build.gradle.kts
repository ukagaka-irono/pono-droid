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
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}
