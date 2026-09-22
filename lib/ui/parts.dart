import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter_svg/flutter_svg.dart';

import '../design/tokens.dart';

/// The artboard's icons, verbatim. Rendering the original SVG keeps the shapes
/// identical to the design rather than approximating them with a font icon.
abstract final class Ic {
  static const reload =
      '<path d="M21 12a9 9 0 1 1-9-9c2.52 0 4.93 1 6.74 2.74L21 8"/>'
      '<path d="M21 3v5h-5"/>';
  static const profiles = '<path d="M18 21a8 8 0 0 0-16 0"/>'
      '<circle cx="10" cy="8" r="5"/>'
      '<path d="M22 20c0-3.37-2-6.5-4-8a5 5 0 0 0-.45-8.3"/>';
  static const person =
      '<path d="M20 21a8 8 0 0 0-16 0"/><circle cx="12" cy="8" r="4.6"/>';
  static const chevronLeft = '<path d="M11 3.5 L5.5 9 L11 14.5"/>';
  static const plus = '<path d="M8 2.5 V13.5 M2.5 8 H13.5"/>';
  static const close = '<path d="M1.5 1.5 L10.5 10.5 M10.5 1.5 L1.5 10.5"/>';
  static const closeSmall = '<path d="M1 1 L9 9 M9 1 L1 9"/>';
  static const dots = '<circle cx="12" cy="12" r="1"/><circle cx="12" cy="5" r="1"/>'
      '<circle cx="12" cy="19" r="1"/>';
  static const gear =
      '<path d="M9.671 4.136a2.34 2.34 0 0 1 4.659 0 2.34 2.34 0 0 0 3.319 1.915 '
      '2.34 2.34 0 0 1 2.33 4.033 2.34 2.34 0 0 0 0 3.831 2.34 2.34 0 0 1-2.33 '
      '4.033 2.34 2.34 0 0 0-3.319 1.915 2.34 2.34 0 0 1-4.659 0 2.34 2.34 0 0 '
      '0-3.32-1.915 2.34 2.34 0 0 1-2.33-4.033 2.34 2.34 0 0 0 0-3.831A2.34 2.34 '
      '0 0 1 6.35 6.051a2.34 2.34 0 0 0 3.319-1.915"/><circle cx="12" cy="12" r="3"/>';
  static const tab = '<rect x="2" y="3.5" width="14" height="11" rx="2.4"/>'
      '<path d="M2 7 H16"/>';
  static const trash =
      '<path d="M3 5 H13 M6.5 5 V3.4 H9.5 V5 M4.4 5 L5 13 H11 L11.6 5"/>';
  static const arrowRight = '<path d="M5 12h14"/><path d="m12 5 7 7-7 7"/>';
}

class Glyph extends StatelessWidget {
  const Glyph(
    this.paths, {
    super.key,
    required this.size,
    required this.color,
    this.viewBox = 24,
    this.stroke = 1.9,
    this.fill = false,
  });

  final String paths;
  final double size;
  final Color color;
  final double viewBox;
  final double stroke;
  final bool fill;

  @override
  Widget build(BuildContext context) {
    final paint = fill
        ? 'fill="currentColor" stroke="none"'
        : 'fill="none" stroke="currentColor" stroke-width="$stroke" '
            'stroke-linecap="round" stroke-linejoin="round"';
    return SvgPicture.string(
      '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 $viewBox $viewBox" '
      '$paint>$paths</svg>',
      width: size,
      height: size,
      colorFilter: ColorFilter.mode(color, BlendMode.srcIn),
    );
  }
}

/// A tappable icon slot. The header uses 30x40 hit areas.
class IconSlot extends StatelessWidget {
  const IconSlot({
    super.key,
    required this.child,
    required this.onTap,
    this.width = 38,
    this.height = 46,
    this.tooltip,
  });

  final Widget child;
  final VoidCallback onTap;
  final double width;
  final double height;
  final String? tooltip;

