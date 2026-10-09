package com.example.piliplus

import android.app.Activity
import android.view.View
import io.flutter.plugin.common.BinaryMessenger
import io.flutter.plugin.common.MethodChannel

/**
 * OPPO View Seamless SDK（ColorOS 16.1+ 卡片 View 与全屏 Activity 间的无缝转场）封装。
 *
 * ## 实现方式：纯反射，不添加 Gradle 依赖
 *
 * 官方文档给出的依赖声明为
 * `compileOnly "com.oplus.animation:viewseamless:1.0.0@aar"`，但该坐标在
 * Maven Central 的可解析性**未经验证**（项目仓库仅配置 google()/mavenCentral()），
 * 贸然添加会导致所有 CI 构建失败。由于 `compileOnly` 语义即「编译期可见、
 * 运行时由系统（ROM）提供」，运行期反射调用与 compileOnly 行为一致，
 * 因此采用反射实现，对现有构建零风险。
 *
 * ## 类名说明（待真机验证）
 *
 * [SEAMLESS_CLASS_NAME] 由官方依赖坐标 `com.oplus.animation:viewseamless`
 * 与接口类名 `OplusViewSeamless` 推导。若真机上类名不符，只需修改此常量。
 *
 * ## 支持条件（官方文档）
 *
 * - ColorOS 16.1 及以上
 * - 动效等级 B+ 及以上（系统侧控制，应用层无公开查询接口，由调用结果间接体现）
 * - 浮窗 / 兼容模式 / 平行视窗 / 分屏场景不支持
 *
 * 所有反射调用均捕获 Throwable（含文档要求处理的 RuntimeException、
 * NoSuchMethodError 及 LinkageError 等），失败即返回不支持/false，安全降级。
 */
object OplusViewSeamlessHelper {

    /** 待验证：由官方依赖坐标推导的完整类名 */
    private const val SEAMLESS_CLASS_NAME = "com.oplus.animation.viewseamless.OplusViewSeamless"

    const val CHANNEL_NAME = "piliplus/oplus_view_seamless"

    private val seamlessClass: Class<*>? by lazy {
        try {
            Class.forName(SEAMLESS_CLASS_NAME)
        } catch (_: Throwable) {
            // 非 ColorOS 16.1+ 机型/ROM 未内置该类
            null
        }
    }

    /** SDK 版本号，不可用时返回 null */
    fun getVersion(): String? {
        val clazz = seamlessClass ?: return null
        return try {
            clazz.getDeclaredMethod("getVersion").invoke(null) as? String
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * 能力判断：以 getVersion() 的真实调用结果为准，
     * 不依据品牌/机型名称判断（官方文档要求）。
     */
    fun isSupported(): Boolean = getVersion() != null

    /**
     * 对真实原生 View 建立无缝转场绑定。
     *
     * 注意：Flutter Widget 不是 Android 原生 View，禁止把 Dart 侧内容
     * 当作 View 传入。当前 PiliPlus 的常规页面没有可满足
     * 「真实原生 View + Activity Context + 有效布局位置」条件的接入点，
     * 故本方法仅供未来出现原生 View 场景（如 WebView PlatformView +
     * 独立全屏 Activity）时在原生层调用，**未暴露给 Dart**。
     *
     * 因官方文档未给出方法完整签名，此处按方法名 + 参数类型兼容匹配，
     * 支持静态 (Activity, View) / (View) 两种常见形式。
     *
     * @return true 仅代表「反射调用完成且未抛异常」，
     *         不代表转场已被系统接受（是否生效由 ColorOS 侧决定，待真机验证）。
     */
    fun setSeamlessView(activity: Activity, view: View): Boolean {
        val clazz = seamlessClass ?: return false
        return try {
            for (method in clazz.declaredMethods) {
                if (method.name != "setSeamlessView") continue
                val params = method.parameterTypes
                val invoked: Any? = when (params.size) {
                    2 -> if (
                        params[0].isAssignableFrom(Activity::class.java) &&
                        params[1].isAssignableFrom(View::class.java)
                    ) {
                        method.invoke(null, activity, view)
                    } else {
                        continue
                    }

                    1 -> if (params[0].isAssignableFrom(View::class.java)) {
                        method.invoke(null, view)
                    } else {
                        continue
                    }

                    else -> continue
                }
                // Boolean 返回值（如有）为 false 时视为系统侧拒绝
                return !(invoked is Boolean && !invoked)
            }
            false
        } catch (_: Throwable) {
            false
        }
    }

    /** 结束当前无缝转场动画 */
    fun finishCurrentAnimation(): Boolean {
        val clazz = seamlessClass ?: return false
        return try {
            clazz.getDeclaredMethod("finishCurrentAnimation").invoke(null)
            true
        } catch (_: Throwable) {
            false
        }
    }

    /**
     * 注册 MethodChannel。仅暴露能力检测与结束动画两个入口；
     * setSeamlessView 因缺少真实原生 View 来源（见方法注释）不对外暴露，
     * 避免 Dart 层产生「已建立无缝转场」的虚假成功状态。
     */
    fun registerChannel(messenger: BinaryMessenger) {
        MethodChannel(messenger, CHANNEL_NAME).setMethodCallHandler { call, result ->
            when (call.method) {
                "checkSupport" -> {
                    val version = getVersion()
                    result.success(
                        mapOf(
                            "supported" to (version != null),
                            "version" to version,
                            "reason" to if (version != null) {
                                null
                            } else {
                                "OplusViewSeamless 不可用（非 ColorOS 16.1+ 或 ROM 未内置该能力）"
                            },
                        )
                    )
                }

                "finishCurrentAnimation" -> result.success(finishCurrentAnimation())

                else -> result.notImplemented()
            }
        }
    }
}
