package tech.jpco.qen

import android.app.Application
import com.squareup.leakcanary.LeakCanary

class Qen : Application() {
    override fun onCreate() {
        super.onCreate()
        LeakCanary.install(this)
    }
}