  @override
  Widget build(BuildContext context) {
    final button = InkResponse(
      onTap: onTap,
      radius: 22,
      child: SizedBox(width: width, height: height, child: Center(child: child)),
    );
    return tooltip == null ? button : Tooltip(message: tooltip!, child: button);
  }
}

/// `min-width: 17px; height: 17px; border-radius: 9px; background: #2FA98A`
class UnreadPill extends StatelessWidget {
  const UnreadPill(
    this.count, {
    super.key,
    this.size = 17,
    this.fontSize = 9.5,
    this.border,
  });

  final int count;
  final double size;
  final double fontSize;
  final Color? border;

  @override
  Widget build(BuildContext context) {
    if (count <= 0) return const SizedBox.shrink();
    return Container(
      constraints: BoxConstraints(minWidth: size, minHeight: size),
      padding: const EdgeInsets.symmetric(horizontal: 5),
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: Sk.accent,
        borderRadius: BorderRadius.circular(size / 2 + 0.5),
        border: border == null ? null : Border.all(color: border!, width: 2),
      ),
      child: Text(
        count > 99 ? '99+' : '$count',
        style: T.s(fontSize, weight: FontWeight.w700, color: Sk.onAccent),
      ),
    );
  }
}

/// The rounded-square profile mark: a person glyph on the profile's colour.
class ProfileMark extends StatelessWidget {
  const ProfileMark({
    super.key,
    required this.color,
    required this.size,
    required this.radius,
    required this.glyphSize,
    this.stroke = 2.2,
    this.photo,
  });

  final Color color;
  final double size;
  final double radius;
  final double glyphSize;
  final double stroke;

  /// The account's own WhatsApp photo. The coloured person glyph is the
  /// fallback for a session that has not reported one yet.
  final ValueListenable<Uint8List?>? photo;

  @override
  Widget build(BuildContext context) {
    final placeholder = Container(
      width: size,
      height: size,
      alignment: Alignment.center,
      decoration: BoxDecoration(
        color: color,
        borderRadius: BorderRadius.circular(radius),
      ),
      child: Glyph(Ic.person, size: glyphSize, color: Sk.ink, stroke: stroke),
    );
    if (photo == null) return placeholder;
    return ValueListenableBuilder<Uint8List?>(
      valueListenable: photo!,
      builder: (context, bytes, _) {
        if (bytes == null || bytes.isEmpty) return placeholder;
        return ClipRRect(
          borderRadius: BorderRadius.circular(radius),
          child: Image.memory(
            bytes,
            width: size,
            height: size,
            fit: BoxFit.cover,
            gaplessPlayback: true,
            filterQuality: FilterQuality.medium,
            errorBuilder: (_, __, ___) => placeholder,
          ),
        );
      },
    );
  }
}

/// `width: 44px; height: 26px; border-radius: 13px` with a 20px knob.
class Toggle extends StatelessWidget {
  const Toggle({
    super.key,
    required this.value,
    this.width = 44,
    this.height = 26,
    this.knob = 20,
  });

  final bool value;
  final double width;
  final double height;
  final double knob;

  @override
  Widget build(BuildContext context) => AnimatedContainer(
        duration: Mo.fade,
        curve: Mo.curve,
        width: width,
        height: height,
        padding: const EdgeInsets.all(3),
        decoration: BoxDecoration(
          color: value ? Sk.accent : Sk.line5,
          borderRadius: BorderRadius.circular(height / 2),
        ),
        child: Row(
          mainAxisAlignment:
              value ? MainAxisAlignment.end : MainAxisAlignment.start,
          children: [
            Container(
              width: knob,
              height: knob,
              decoration: BoxDecoration(
                color: value ? Sk.onAccent : Sk.faint,
                borderRadius: BorderRadius.circular(knob / 2),
              ),
            ),
          ],
        ),
      );
}

