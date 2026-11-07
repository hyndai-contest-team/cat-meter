package kr.catmeter.app

import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import kr.catmeter.app.car.AaosMonitor
import kr.catmeter.app.ui.CatMeterApp

class MainActivity : ComponentActivity() {

    private val session get() = (application as CatMeterApplication).session

    /** 차속(CAR_SPEED)은 dangerous 권한이라 실행 중에 물어본다. 거절하면 누적 거리만으로 기록한다. */
    private val speedPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { session.restartSpeed() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            CatMeterApp(
                session = session,
                onRequestSpeedPermission = { speedPermission.launch(AaosMonitor.SPEED_PERMISSION) },
            )
        }
        if (checkSelfPermission(AaosMonitor.SPEED_PERMISSION) != PackageManager.PERMISSION_GRANTED && savedInstanceState == null) {
            speedPermission.launch(AaosMonitor.SPEED_PERMISSION)
        }
    }
}
