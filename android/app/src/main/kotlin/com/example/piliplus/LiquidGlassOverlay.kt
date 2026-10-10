package com.example.piliplus

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Choreographer
import android.view.PixelCopy
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * 原生液态玻璃底栏覆盖层（零第三方依赖）
 *
 * 原理：Flutter 底栏仍由 Flutter 渲染（含图标/文字），本层在 DecorView
 * 上以同尺寸普通 View 叠加在其正上方：
 * 1. Choreographer ~30fps 用 [PixelCopy] 从 FlutterSurfaceView 截取底栏区域
 *    （该位图包含信息流内容+半透明底栏 chrome）；
 * 2. [GLASS_AGSL] 自定义 AGSL 着色器（Android 13+ RuntimeShader）对该位图
 *    做 SDF 圆角边缘折射 + RGB 色散 + 方向性边缘高光 —— 与
 *    Kyant0/AndroidLiquidGlass 同等技术路线（其 Maven 工件版本与文档包名
 *    不符，故按同思路自实现，避免依赖不确定性）；
 * 3. 覆盖层不消费触摸事件，点击穿透回 FlutterView，交互逻辑零改动。
 *
 * 门控：API 33+（RuntimeShader）；不支持或着色器编译失败时 Dart 侧保持
 * GlassSurface（Flutter 实现）降级，本层完全不创建/不绘制。
 */
object LiquidGlassOverlay {

    const val CHANNEL_NAME = "piliplus/liquid_glass"

    /** AGSL RuntimeShader 需要 Android 13 (API 33) */
    val isSupported: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    private const val frameIntervalMs = 33L // ~30fps

    private val mainHandler = Handler(Looper.getMainLooper())

    private var activity: Activity? = null
    private var overlay: GlassShaderView? = null
    private var surfaceView: SurfaceView? = null
    private var choreographerCallback: Choreographer.FrameCallback? = null

