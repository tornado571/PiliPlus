import 'dart:ui' show ImageFilter;

import 'package:PiliPlus/utils/extension/theme_ext.dart';
import 'package:PiliPlus/utils/storage_pref.dart';
import 'package:material_ui/material_ui.dart';

/// 基础液态玻璃材质容器（v2，Flutter 原生实现）
///
/// 层级（由外到内）：
/// 1. [ClipRRect] 裁剪，模糊与光环严格限制在圆角内；
/// 2. [BackdropFilter] 高斯模糊（背景内容透出）；
/// 3. 渐变边缘光环（[ringWidth] 环带，左上受光/右下背光，
///    模拟玻璃厚度与折射边缘）；
/// 4. 半透明表面色（深浅色分别调校）；
/// 5. 顶部亮边 + 底部内阴影（垂直光照）+ 斜向镜面高光。
///
/// 说明：Flutter 无法对背景纹理做位移采样，真正的光学折射/色散
/// 在引擎层不可实现；Android 13+ 的真折射由原生层
///（LiquidGlassOverlay，Kyant0 backdrop + AGSL）提供，
/// 本组件是 iOS / 桌面 / 低版本 Android / 原生失败时的降级实现。
/// 每个实例对应一个 saveLayer，仅用于常驻的少量浮层（底栏等），
/// 严禁在滚动列表条目上使用。
/// [Pref.liquidGlassBlur] 为 0 时进入低性能降级：无 BackdropFilter，
/// 仅保留光环与高光。
class GlassSurface extends StatelessWidget {
  const GlassSurface({
    super.key,
    required this.child,
    required this.borderRadius,
    this.tint,
    this.sigma,
    this.shape,
    this.borderSide,
    this.ringWidth = 1.5,
  });

  final Widget child;

  /// 模糊裁剪圆角，需与外层形状一致
  final BorderRadius borderRadius;

  /// 表面叠加色，默认取主题 surface 的半透明色（区分深浅色）
  final Color? tint;

  /// 模糊强度 sigma；null 时读取全局设置 [Pref.liquidGlassBlur]
  final double? sigma;

  /// 边缘光环形状（含描边），默认与 [borderRadius] 对应的圆角矩形
  final ShapeBorder? shape;

  final BorderSide? borderSide;

  /// 边缘光环宽度
  final double ringWidth;

  @override
  Widget build(BuildContext context) {
    final colors = ColorScheme.of(context);
    final isDark = colors.isDark;

    final double sigma =
        (this.sigma ?? Pref.liquidGlassBlur).clamp(0.0, 40.0).toDouble();
    final Color tint =
        this.tint ??
        colors.surface.withValues(alpha: isDark ? 0.34 : 0.50);

    final ShapeBorder shape =
        this.shape ??
        RoundedRectangleBorder(
          borderRadius: borderRadius,
          side: _borderSide(isDark),
        );

    // 边缘光环：全幅渐变，中心被内层表面覆盖，仅剩 ringWidth 环带可见
    final Widget glass = DecoratedBox(
      decoration: ShapeDecoration(
        gradient: LinearGradient(
          begin: Alignment.topLeft,
          end: Alignment.bottomRight,
          colors: isDark
              ? [
                  Colors.white.withValues(alpha: 0.30),
                  Colors.white.withValues(alpha: 0.05),
                  colors.onSurface.withValues(alpha: 0.18),
                ]
              : [
                  Colors.white.withValues(alpha: 0.55),
                  Colors.white.withValues(alpha: 0.10),
                  colors.onSurface.withValues(alpha: 0.06),
                ],
        ),
        shape: shape,
      ),
      child: Padding(
        padding: EdgeInsets.all(ringWidth),
        child: ColoredBox(
          color: tint,
          child: DecoratedBox(
            // 顶部亮边 + 底部内阴影（垂直光照方向）
            decoration: ShapeDecoration(
              gradient: LinearGradient(
                begin: Alignment.topCenter,
                end: Alignment.bottomCenter,
                stops: const [0.0, 0.09, 0.9, 1.0],
                colors: [
                  Colors.white.withValues(alpha: isDark ? 0.20 : 0.35),
                  Colors.transparent,
                  Colors.transparent,
                  colors.onSurface.withValues(alpha: isDark ? 0.12 : 0.06),
                ],
              ),
              shape: RoundedRectangleBorder(borderRadius: borderRadius),
            ),
            child: DecoratedBox(
              // 斜向镜面高光
              decoration: ShapeDecoration(
                gradient: LinearGradient(
                  begin: Alignment.topLeft,
                  end: Alignment.bottomRight,
                  stops: const [0.0, 0.4, 1.0],
                  colors: [
                    Colors.white.withValues(alpha: isDark ? 0.16 : 0.30),
                    Colors.white.withValues(alpha: isDark ? 0.04 : 0.08),
                    Colors.transparent,
                  ],
                ),
                shape: RoundedRectangleBorder(borderRadius: borderRadius),
              ),
              child: child,
            ),
          ),
        ),
      ),
    );

    if (sigma <= 0) {
      // 低性能降级：无模糊，仅光环与高光
      return glass;
    }

    return ClipRRect(
      borderRadius: borderRadius,
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: sigma, sigmaY: sigma),
        child: glass,
      ),
    );
  }

  BorderSide _borderSide(bool isDark) =>
      borderSide ??
      BorderSide(
        color: isDark ? const Color(0x1FFFFFFF) : const Color(0x0F000000),
      );
}
