import 'dart:io' show Platform;

import 'package:PiliPlus/common/widgets/liquid_glass.dart';
import 'package:flutter/scheduler.dart' show SchedulerBinding;
import 'package:flutter/services.dart'
    show MethodChannel, MissingPluginException;
import 'package:flutter/rendering.dart' show RenderBox;
import 'package:flutter/widgets.dart'
    show
        BorderRadius,
        BuildContext,
        KeyedSubtree,
        GlobalKey,
        MediaQuery,
        ModalRoute,
        Offset,
        Rect,
        State,
        StatefulWidget,
        Widget;

/// 原生液态玻璃（Android 13+ AGSL 真折射）桥接层
///
/// Android 13+ 且开启玻璃设置时，底栏玻璃由原生
/// LiquidGlassOverlay（Kyant0 backdrop 着色器）渲染：
/// Flutter 底栏保持透明背景正常渲染内容，原生层截取该区域
/// （含其下信息流）做折射/色散叠加，触摸穿透不受影响。
/// 其余平台/低版本/原生失败时由 [GlassSurface] 降级。
abstract final class LiquidGlassNative {
  static const _channel = MethodChannel('piliplus/liquid_glass');

  static bool? _supported;

  /// 是否可用原生折射层（结果缓存）
  static Future<bool> isSupported() async {
    if (!Platform.isAndroid) return false;
    final cached = _supported;
    if (cached != null) return cached;
    try {
      return _supported =
          await _channel.invokeMethod<bool>('isSupported') == true;
    } on MissingPluginException {
      return _supported = false;
    } on Exception {
      return _supported = false;
    }
  }

  /// 同步原生覆盖层位置（物理像素；矩形变化时调用，原生侧原地更新）
  static Future<bool> show(
    Rect rect,
    double radius,
    double devicePixelRatio,
  ) async {
    try {
      return await _channel.invokeMethod<bool>('show', {
        'left': (rect.left * devicePixelRatio).round(),
        'top': (rect.top * devicePixelRatio).round(),
        'width': (rect.width * devicePixelRatio).round(),
        'height': (rect.height * devicePixelRatio).round(),
        'radius': radius * devicePixelRatio,
      }) == true;
    } on Exception {
      return false;
    }
  }

  static Future<void> hide() async {
    try {
      await _channel.invokeMethod<void>('hide');
    } on Exception {}
  }

  /// 原生层调用失败后永久标记不可用（回退 v2）
  static void markUnsupported() => _supported = false;
}

/// 底栏自适应玻璃容器：
/// - 检测期间与不支持时 → [GlassSurface]（v2 降级实现）；
/// - 原生可用 → 透明承载层（原生层负责玻璃视觉），逐帧同步矩形。
class AdaptiveGlassBar extends StatefulWidget {
  const AdaptiveGlassBar({
    super.key,
    required this.child,
    required this.borderRadius,
  });

  final Widget child;

  final BorderRadius borderRadius;

  @override
  State<AdaptiveGlassBar> createState() => _AdaptiveGlassBarState();
}

class _AdaptiveGlassBarState extends State<AdaptiveGlassBar> {
  final _boxKey = GlobalKey();
  bool? _native;
  bool _syncing = false;
  bool _overlayShown = false;
  Rect? _lastRect;

  @override
  void initState() {
    super.initState();
    LiquidGlassNative.isSupported().then((value) {
      if (mounted) {
        setState(() => _native = value);
      }
    });
  }

  @override
  void dispose() {
    if (_native == true) LiquidGlassNative.hide();
    super.dispose();
  }

  void _scheduleSync() {
    if (_syncing) return;
    _syncing = true;
    SchedulerBinding.instance.addPostFrameCallback((_) {
      _syncing = false;
      if (!mounted || _native != true) return;
      _pushRect();
      _scheduleSync();
    });
  }

  void _pushRect() async {
    final renderObject = _boxKey.currentContext?.findRenderObject();
    if (renderObject is! RenderBox || !renderObject.attached) return;
    final size = renderObject.size;
    if (!renderObject.hasSize || size.isEmpty) return;

    // 路由被覆盖（进入设置页等）时隐藏原生覆盖层，返回主页时恢复
    final route = ModalRoute.of(context);
    if (route != null && !route.isCurrent) {
      if (_overlayShown) {
        _overlayShown = false;
        _lastRect = null;
        await LiquidGlassNative.hide();
      }
      return;
    }

    final origin = renderObject.localToGlobal(Offset.zero);
    final rect = origin & size;
    final last = _lastRect;
    if (last != null && rect == last) return;
    _lastRect = rect;
    final ok = await LiquidGlassNative.show(
      rect,
      size.height / 2,
      MediaQuery.devicePixelRatioOf(context),
    );
    if (!ok && mounted && _native == true) {
      // 原生层创建失败（无 FlutterSurfaceView / 异常等）：
      // 永久回退 v2 降级实现，避免无背景状态
      LiquidGlassNative.markUnsupported();
      setState(() => _native = false);
    } else if (ok) {
      _overlayShown = true;
    }
  }

  @override
  Widget build(BuildContext context) {
    if (_native != true) {
      // v2 降级（检测期间 / 不支持平台）
      return GlassSurface(
        borderRadius: widget.borderRadius,
        child: widget.child,
      );
    }

    // 原生模式：透明承载，玻璃视觉由原生层叠加
    _scheduleSync();
    return KeyedSubtree(key: _boxKey, child: widget.child);
  }
}