    fun registerChannel(messenger: BinaryMessenger, hostActivity: Activity) {
        activity = hostActivity
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
                    } catch (_: Throwable) {
                        // 任何原生异常都安全降级为 Flutter 玻璃
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
            existing.radiusPx = radius
            if (existing.width != width || existing.height != height) {
                existing.layoutParams = FrameLayout.LayoutParams(width, height)
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
        surfaceView = surface

        val view = GlassShaderView(activity).apply {
            radiusPx = radius
        }
        overlay = view

        val params = FrameLayout.LayoutParams(width, height)
        view.translationX = left.toFloat()
        view.translationY = top.toFloat()
        decor.addView(view, params)
        startCaptureLoop()
    }

    fun hide() {
        stopCaptureLoop()
        overlay?.let { view ->
            (view.parent as? ViewGroup)?.removeView(view)
        }
        overlay = null
        surfaceView = null
        // 注意：不置空 activity —— channel 仅注册一次，
        // 置空会导致后续 show 永远失败
    }

    private fun startCaptureLoop() {
        stopCaptureLoop()
        val callback = object : Choreographer.FrameCallback {
            override fun doFrame(frameTimeNanos: Long) {
                val overlayView = overlay ?: return
                overlayView.requestCapture(
                    surfaceView ?: return,
                    left = overlayView.translationX.toInt(),
                    top = overlayView.translationY.toInt(),
                    width = overlayView.width,
                    height = overlayView.height,
                    handler = mainHandler,
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

    private fun findSurfaceView(view: View): SurfaceView? {
        if (view is SurfaceView) return view
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                findSurfaceView(view.getChildAt(i))?.let { return it }
            }
        }
        return null
    }
}

/**
 * 液态玻璃着色器承载 View：绘制 AGSL 折射/色散效果。
 * 不消费触摸事件（无 click/touch 监听），事件穿透至下层 FlutterView。
 */
private class GlassShaderView(context: Context) : View(context) {

    var radiusPx: Float = 0f

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var shader: RuntimeShader? = null
    private var contentShader: BitmapShader? = null
    private var frame: Bitmap? = null
    private var capturing = false
    private var lastCaptureAt = 0L

    fun requestCapture(
        surface: SurfaceView,
        left: Int,
        top: Int,
        width: Int,
        height: Int,
        handler: Handler,
    ) {
        if (width <= 0 || height <= 0 || capturing) return
        val now = SystemClock.uptimeMillis()
        if (now - lastCaptureAt < 33L) return
        lastCaptureAt = now
        val target = obtainBitmap(width, height)
        capturing = true
        val rect = Rect(left, top, left + width, top + height)
        try {
            PixelCopy.request(
                surface,
                rect,
                target,
                { copyResult ->
                    capturing = false
                    if (copyResult == PixelCopy.SUCCESS) {
                        frame = target
                        contentShader = BitmapShader(
                            target,
                            Shader.TileMode.CLAMP,
                            Shader.TileMode.CLAMP,
                        )
                        invalidate()
                    }
                },
                handler,
            )
        } catch (_: Throwable) {
            capturing = false
        }
    }

    private fun obtainBitmap(width: Int, height: Int): Bitmap {
        val current = frame
        if (current != null && current.width == width && current.height == height &&
            !current.isRecycled
        ) {
            return current
        }
        if (current != null) current.recycle()
        return Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    }

    override fun onDraw(canvas: Canvas) {
        val content = contentShader ?: return
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return
        val glass = ensureShader(
            w,
            h,
            radiusPx.coerceAtMost(minOf(w, h) / 2f),
        ) ?: return
        glass.setInputShader("content", content)
        paint.shader = glass
        try {
            canvas.drawRect(0f, 0f, w, h, paint)
        } catch (_: Throwable) {
            // 运行期着色器异常（部分机型 AGSL 兼容性）：不绘制，保持透明
        } finally {
            paint.shader = null
        }
    }

    private var shaderParams: Triple<Float, Float, Float>? = null
    private var shaderFailed = false

    /** 宽高/圆角以常量内插进 AGSL 源码（避免依赖 uniform setter API），
     *  尺寸/圆角变化时重建着色器（开销极小且极少发生） */
    private fun ensureShader(w: Float, h: Float, r: Float): RuntimeShader? {
        if (shaderFailed) return null
        val params = Triple(w, h, r)
        val current = shader
        if (current != null && shaderParams == params) return current
        return try {
            RuntimeShader(glassAgl(w, h, r)).also {
                shader = it
                shaderParams = params
            }
        } catch (_: Throwable) {
            // AGSL 编译失败（语法/驱动不支持）：降级为不绘制
            shaderFailed = true
            null
        }
    }

    private companion object {
        /**
         * 液态玻璃 AGSL：
         * - SDF 圆角矩形边缘 → 边缘区沿法线方向折射采样（位移弯曲）
         * - R/G/B 三通道错位采样 → 色散
         * - 边缘 rim × 方向光 → 左上受光高光
         * 尺寸参数以 const 内插（见 ensureShader）
         */
        private fun glassAgl(w: Float, h: Float, r: Float) = """
            uniform shader content;
            const float width = $w;
            const float height = $h;
            const float radius = $r;
            const float edge = 12.0;
            const float dispersion = 0.12;

            float sdRoundedBox(float2 p, float2 b, float rr) {
                float2 q = abs(p) - b + rr;
                return min(max(q.x, q.y), 0.0) + length(max(q, 0.0)) - rr;
            }

            half4 main(float2 fragCoord) {
                float2 halfSize = float2(width, height) * 0.5;
                float2 p = fragCoord - halfSize;
                float d = sdRoundedBox(p, halfSize - 1.0, min(radius, min(halfSize.x, halfSize.y) - 1.0));
                if (d > 1.0) {
                    return half4(0.0);
                }
                float alpha = clamp(0.5 - d, 0.0, 1.0);

                // 边缘法线（近似）：到内缩圆角矩形的最近点方向
                float2 inner = halfSize - 1.0 - radius;
                float2 clamped = clamp(p, -inner, inner);
                float2 dir = p - clamped;
                float len = length(dir);
                float2 n = len > 0.0001 ? dir / len : float2(0.0, -1.0);

                // 折射弯曲：越靠边缘越强
                float t = 1.0 - clamp(-d / edge, 0.0, 1.0);
                float bend = t * t * edge * 1.35;

                float2 base = fragCoord - n * bend;
                float2 disp = n * bend * dispersion;
                half4 c;
                c.r = content.eval(base + disp).r;
                c.g = content.eval(base).g;
                c.b = content.eval(base - disp).b;
                c.a = 1.0;

                // 边缘高光：rim 强度 × 左上方向光
                float rim = smoothstep(-edge, 0.0, d) * (1.0 - smoothstep(0.0, 1.0, d));
                float lit = clamp(dot(n, normalize(float2(-0.6, -0.8))), 0.0, 1.0);
                c.rgb += rim * (0.30 + 0.70 * lit) * 0.32;

                // 表面轻微提亮（玻璃通透感）
                c.rgb += 0.03;

                return half4(c.rgb * alpha, alpha);
            }
        """.trimIndent()
    }
}