/// A label + hint row with a trailing toggle, as used by both settings surfaces.
class ToggleRow extends StatelessWidget {
  const ToggleRow({
    super.key,
    required this.label,
    required this.hint,
    required this.value,
    required this.onChanged,
    this.padding = const EdgeInsets.symmetric(vertical: 13),
    this.divider = true,
  });

  final String label;
  final String hint;
  final bool value;
  final ValueChanged<bool> onChanged;
  final EdgeInsets padding;
  final bool divider;

  @override
  Widget build(BuildContext context) => InkWell(
        onTap: () => onChanged(!value),
        child: Container(
          padding: padding,
          decoration: divider
              ? const BoxDecoration(
                  border: Border(bottom: BorderSide(color: Sk.lineSoft)))
              : null,
          child: Row(
            children: [
              Expanded(
                child: Column(
                  crossAxisAlignment: CrossAxisAlignment.start,
                  children: [
                    Text(label,
                        style: T.s(14,
                            weight: FontWeight.w600, tracking: -0.1)),
                    const SizedBox(height: 2),
                    Text(hint,
                        style: T.s(11.5, height: 1.45, color: Sk.muted)),
                  ],
                ),
              ),
              const SizedBox(width: 14),
              Toggle(value: value),
            ],
          ),
        ),
      );
}

/// `height: 46px; border-radius: 13px; background: #2FA98A`
class AccentButton extends StatelessWidget {
  const AccentButton({
    super.key,
    required this.label,
    required this.onTap,
    this.trailing,
    this.height = 46,
    this.fontSize = 14.5,
  });

  final String label;
  final VoidCallback onTap;
  final Widget? trailing;
  final double height;
  final double fontSize;

  @override
  Widget build(BuildContext context) => Material(
        color: Sk.accent,
        borderRadius: BorderRadius.circular(13),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(13),
          child: SizedBox(
            height: height,
            child: Row(
              mainAxisAlignment: MainAxisAlignment.center,
              children: [
                Text(label,
                    style: T.s(fontSize,
                        weight: FontWeight.w700, color: Sk.onAccent)),
                if (trailing != null) ...[const SizedBox(width: 9), trailing!],
              ],
            ),
          ),
        ),
      );
}

/// `height: 46px; border-radius: 13px; border: 1px solid #2E3238`
class GhostButton extends StatelessWidget {
  const GhostButton({
    super.key,
    required this.label,
    required this.onTap,
    this.leading,
    this.color = Sk.soft,
    this.borderColor = Sk.line6,
    this.height = 46,
  });

  final String label;
  final VoidCallback onTap;
  final Widget? leading;
  final Color color;
  final Color borderColor;
  final double height;

  @override
  Widget build(BuildContext context) => Material(
        color: Colors.transparent,
        borderRadius: BorderRadius.circular(13),
        child: InkWell(
          onTap: onTap,
          borderRadius: BorderRadius.circular(13),
          child: Container(
            height: height,
            padding: const EdgeInsets.symmetric(horizontal: 18),
            decoration: BoxDecoration(
              borderRadius: BorderRadius.circular(13),
              border: Border.all(color: borderColor),
            ),
            child: Row(
              mainAxisSize: MainAxisSize.min,
              children: [
                if (leading != null) ...[leading!, const SizedBox(width: 8)],
                Text(label,
                    style:
                        T.s(14, weight: FontWeight.w600, color: color)),
              ],
            ),
          ),
        ),
      );
}

/// `height: 30px; border-radius: 9px` swatches, nine across.
class ColorPicker extends StatelessWidget {
  const ColorPicker({super.key, required this.value, required this.onChanged});

  final int value;
  final ValueChanged<int> onChanged;

