package kr.catmeter.app.ui.cat3d

import android.util.Log
import android.view.TextureView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kr.catmeter.app.trip.CatProfile
import kr.catmeter.app.ui.CatCanvas

/**
 * 오늘의 고양이 (3D, Filament).
 * - [pose]: 출발 전 인사(GREET) / 도착 후 영수증 내밀기(RECEIPT)
 * - [animated]: false 면 한 장만 그리고 멈춘다. 이동 화면(정차 중)에 쓴다. 주행 중에는 이 컴포저블 자체를 빼서
 *   렌더링을 완전히 멈춘다 (DriveScreen).
 * - 누르면 인사한다 (GREET, animated 일 때).
 * - 3D 초기화에 실패하면 로그를 남기고 2D 그림(CatCanvas)을 보여 준다.
 */
@Composable
fun Cat3D(
    cat: CatProfile,
    modifier: Modifier = Modifier,
    pose: CatPose = CatPose.GREET,
    animated: Boolean = true,
) {
    var renderer by remember { mutableStateOf<CatRenderer?>(null) }
    var failed by remember { mutableStateOf(false) }

    if (failed) {
        CatCanvas(cat, modifier)
        return
    }

    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle, renderer) {
        val r = renderer
        val observer = LifecycleEventObserver { _, e ->
            when (e) {
                Lifecycle.Event.ON_START -> r?.resume()
                Lifecycle.Event.ON_STOP -> r?.pause()
                else -> Unit
            }
        }
        lifecycle.addObserver(observer) // 이미 STARTED 면 ON_START 가 바로 온다
        onDispose { lifecycle.removeObserver(observer) }
    }

    AndroidView(
        modifier = modifier,
        factory = { ctx ->
            TextureView(ctx).also { tv ->
                runCatching { CatRenderer(ctx, tv) }
                    .onSuccess { r ->
                        renderer = r
                        tv.setOnClickListener { r.wave() }
                    }
                    .onFailure {
                        Log.e("Cat3D", "3D 고양이 초기화 실패, 2D 그림으로 대체", it)
                        failed = true
                    }
            }
        },
        update = {
            renderer?.let { r ->
                r.setCat(cat)
                r.pose = pose
                r.animated = animated
            }
        },
        onRelease = {
            renderer?.destroy()
            renderer = null
        },
    )
}
