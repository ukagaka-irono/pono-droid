package net.pono.droid

import android.content.Context
import android.os.Build

/** インストールされている pono-droid のバージョン（build.gradle.kts の versionName / versionCode） */
data class AppVersion(val name: String, val code: Long) {
    companion object {
        fun of(context: Context): AppVersion {
            val info = context.packageManager.getPackageInfo(context.packageName, 0)
            val code = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else @Suppress("DEPRECATION") info.versionCode.toLong()
            return AppVersion(info.versionName ?: "?", code)
        }
    }
}
