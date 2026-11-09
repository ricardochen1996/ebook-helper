package com.booxai.helper

import android.app.Application
import com.booxai.helper.ai.ProviderRouter
import com.booxai.helper.data.AppDatabase
import com.booxai.helper.data.ChatRepository
import com.booxai.helper.data.SecretStore
import com.booxai.helper.data.SettingsStore
import com.booxai.helper.speech.TtsController

class App : Application() {

    override fun onCreate() {
        super.onCreate()
        instance = this
        db = AppDatabase.get(this)
        settings = SettingsStore(this)
        secrets = SecretStore(this)
        repository = ChatRepository(db, settings)
        router = ProviderRouter(settings, secrets)
        tts = TtsController(this)
        tts.init()
    }

    companion object {
        lateinit var instance: App
            private set

        lateinit var db: AppDatabase
            private set

        lateinit var settings: SettingsStore
            private set

        lateinit var secrets: SecretStore
            private set

        lateinit var repository: ChatRepository
            private set

        lateinit var router: ProviderRouter
            private set

        lateinit var tts: TtsController
            private set
    }
}
