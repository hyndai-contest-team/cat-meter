package kr.catmeter.app.ui.cat3d

import android.content.Context
import android.util.Log
import android.view.Choreographer
import android.view.Surface
import android.view.TextureView
import com.google.android.filament.Camera
import com.google.android.filament.ColorGrading
import com.google.android.filament.Engine
import com.google.android.filament.EntityManager
import com.google.android.filament.IndirectLight
import com.google.android.filament.LightManager
import com.google.android.filament.Renderer
import com.google.android.filament.SwapChain
import com.google.android.filament.ToneMapper
import com.google.android.filament.View
import com.google.android.filament.Viewport
import com.google.android.filament.android.DisplayHelper
import com.google.android.filament.android.UiHelper
import com.google.android.filament.gltfio.AssetLoader
import com.google.android.filament.gltfio.FilamentAsset
import com.google.android.filament.gltfio.Gltfio
import com.google.android.filament.gltfio.ResourceLoader
import com.google.android.filament.gltfio.UbershaderProvider
import kr.catmeter.app.trip.Accessory
import kr.catmeter.app.trip.CatProfile
import kr.catmeter.app.trip.Fur
import java.nio.ByteBuffer
import java.nio.ByteOrder

private const val TAG = "Cat3D"

/** 화면마다 쓰는 자세 */
enum class CatPose {
    /** 출발 전: 대기 + 꼬리 + 깜빡임, 처음 나타날 때와 누를 때 인사 */
    GREET,

    /** 도착 후: 영수증을 내밀고 그 자세를 유지 */
    RECEIPT,
}

/**
 * 앱 전체에서 하나만 쓰는 Filament 엔진과 glTF 로더. 모두 메인 스레드에서만 쓴다.
 * 고양이 모델은 털 색마다 하나: app/src/main/assets/models/cat_<털>.glb
 * (원본: tools/make_cat_glb.py, 명세: docs/spec/cat-model-spec.md). 털 색·무늬는 모델에 칠해져 있다.
 */
internal object CatEngine {
    val engine: Engine by lazy {
        Gltfio.init() // filament-jni + gltfio-jni
        Engine.create().also { Log.i(TAG, "Filament backend=${it.backend} featureLevel=${it.activeFeatureLevel}") }
    }
    val assetLoader: AssetLoader by lazy { AssetLoader(engine, UbershaderProvider(engine), EntityManager.get()) }
    val resourceLoader: ResourceLoader by lazy { ResourceLoader(engine) }

    private val glb = mutableMapOf<Fur, ByteBuffer>()

    fun model(context: Context, fur: Fur): ByteBuffer {
        val cached = glb.getOrPut(fur) {
            val bytes = context.assets.open("models/${fileName(fur)}").use { it.readBytes() }
            ByteBuffer.allocateDirect(bytes.size).order(ByteOrder.nativeOrder()).put(bytes).also { it.flip() }
        }
        return cached.duplicate()
    }

    fun fileName(fur: Fur) = when (fur) {
        Fur.CHEESE -> "cat_cheese.glb"
        Fur.MACKEREL -> "cat_mackerel.glb"
        Fur.BLACK -> "cat_black.glb"
        Fur.WHITE -> "cat_white.glb"
    }
}

/**
 * TextureView 하나에 고양이 한 마리를 그린다.
 * - 배경은 투명이라 뒤의 Compose 배경(다크 UI·영수증 종이)이 그대로 보인다.
 * - 털 색을 바꾸면 그 털의 모델로 갈아 끼운다.
 * - [animated] 가 false 이면 자세를 한 번 그리고 렌더링을 멈춘다 (이동 화면의 정차 중 그림).
 * - 화면에서 사라지거나 앱이 멈추면 [pause]/[destroy] 로 렌더링을 완전히 멈춘다.
 */
class CatRenderer(private val context: Context, private val textureView: TextureView) : Choreographer.FrameCallback {
    private val engine = CatEngine.engine
    private val entities = EntityManager.get()
    private val choreographer = Choreographer.getInstance()
    private val uiHelper = UiHelper(UiHelper.ContextErrorPolicy.DONT_CHECK)
    private val displayHelper = DisplayHelper(context)

    private val renderer: Renderer = engine.createRenderer()
    private val scene = engine.createScene()
    private val view: View = engine.createView()
    private val cameraEntity = entities.create()
    private val camera: Camera = engine.createCamera(cameraEntity)
    private val sun = entities.create()
    private val rim = entities.create()
    private val indirectLight: IndirectLight
    private val colorGrading: ColorGrading
    private var swapChain: SwapChain? = null

    private var asset: FilamentAsset? = null
    private var anim: Map<String, Int> = emptyMap()

    private var running = false
    private var startNanos = 0L
    private var waveStartNanos = -1L
    private var nextBlinkNanos = 0L
    private var blinkStartNanos = -1L
    private var framesToDraw = 0

    var animated: Boolean = true
        set(value) {
            if (field == value) return
            field = value
            requestFrames()
        }

    var pose: CatPose = CatPose.GREET
        set(value) {
            if (field == value) return
            field = value
            applyVisibility()
            startNanos = 0L
            requestFrames()
        }

