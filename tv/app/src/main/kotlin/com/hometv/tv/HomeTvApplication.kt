package com.hometv.tv

import android.app.Application
import com.hometv.tv.di.AppContainer

/** Process entry point: creates the [AppContainer] (manual DI) once per process. */
class HomeTvApplication : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer.create(this)
    }
}
