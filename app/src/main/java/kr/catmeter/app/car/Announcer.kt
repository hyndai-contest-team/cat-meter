package kr.catmeter.app.car

import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.EventListener
import ai.pleos.playground.tts.listener.OnInitializeListener
import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import java.util.Locale

/**
 * 하차·정산을 짧게 읽어 주는 안내 음성 (Pleos TextToSpeech SDK, 온디바이스 모드).
 *
 * - 온디바이스 모드만 쓰면 앱 등록(registerApp)이 필요 없다 (Pleos 문서 TextToSpeech "App registration").
 *   서버·하이브리드 모드는 Pleos Playground 의 My Project 등록이 필요해 쓰지 않는다.
 * - 재생 전에 오디오 포커스를 요청한다 (Pleos "Audio Focus Development Guide": 재생 전에 AudioAttributes 와
 *   AudioFocusRequest 로 포커스를 요청). 잠깐 끼어드는 안내라 다른 소리를 끄지 않고 줄이기만 하는 요청을 쓴다.
 *   포커스를 얻지 못하면(통화 중 등) 읽지 않는다.
 * - 포커스를 얻은 뒤 [PRE_ROLL_MS] 만큼 쉬고 읽는다 (Pleos "Voice Guidance Pre-roll Silence Guide": 150~200ms 권장).
 * - 화면을 보지 않아도 결과를 알 수 있게 하는 용도라 한 문장으로 짧게 말한다.
 * - 음성 엔진이 없거나 준비되지 않았으면 조용히 넘어간다. 화면 표시는 그대로이므로 기능은 달라지지 않는다.
 */
class Announcer(context: Context) {

    private val appContext = context.applicationContext
    private val audio = appContext.getSystemService(AudioManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var tts: TextToSpeech? = null
    @Volatile private var ready = false

    private val attributes = AudioAttributes.Builder()
        .setUsage(AudioAttributes.USAGE_ASSISTANT)
        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
        .build()

    private val focusRequest = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
        .setAudioAttributes(attributes)
        .setOnAudioFocusChangeListener { change ->
            // 통화처럼 더 급한 소리가 포커스를 가져가면 안내를 멈춘다
            if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) {
                runCatching { tts?.stop() }
            }
        }
        .build()

    private val events = object : EventListener {
        override fun onReady() {}
        override fun onStart() = Log.i(TAG, "playback started").let { }
        override fun onDone() {
            Log.i(TAG, "playback done")
            releaseFocus()
        }
        override fun onStop() = releaseFocus()
        override fun onError(error: ErrorType) {
            Log.w(TAG, "playback error: $error")
            releaseFocus()
        }
        override fun onUpdatedRms(rms: Double) {}
    }

    fun start() {
        val t = runCatching { TextToSpeech(appContext, Mode.ON_DEVICE) }
            .onFailure { Log.w(TAG, "tts unavailable: ${it.message}") }
            .getOrNull() ?: return
        tts = t
        runCatching {
            t.initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    // 리스너와 설정은 초기화가 끝난 뒤에만 받는다
                    runCatching { t.addEventListener(events) }.onFailure { Log.w(TAG, "listener: ${it.message}") }
                    runCatching { t.setAudioAttributes(attributes) }.onFailure { Log.w(TAG, "attributes: ${it.message}") }
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
        val granted = runCatching { audio.requestAudioFocus(focusRequest) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED }.getOrDefault(false)
        if (!granted) {
            Log.i(TAG, "skip (no audio focus): $text")
            return
        }
        Log.i(TAG, "say: $text")
        main.postDelayed({
            runCatching { t.speak(text, true) }.onFailure {
                Log.w(TAG, "speak failed: ${it.message}")
                releaseFocus()
            }
        }, PRE_ROLL_MS)
    }

    private fun releaseFocus() {
        runCatching { audio.abandonAudioFocusRequest(focusRequest) }
    }

    fun release() {
        runCatching { tts?.release() }
        releaseFocus()
    }

    private companion object {
        const val TAG = "CatMeterVoice"
        /** 포커스를 얻은 뒤 읽기 시작하기까지 쉬는 시간 */
        const val PRE_ROLL_MS = 200L
    }
}
