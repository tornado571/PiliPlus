import 'dart:io' show Platform;

import 'package:flutter/services.dart'
    show MethodChannel, MissingPluginException;

/// 无缝转场能力检测结果
///
/// - [supported] 是否支持
/// - [version] SDK 版本号（支持时）
/// - [reason] 不支持原因（不支持时）
typedef OplusSeamlessSupport = ({
  bool supported,
  String? version,
  String? reason,
});

/// OPPO View Seamless（ColorOS 16.1+）能力探测封装
///
/// 该 SDK 提供「卡片 View 与全屏 Activity 之间的无缝转场动画」，
/// 不涉及任何玻璃材质/UI 能力。
///
/// ## 当前接入状态（如实说明）
///
/// - Flutter Widget **不是** Android 原生 View，无法作为
///   `OplusViewSeamless.setSeamlessView()` 的参数；
/// - PiliPlus 常规页面（视频/首页/动态等）均为 Flutter 渲染，
///   不存在可满足「真实原生 View + Activity Context + 有效布局位置 +
///   合理内容快照」条件的接入点（视频为 media_kit Texture，
///   唯一原生 View 是 inappwebview 的 WebView PlatformView）；
/// - 因此 Dart 侧只暴露能力检测与结束动画；
///   `setSeamlessView` 保留在原生层（OplusViewSeamlessHelper.kt），
///   供未来出现真实原生 View 场景时调用，不对外伪造成功状态。
///
/// 原生侧以反射方式调用 ROM 提供的 SDK（不添加 Gradle 依赖），
/// 非 ColorOS 16.1+ / ROM 未内置 / 动效等级不足的设备一律返回不支持，
/// 应用继续使用原有页面跳转，无崩溃风险。
abstract final class OplusViewSeamless {
  static const _channel = MethodChannel('piliplus/oplus_view_seamless');

  /// 检测当前设备是否支持无缝转场
  ///
  /// 以 SDK `getVersion()` 的真实调用结果为准（官方文档要求），
  /// 不依据品牌/机型判断。
  static Future<OplusSeamlessSupport> checkSupport() async {
    if (!Platform.isAndroid) {
      return (
        supported: false,
        version: null,
        reason: '非 Android 平台',
      );
    }
    try {
      final res = await _channel.invokeMapMethod<String, dynamic>(
        'checkSupport',
      );
      if (res != null) {
        return (
          supported: res['supported'] == true,
          version: res['version'] as String?,
          reason: res['reason'] as String?,
        );
      }
    } on MissingPluginException {
      // 原生侧未注册（旧版本/其他平台实现），安全降级
    } on Exception {
      // PlatformException 等，安全降级
    }
    return (supported: false, version: null, reason: '通道不可用');
  }

  /// 结束当前无缝转场动画（如支持）
  ///
  /// 返回是否调用成功；不支持时返回 false，不影响原有导航。
  static Future<bool> finishCurrentAnimation() async {
    if (!Platform.isAndroid) {
      return false;
    }
    try {
      return await _channel.invokeMethod<bool>('finishCurrentAnimation') ==
          true;
    } on MissingPluginException {
      return false;
    } on Exception {
      return false;
    }
  }
}
