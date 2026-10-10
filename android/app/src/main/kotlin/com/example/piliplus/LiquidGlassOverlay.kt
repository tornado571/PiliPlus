package com.example.piliplus

import android.app.Activity
import android.graphics.Bitmap
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.Choreographer
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.lifecycle.setViewTreeViewModelStoreOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

// Kyant0 Backdrop（io.github.kyant0:backdrop，Maven Central 2.0.1）
// 注意：Maven 组是 io.github.kyant0，但代码包是 com.kyant.backdrop；
// rememberLayerBackdrop/layerBackdrop 在子包 com.kyant.backdrop.backdrops
import com.kyant.backdrop.*
import com.kyant.backdrop.backdrops.layerBackdrop
import com.kyant.backdrop.backdrops.rememberLayerBackdrop
import com.kyant.backdrop.effects.*

/** 诊断日志标签（文件级） */
private const val TAG = "PiliGlass"

/**
 * 原生液态玻璃底栏覆盖层（Kyant0 Backdrop 实现）
 *
 * 原理：Flutter 底栏仍由 Flutter 渲染（含图标/文字），本层在 DecorView
 * 上以同尺寸 ComposeView 叠加在其正上方：
 * 1. Choreographer 逐帧（仅在捕获空闲时）用 [PixelCopy] 从
 *    FlutterSurfaceView 截取底栏区域（含信息流内容+底栏 chrome）；
 * 2. Compose 内把位图作为 backdrop 源，用 Kyant0 Backdrop 的 AGSL
 *    效果链（vibrancy + blur + lens 折射/色散）绘制液态玻璃；
 * 3. 覆盖层不消费触摸事件，点击穿透回 FlutterView，交互零改动；
 * 4. Dart 侧在路由被覆盖（进入二级页）时调用 hide 隐藏本层。
 *
 * 门控：API 33+（RuntimeShader）；不支持或创建失败时 Dart 侧保持
 * GlassSurface（Flutter 实现）降级。
 */
object LiquidGlassOverlay {

    const val CHANNEL_NAME = "piliplus/liquid_glass"

    /** AGSL RuntimeShader 需要 Android 13 (API 33) */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private val mainHandler = Handler(Looper.getMainLooper())

    private var activity: Activity? = null
    private var overlay: ComposeView? = null
    private var owner: OverlayLifecycleOwner? = null
    private var surfaceView: SurfaceView? = null
    private var bitmap: Bitmap? = null
    private val frameBitmap = mutableStateOf<ImageBitmap?>(null)
    private var radiusPx: Float = 0f
    private var capturing = false
    private var choreographerCallback: Choreographer.FrameCallback? = null

    fun registerChannel(messenger: BinaryMessenger, hostActivity: Activity) {
        activity = hostActivity
        Log.d(TAG, "channel registered, isSupported=$isSupported")
        MethodChannel(messenger, CHANNEL_NAME).setMethodCallHandler { call, result ->
            when (call.method) {
                "isSupported" -> result.success(isSupported)

                "show" -> {
                    val left = (call.argument<Number>("left") ?: 0).toInt()
                    val top = (call.argument<Number>("top") ?: 0).toInt()
                    val width = (call.argument<Number>("width") ?: 0).toInt()
                    val height = (call.argument<Number>("height") ?: 0).toInt()
                    val radius = (call.argument<Number>("radius") ?: 0).toFloat()
                    val host = activity
                    if (host == null) {
                        result.success(false)
                        return@setMethodCallHandler
                    }
                    try {
                        show(host, left, top, width, height, radius)
                        result.success(true)
                    } catch (t: Throwable) {
                        // 任何原生异常都安全降级为 Flutter 玻璃
                        Log.e(TAG, "show failed", t)
                        result.success(false)
                    }
                }

                "hide" -> {
                    hide()
                    result.success(true)
                }

                else -> result.notImplemented()
            }
        }
    }

