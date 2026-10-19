package es.inled.sayri

import android.app.Application
import android.content.Context
import android.content.SharedPreferences

class SayriApp : Application() {

    companion object {
        lateinit var instance: SayriApp
            private set

        private const val PREFS_NAME = "sayri_prefs"
        const val KEY_HOST = "key_host"
        const val KEY_PORT = "key_port"
        const val KEY_API_KEY = "key_api_key"

        const val DEFAULT_HOST = "172.16.180.141"
        const val DEFAULT_PORT = 8765
    }

    val prefs: SharedPreferences by lazy {
        getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    }

    var serverHost: String
        get() = prefs.getString(KEY_HOST, DEFAULT_HOST) ?: DEFAULT_HOST
        set(value) = prefs.edit().putString(KEY_HOST, value.trim()).apply()

    var serverPort: Int
        get() = prefs.getInt(KEY_PORT, DEFAULT_PORT)
        set(value) = prefs.edit().putInt(KEY_PORT, value).apply()

    var apiKey: String
        get() = prefs.getString(KEY_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_API_KEY, value.trim()).apply()

    val baseUrl: String
        get() = "http://$serverHost:$serverPort"

    override fun onCreate() {
        super.onCreate()
        instance = this
    }
}
