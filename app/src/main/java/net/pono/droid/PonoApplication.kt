package net.pono.droid

import android.app.Application
import android.content.Context
import android.content.res.Configuration
import net.pono.baseware.text.Messages

/** アプリの言語（端末の言語か、Android 13 以降のアプリごとの言語）を baseware の言語表にも伝える */
class PonoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        applyLanguage(this)
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        applyLanguage(this)
    }

    companion object {
        /** Android が values-xx から選んだ言語を、baseware の文言でも使う */
        fun applyLanguage(context: Context) {
            Messages.language = context.getString(R.string.language_code)
        }
    }
}