    fun show(activity: Activity, left: Int, top: Int, width: Int, height: Int, radius: Float) {
        if (!isSupported || width <= 0 || height <= 0) throw IllegalStateException("unsupported")

        // 已存在覆盖层：仅原地更新位置与尺寸（动画期间每帧调用）
        val existing = overlay
        if (existing != null && existing.parent is ViewGroup) {
            radiusPx = radius
            if (existing.width != width || existing.height != height) {
                existing.layoutParams = FrameLayout.LayoutParams(width, height)
                ensureBitmap(width, height)
            }
            existing.translationX = left.toFloat()
            existing.translationY = top.toFloat()
            return
        }
        hide()

        val decor = activity.window.decorView as? ViewGroup
            ?: throw IllegalStateException("no decor view")
        val surface = findSurfaceView(decor)
            ?: throw IllegalStateException("FlutterSurfaceView not found")

        this.activity = activity
        this.surfaceView = surface
        this.radiusPx = radius

        val lifecycleOwner = OverlayLifecycleOwner()
        owner = lifecycleOwner

        val view = ComposeView(activity).apply {
            setViewCompositionStrategy(
                ViewCompositionStrategy.DisposeOnDetachedFromWindow
            )
            setViewTreeLifecycleOwner(lifecycleOwner)
            setViewTreeViewModelStoreOwner(lifecycleOwner)
            setViewTreeSavedStateRegistryOwner(lifecycleOwner)
            setContent { LiquidGlassLayer() }
        }
        overlay = view

        val params = FrameLayout.LayoutParams(width, height)
        view.translationX = left.toFloat()
        view.translationY = top.toFloat()
        decor.addView(view, params)
        Log.d(TAG, "overlay attached: ${width}x$height at ($left,$top), radius=$radius")
        ensureBitmap(width, height)
        startCaptureLoop()
    }

    fun hide() {
        stopCaptureLoop()
        overlay?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        overlay = null
        owner?.destroy()
        owner = null
        surfaceView = null
        bitmap = null
        frameBitmap.value = null
        // 注意：不置空 activity —— channel 仅注册一次，
        // 置空会导致后续 show 永远失败
    }

    private fun ensureBitmap(width: Int, height: Int) {
        val current = bitmap
        if (current == null || current.width != width || current.height != height) {
            bitmap?.recycle()
            bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        }
    }

    private fun startCaptureLoop() {
        stopCaptureLoop()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val overlayView = overlay ?: return
                captureOnce(
                    surfaceView ?: return,
                    left = overlayView.translationX.toInt(),
                    top = overlayView.translationY.toInt(),
                    width = overlayView.width,
                    height = overlayView.height,
                )
                Choreographer.getInstance().postFrameCallback(this)
            }
        }
        choreographerCallback = callback
        Choreographer.getInstance().postFrameCallback(callback)
    }

    private fun stopCaptureLoop() {
        choreographerCallback?.let { Choreographer.getInstance().removeFrameCallback(it) }
        choreographerCallback = null
    }

    private fun captureOnce(
        surface: SurfaceView,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
    ) {
        val target = bitmap ?: return
        if (width <= 0 || height <= 0 || capturing) return
        capturing = true
        val rect = android.graphics.Rect(left, top, left + width, top + height)
        try {
            PixelCopy.request(
                surface,
                rect,
                target,
                { copyResult ->
                    capturing = false
                    if (copyResult == PixelCopy.SUCCESS) {
                        frameBitmap.value = target.asImageBitmap()
                    }
                },
                mainHandler,
            )
        } catch (t: Throwable) {
            capturing = false
            Log.w(TAG, "pixelcopy failed", t)
        }
    }

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findSurfaceView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }

    @Composable
    private fun LiquidGlassLayer() {
        val bmp by frameBitmap
        val backdrop = rememberLayerBackdrop { }
        Box(modifier = Modifier.fillMaxSize()) {
            val current = bmp ?: return@Box
            // 背景源：截取的 Flutter 画面
            Image(
                bitmap = current,
                contentDescription = null,
                modifier = Modifier
                    .fillMaxSize()
                    .layerBackdrop(backdrop),
            )
            // 液态玻璃：vibrancy 提饱和 + 轻模糊 + lens 折射/色散
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBackdrop(
                        backdrop = backdrop,
                        shape = { RoundedCornerShape(radiusPx) },
                        effects = {
                            vibrancy()
                            blur(2f.dp.toPx())
                            lens(10f.dp.toPx(), 32f.dp.toPx())
                        },
                        onDrawSurface = {
                            drawRect(Color.White.copy(alpha = 0.06f))
                        },
                    ),
            )
        }
    }
}

/**
 * 给 ComposeView 提供 Lifecycle / ViewModelStore / SavedStateRegistry 宿主
 * （FlutterActivity 不是 ComponentActivity，需手动挂 owner）。
 */
private class OverlayLifecycleOwner : SavedStateRegistryOwner, ViewModelStoreOwner {

    private val controller = SavedStateRegistryController.create(this)
    private val store = ViewModelStore()

    override val lifecycle: LifecycleRegistry = LifecycleRegistry(this)

    override val savedStateRegistry: SavedStateRegistry
        get() = controller.savedStateRegistry

    override val viewModelStore: ViewModelStore
        get() = store

    init {
        controller.performRestore(null)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_START)
        lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
    }

    fun destroy() {
        runCatching {
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
            lifecycle.handleLifecycleEvent(Lifecycle.Event.ON_DESTROY)
            store.clear()
        }
    }
}
