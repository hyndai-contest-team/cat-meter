package kr.catmeter.app.car

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.util.Log
import java.util.Locale

/**
 * 하차·정산을 짧게 읽어 주는 안내 음성 (Pleos TextToSpeech SDK, 온디바이스 모드).
 *
 * - 온디바이스 모드는 앱 등록(registerApp) 없이 쓴다. 서버 모드는 Pleos 콘솔의 사용 신청이 필요해 쓰지 않는다.
 * - 화면을 보지 않아도 결과를 알 수 있게 하는 용도라 한 문장으로 짧게 말한다. 대화형 응답이나 긴 내역은 읽지 않는다.
 * - 음성 엔진이 없거나 준비되지 않았으면 조용히 넘어간다. 화면 표시는 그대로이므로 기능은 달라지지 않는다.
 */
class Announcer(context: Context) {

    private val appContext = context.applicationContext
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    fun start() {
        val t = runCatching { TextToSpeech(appContext, Mode.ON_DEVICE) }
            .onFailure { Log.w(TAG, "tts unavailable: ${it.message}") }
            .getOrNull() ?: return
        tts = t
        runCatching {
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    runCatching { t.setLocale(Locale.KOREA) }
                    ready = true
                    Log.i(TAG, "tts ready")
                }

                override fun onFailure(error: ErrorType) {
                    Log.w(TAG, "tts init failed: $error")
                }
            })
        }.onFailure { Log.w(TAG, "tts init threw: ${it.message}") }
    }

    /** 한 문장을 읽는다. 앞선 안내가 끝나지 않았으면 끊고 새 안내를 읽는다. */
    fun say(text: String) {
        val t = tts
        if (t == null || !ready) {
            Log.i(TAG, "skip (tts not ready): $text")
            return
        }
        Log.i(TAG, "say: $text")
        runCatching { t.speak(text, true) }.onFailure { Log.w(TAG, "speak failed: ${it.message}") }
    }

    fun release() {
        runCatching { tts?.release() }
    }

    private companion object {
        const val TAG = "CatMeterVoice"
    }
}
