package com.example.piliplus

import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.WindowManager.LayoutParams
import com.ryanheise.audioservice.AudioServiceActivity
import io.flutter.embedding.engine.FlutterEngine

class MainActivity : AudioServiceActivity() {
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        if (AndroidHelper.isFoldable) {
            AndroidHelper.ToDart.onConfigurationChanged?.run()
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        registerNativeChannels(flutterEngine)
    }

    /// 兜底：若 configureFlutterEngine 被父类链覆盖未生效，
    /// 在 onPostResume（引擎必然已创建）再注册一次（幂等）
    override fun onPostResume() {
        super.onPostResume()
        flutterEngine?.let { registerNativeChannels(it) }
    }

    private fun registerNativeChannels(flutterEngine: FlutterEngine) {
        OplusViewSeamlessHelper.registerChannel(
            flutterEngine.dartExecutor.binaryMessenger
        )
        LiquidGlassOverlay.registerChannel(
            flutterEngine.dartExecutor.binaryMessenger,
            this,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            window.attributes.layoutInDisplayCutoutMode =
                LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        AndroidHelper.ToDart.onUserLeaveHint?.run()
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration?) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        AndroidHelper.isPipMode = isInPictureInPictureMode
    }
}
