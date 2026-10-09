import 'dart:ui' show ImageFilter;

import 'package:PiliPlus/utils/extension/theme_ext.dart';
import 'package:PiliPlus/utils/storage_pref.dart';
import 'package:material_ui/material_ui.dart';

/// 基础液态玻璃材质容器
///
/// 实现层级（由外到内）：
/// 1. [ClipRRect] 裁剪模糊区域，保证模糊严格限制在圆角内；
/// 2. [BackdropFilter] 对背景内容做高斯模糊（背景透出）；
/// 3. 半透明表面色 [ColoredBox]（深浅色模式下分别调校，保证前景对比度）；
/// 4. 高光渐变（左上受光、右下阴影的镜面效果）+ 细描边。
///
/// 性能说明：
/// - 每个实例对应一个 saveLayer，只用于常驻的少量浮层（底栏、悬浮面板），
///   严禁在滚动列表条目上使用；
/// - [Pref.liquidGlassBlur] 为 0 时进入降级模式：不创建 BackdropFilter，
///   仅保留半透明表面 + 高光，供低端设备使用；
/// - 高级折射/色散效果（真正的 Liquid Glass 光学折射）需要自定义 Shader
///   采样背景，当前未实现，此组件仅为「基础玻璃效果」。
class GlassSurface extends StatelessWidget {
  const GlassSurface({
    super.key,
    required this.child,
    required this.borderRadius,
    this.tint,
    this.sigma,
    this.shape,
    this.borderSide,
  });

  final Widget child;

  /// 模糊裁剪圆角，需与外层形状一致
  final BorderRadius borderRadius;

  /// 表面叠加色，默认取主题 surface 的半透明色（区分深浅色）
  final Color? tint;

  /// 模糊强度 sigma；null 时读取全局设置 [Pref.liquidGlassBlur]
  final double? sigma;

  /// 玻璃层形状（绘制高光与描边），默认与 [borderRadius] 对应的圆角矩形
  final ShapeBorder? shape;

  final BorderSide? borderSide;

  @override
  Widget build(BuildContext context) {
    final colors = ColorScheme.of(context);
    final isDark = colors.isDark;

    final double sigma =
        (this.sigma ?? Pref.liquidGlassBlur).clamp(0.0, 40.0).toDouble();
    final Color tint =
        this.tint ??
        colors.surface.withValues(alpha: isDark ? 0.40 : 0.58);

    final ShapeBorder shape =
        this.shape ??
        RoundedRectangleBorder(
          borderRadius: borderRadius,
          side: _borderSide(isDark),
        );

    final Widget surface = ColoredBox(
      color: tint,
      child: DecoratedBox(
        decoration: ShapeDecoration(
          // 镜面高光：左上亮、右下暗，模拟光照方向
          gradient: LinearGradient(
            begin: Alignment.topLeft,
            end: Alignment.bottomRight,
            stops: const [0.0, 0.45, 1.0],
            colors: [
              Colors.white.withValues(alpha: isDark ? 0.14 : 0.35),
              Colors.white.withValues(alpha: isDark ? 0.03 : 0.08),
              colors.onSurface.withValues(alpha: isDark ? 0.10 : 0.05),
            ],
          ),
          shape: shape,
        ),
        child: child,
      ),
    );

    if (sigma <= 0) {
      // 低性能降级：无模糊，仅半透明表面
      return surface;
    }

    return ClipRRect(
      borderRadius: borderRadius,
      child: BackdropFilter(
        filter: ImageFilter.blur(sigmaX: sigma, sigmaY: sigma),
        child: surface,
      ),
    );
  }

  BorderSide _borderSide(bool isDark) =>
      borderSide ??
      BorderSide(
        color: isDark
            ? const Color(0x1FFFFFFF)
            : const Color(0x0F000000),
      );
}
