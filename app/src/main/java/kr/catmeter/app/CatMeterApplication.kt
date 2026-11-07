package kr.catmeter.app

import android.app.Application

class CatMeterApplication : Application() {
    val session: CatMeterSession by lazy { CatMeterSession(this) }
}