    private var cat: CatProfile? = null

    init {
        view.scene = scene
        view.camera = camera
        view.blendMode = View.BlendMode.TRANSLUCENT
        view.multiSampleAntiAliasingOptions = View.MultiSampleAntiAliasingOptions().apply { enabled = true; sampleCount = 4 }
        view.ambientOcclusionOptions = View.AmbientOcclusionOptions().apply { enabled = true; radius = 0.25f; intensity = 0.8f }
        colorGrading = ColorGrading.Builder().toneMapper(ToneMapper.PBRNeutral()).build(engine)
        view.colorGrading = colorGrading
        renderer.clearOptions = Renderer.ClearOptions().apply { clear = true; setClearColor(0.0, 0.0, 0.0, 0.0) }

        // 왼쪽 위 앞에서 비추는 부드러운 주광 + 뒤에서 윤곽을 살리는 차가운 림 라이트 (다크 UI 에서 까망이 묻히지 않게)
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(1.0f, 0.97f, 0.92f)
            .intensity(70_000f)
            .direction(0.45f, -0.7f, -0.55f)
            .castShadows(true)
            .build(engine, sun)
        LightManager.Builder(LightManager.Type.DIRECTIONAL)
            .color(0.8f, 0.87f, 1.0f)
            .intensity(45_000f)
            .direction(-0.3f, -0.35f, 0.9f)
            .build(engine, rim)
        scene.addEntity(sun)
        scene.addEntity(rim)
        // 텍스처 없이 구면 조화 계수로 만든 스튜디오 환경광: 위가 밝고 앞이 조금 밝다
        indirectLight = IndirectLight.Builder()
            .irradiance(3, floatArrayOf(
                0.78f, 0.77f, 0.80f, // L00 (상수)
                0.18f, 0.18f, 0.20f, // L1-1 (y)
                0.08f, 0.08f, 0.08f, // L10  (z)
                0f, 0f, 0f,          // L11  (x)
                0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f,
            ))
            .intensity(36_000f)
            .build(engine)
        scene.indirectLight = indirectLight

        uiHelper.isOpaque = false
        textureView.isOpaque = false
        uiHelper.renderCallback = object : UiHelper.RendererCallback {
            override fun onNativeWindowChanged(surface: Surface) {
                swapChain?.let { engine.destroySwapChain(it) }
                swapChain = engine.createSwapChain(surface, uiHelper.swapChainFlags)
                textureView.display?.let { displayHelper.attach(renderer, it) }
                requestFrames()
            }

            override fun onDetachedFromSurface() {
                displayHelper.detach()
                swapChain?.let {
                    engine.destroySwapChain(it)
                    engine.flushAndWait()
                    swapChain = null
                }
            }

            override fun onResized(width: Int, height: Int) {
                view.viewport = Viewport(0, 0, width, height)
                frameCamera(width, height)
                requestFrames()
            }
        }
        uiHelper.attachTo(textureView)
    }

    /** 털 색(→ 모델)·소품 반영 */
    fun setCat(profile: CatProfile) {
        if (profile == cat) return
        val furChanged = profile.fur != cat?.fur
        cat = profile
        if (furChanged) loadModel(profile.fur)
        applyVisibility()
        requestFrames()
    }

    private fun loadModel(fur: Fur) {
        asset?.let {
            scene.removeEntities(it.entities)
            CatEngine.assetLoader.destroyAsset(it)
        }
        val a = CatEngine.assetLoader.createAsset(CatEngine.model(context, fur))
            ?: error("${CatEngine.fileName(fur)} 를 읽지 못함")
        CatEngine.resourceLoader.loadResources(a)
        a.releaseSourceData()
        val rm = engine.renderableManager
        for (e in a.renderableEntities) {
            val ri = rm.getInstance(e)
            rm.setCastShadows(ri, true)
            rm.setReceiveShadows(ri, true)
        }
        scene.addEntities(a.entities)
        val animator = a.instance.animator
        anim = (0 until animator.animationCount).associateBy { animator.getAnimationName(it) }
        asset = a
        val missing = REQUIRED.filterNot { it in anim || a.getFirstEntityByName(it) != 0 }
        Log.i(TAG, "${CatEngine.fileName(fur)} 불러옴: 애니메이션 ${anim.keys}" +
            if (missing.isEmpty()) "" else " / 모델에 없는 이름: $missing (명세와 모델 확인)")
    }

    private fun applyVisibility() {
        setVisible("Receipt", pose == CatPose.RECEIPT)
        val chosen = cat?.accessories.orEmpty()
        Accessory.entries.forEach { setVisible(nodeOf(it), it in chosen) }
    }

    /** 앞발 들어 인사 (GREET 자세에서만) */
    fun wave() {
        if (pose != CatPose.GREET || !animated) return
        waveStartNanos = 0L // 다음 프레임 시각으로 채움
        requestFrames()
    }

    fun resume() {
        if (running) return
        running = true
        choreographer.postFrameCallback(this)
    }

    fun pause() {
        running = false
        choreographer.removeFrameCallback(this)
    }

