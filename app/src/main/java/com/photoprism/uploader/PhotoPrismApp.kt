package com.photoprism.uploader

import android.app.Application
import com.photoprism.uploader.di.AppModule

/**
 * Application class providing the DI container.
 */
class PhotoPrismApp : Application() {

    lateinit var appModule: AppModule
        private set

    override fun onCreate() {
        super.onCreate()
        appModule = AppModule(this)
    }
}
