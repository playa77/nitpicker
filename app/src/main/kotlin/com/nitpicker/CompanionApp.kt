package com.nitpicker

import android.app.Application
import com.nitpicker.di.AppContainer

class CompanionApp : Application() {
    lateinit var container: AppContainer
        private set
    override fun onCreate() { super.onCreate(); container = AppContainer(this) }
}