    fun destroy() {
        pause()
        uiHelper.detach()
        asset?.let {
            scene.removeEntities(it.entities)
            CatEngine.assetLoader.destroyAsset(it)
        }
        asset = null
        engine.destroyRenderer(renderer)
        engine.destroyView(view)
        engine.destroyScene(scene)
        engine.destroyCameraComponent(cameraEntity)
        engine.destroyIndirectLight(indirectLight)
        engine.destroyColorGrading(colorGrading)
        engine.destroyEntity(sun)
        engine.destroyEntity(rim)
        entities.destroy(sun)
        entities.destroy(rim)
        entities.destroy(cameraEntity)
    }

    private fun requestFrames() {
        framesToDraw = 3
        if (running) {
            choreographer.removeFrameCallback(this)
            choreographer.postFrameCallback(this)
        }
    }

    override fun doFrame(frameTimeNanos: Long) {
        if (!running) return
        if (startNanos == 0L) {
            startNanos = frameTimeNanos
            nextBlinkNanos = frameTimeNanos + 2_500_000_000L
            if (pose == CatPose.GREET && animated) waveStartNanos = frameTimeNanos + 600_000_000L
        }
        if (waveStartNanos == 0L) waveStartNanos = frameTimeNanos

        asset?.let { applyPose(it, frameTimeNanos) }

        val sc = swapChain
        if (sc != null && asset != null && uiHelper.isReadyToRender && renderer.beginFrame(sc, frameTimeNanos)) {
            renderer.render(view)
            renderer.endFrame()
            if (framesToDraw > 0) framesToDraw--
        }
        // 움직이는 동안만 다음 프레임을 요청한다. 정지 그림은 몇 장 그리고 멈춘다.
        if (animated || framesToDraw > 0) choreographer.postFrameCallback(this)
    }

    private fun applyPose(asset: FilamentAsset, now: Long) {
        val animator = asset.instance.animator
        fun duration(name: String) = anim[name]?.let(animator::getAnimationDuration) ?: 0f
        fun play(name: String, seconds: Float, loop: Boolean) {
            val i = anim[name] ?: return
            val d = animator.getAnimationDuration(i)
            animator.applyAnimation(i, if (loop) seconds % d else seconds.coerceIn(0f, d))
        }
        val t = if (animated) (now - startNanos) / 1e9f else 0f

        play("Idle", t, loop = true)
        if (animated) {
            play("TailWag", t, loop = true)
            if (now >= nextBlinkNanos) {
                blinkStartNanos = now
                nextBlinkNanos = now + (3_000_000_000L..5_000_000_000L).random()
            }
            if (blinkStartNanos > 0) play("Blink", (now - blinkStartNanos) / 1e9f, loop = false)
        }
        // 한 번만 하는 동작은 Idle 다음에 적용해 머리 움직임을 덮어쓴다
        when (pose) {
            CatPose.RECEIPT -> play("ShowReceipt", if (animated) t else Float.MAX_VALUE, loop = false)
            CatPose.GREET -> if (animated && waveStartNanos > 0 && now >= waveStartNanos) {
                val w = (now - waveStartNanos) / 1e9f
                play("Wave", w, loop = false)
                if (w > duration("Wave")) waveStartNanos = -1L
            }
        }
        animator.updateBoneMatrices()
    }

    /** 귀·꼬리까지 다 들어오게. 세로로 긴 칸이면 가로 폭 기준으로 맞춘다. */
    private fun frameCamera(width: Int, height: Int) {
        val aspect = width.toDouble() / height.coerceAtLeast(1)
        if (aspect < 0.75) {
            camera.setProjection(26.0, aspect, 0.1, 30.0, Camera.Fov.HORIZONTAL)
        } else {
            camera.setProjection(30.0, aspect, 0.1, 30.0, Camera.Fov.VERTICAL)
        }
        camera.lookAt(0.75, 1.3, 5.0, 0.0, 1.0, 0.0, 0.0, 1.0, 0.0)
    }

    private fun setVisible(name: String, visible: Boolean) {
        val a = asset ?: return
        val rm = engine.renderableManager
        // 소품 노드 아래 메시까지 함께 (이름이 붙은 노드 자체가 메시인 경우가 대부분)
        for (e in a.getEntitiesByName(name)) {
            if (rm.hasComponent(e)) rm.setLayerMask(rm.getInstance(e), 0xff, if (visible) 0x01 else 0x00)
        }
    }

    private companion object {
        val REQUIRED = listOf(
            "Idle", "TailWag", "Blink", "Wave", "ShowReceipt",
            "Head", "Body", "LeftEye", "RightEye", "Tail", "TailTip", "LeftFrontPaw", "RightFrontPaw",
            "Receipt", "Hat", "Sunglasses", "Ribbon", "Scarf",
        )

        fun nodeOf(a: Accessory) = when (a) {
            Accessory.HAT -> "Hat"
            Accessory.SUNGLASSES -> "Sunglasses"
            Accessory.RIBBON -> "Ribbon"
            Accessory.SCARF -> "Scarf"
        }
    }
}