  @override
  Widget build(BuildContext context) => Row(
        children: [
          for (var i = 0; i < ProfileColors.values.length; i++) ...[
            Expanded(
              child: GestureDetector(
                onTap: () => onChanged(i),
                child: Container(
                  height: 30,
                  alignment: Alignment.center,
                  decoration: BoxDecoration(
                    color: ProfileColors.at(i),
                    borderRadius: BorderRadius.circular(9),
                    border: Border.all(
                      color: i == value ? Sk.text : Colors.transparent,
                      width: 2,
                    ),
                  ),
                  child: i == value
                      ? const Glyph('<path d="M2 7.4 L5.4 10.8 L12 3.8"/>',
                          size: 13, viewBox: 14, color: Sk.ink, stroke: 2.2)
                      : null,
                ),
              ),
            ),
            if (i != ProfileColors.values.length - 1) const SizedBox(width: 5),
          ],
        ],
      );
}

/// The grab handle and rounded top every bottom sheet in the artboard shares.
class DesignSheet extends StatelessWidget {
  const DesignSheet({super.key, required this.child, this.maxHeightFactor = 0.82});

  final Widget child;
  final double maxHeightFactor;

  @override
  Widget build(BuildContext context) {
    final insets = MediaQuery.viewInsetsOf(context).bottom;
    return AnimatedPadding(
      duration: Mo.fade,
      padding: EdgeInsets.only(bottom: insets),
      child: Container(
        constraints: BoxConstraints(
          maxHeight: MediaQuery.sizeOf(context).height * maxHeightFactor,
        ),
        decoration: const BoxDecoration(
          color: Sk.body,
          border: Border(top: BorderSide(color: Sk.line3)),
          borderRadius: BorderRadius.vertical(
            top: Radius.circular(20),
            bottom: Radius.circular(26),
          ),
        ),
        child: SafeArea(
          top: false,
          child: SingleChildScrollView(
            padding: const EdgeInsets.fromLTRB(18, 12, 18, 26),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Center(
                  child: Container(
                    width: 38,
                    height: 4,
                    margin: const EdgeInsets.only(top: 2, bottom: 14),
                    decoration: BoxDecoration(
                      color: Sk.line8,
                      borderRadius: BorderRadius.circular(2),
                    ),
                  ),
                ),
                child,
              ],
            ),
          ),
        ),
      ),
    );
  }
}

/// `border-radius: 11px; border: 1px solid #2A2E33; background: #1D2024`
class DesignField extends StatelessWidget {
  const DesignField({
    super.key,
    required this.controller,
    required this.hint,
    this.autofocus = false,
    this.onSubmitted,
    this.height = 40,
    this.fontSize = 14.5,
  });

  final TextEditingController controller;
  final String hint;
  final bool autofocus;
  final ValueChanged<String>? onSubmitted;
  final double height;
  final double fontSize;

  @override
  Widget build(BuildContext context) => SizedBox(
        height: height,
        child: TextField(
          controller: controller,
          autofocus: autofocus,
          onSubmitted: onSubmitted,
          textCapitalization: TextCapitalization.words,
          textInputAction: TextInputAction.done,
          style: T.s(fontSize),
          cursorColor: Sk.accent,
          decoration: InputDecoration(
            hintText: hint,
            hintStyle: T.s(fontSize, color: Sk.dim),
            filled: true,
            fillColor: Sk.input,
            isDense: true,
            contentPadding: const EdgeInsets.symmetric(horizontal: 12),
            border: OutlineInputBorder(
              borderRadius: BorderRadius.circular(11),
              borderSide: const BorderSide(color: Sk.line4),
            ),
            enabledBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(11),
              borderSide: const BorderSide(color: Sk.line4),
            ),
            focusedBorder: OutlineInputBorder(
              borderRadius: BorderRadius.circular(11),
              borderSide: const BorderSide(color: Sk.accent, width: 1.5),
            ),
          ),
        ),
      );
}

/// `font-size: 10px; font-weight: 700; letter-spacing: 0.8px; uppercase`
class FieldLabel extends StatelessWidget {
  const FieldLabel(this.text, {super.key});

  final String text;

  @override
  Widget build(BuildContext context) => Text(
        text.toUpperCase(),
        style: T.s(10, weight: FontWeight.w700, tracking: 0.8, color: Sk.dim),
      );
}
