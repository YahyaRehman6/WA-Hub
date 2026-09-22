import 'package:flutter/material.dart';

/// Colours, type and metrics taken verbatim from the `Profile Shell` artboard.
///
/// Every value here appears literally in the design file, so the app and the
/// design cannot drift apart silently.
abstract final class Sk {
  // Surfaces
  static const ink = Color(0xFF0A0B0C); // shell root, header, home area
  static const body = Color(0xFF15171A); // body container, panel, sheets
  static const stage = Color(0xFF101214); // web session viewport, overlays
  static const card = Color(0xFF131619); // session card
  static const cardHead = Color(0xFF1A1D21); // session card header
  static const input = Color(0xFF1D2024); // text fields
  static const rowHover = Color(0xFF1D2126);
  static const settingsBg = Color(0xFF0D0F11);
  static const settingsCard = Color(0xFF171A1E);
  static const menu = Color(0xFF1B1E22);
  static const scrim = Color(0xB808090A); // rgba(8,9,10,0.72)
  static const overlayScrim = Color(0x8C08090A); // rgba(8,9,10,0.55)
  static const grid = Color(0xFF08090A); // full-screen overlay backdrop

  // Lines
  static const line = Color(0xFF1B1E22);
  static const lineSoft = Color(0xFF1F2327);
  static const line2 = Color(0xFF23272C);
  static const line3 = Color(0xFF262A2F);
  static const line4 = Color(0xFF2A2E33);
  static const line5 = Color(0xFF2B2F34);
  static const line6 = Color(0xFF2E3238);
  static const line7 = Color(0xFF31363C);
  static const line8 = Color(0xFF33383E);

  // Ink
  static const text = Color(0xFFE9EAEC);
  static const soft = Color(0xFFC3C8CE);
  static const icon = Color(0xFF949AA1);
  static const muted = Color(0xFF8B9198);
  static const dim = Color(0xFF7F868D);
  static const faint = Color(0xFF6E747B);
  static const fainter = Color(0xFF656C73);
  static const iconDim = Color(0xFF868C93);

  // Accent
  static const accent = Color(0xFF2FA98A);
  static const accentHi = Color(0xFF3FBE9C);
  static const onAccent = Color(0xFF08130F);

  // Destructive
  static const danger = Color(0xFFE08080);
  static const dangerLine = Color(0xFF4A2B2E);
  static const dangerBg = Color(0xFF2A1E20);

  /// The artboard declares Helvetica/Arial for prose and a UI monospace for
  /// codes and counts; on Android those resolve to the platform faces.
  static const sans = <String>['Helvetica', 'Helvetica Neue', 'Arial'];
  static const mono = 'monospace';
}

/// The nine profile colours offered by the add and profile-settings sheets.
/// They sit on `Sk.onAccent`-dark text, so each stays light enough to read.
abstract final class ProfileColors {
  static const values = <Color>[
    Sk.accent,
    Color(0xFF4C8DFF),
    Color(0xFFF2A93B),
    Color(0xFFE0655F),
    Color(0xFF9B7BF0),
    Color(0xFF3FBEDC),
    Color(0xFFE07FB0),
    Color(0xFF7FBF4D),
    Color(0xFF9AA0A6),
  ];

  static Color at(int i) => values[i % values.length];
}

abstract final class T {
  static TextStyle s(
    double size, {
    FontWeight weight = FontWeight.w400,
    double? tracking,
    double? height,
    Color color = Sk.text,
  }) =>
      TextStyle(
        fontFamilyFallback: Sk.sans,
        fontSize: size,
        fontWeight: weight,
        letterSpacing: tracking,
        height: height,
        color: color,
      );

  static TextStyle m(
    double size, {
    FontWeight weight = FontWeight.w400,
    double? tracking,
    Color color = Sk.text,
  }) =>
      TextStyle(
        fontFamily: Sk.mono,
        fontSize: size,
        fontWeight: weight,
        letterSpacing: tracking,
        color: color,
      );

  /// `font-size: 10px; font-weight: 700; letter-spacing: 1px; uppercase`
  static TextStyle get groupTitle =>
      s(10, weight: FontWeight.w700, tracking: 1, color: Sk.faint);
}

abstract final class Mo {
  /// The artboard's own animation timings.
  static const fade = Duration(milliseconds: 140);
  /// The press-down of a card: short enough to read as the tap itself.
  static const tap = Duration(milliseconds: 110);
  static const rise = Duration(milliseconds: 200);
  static const slide = Duration(milliseconds: 220);
  static const curve = Curves.easeOut;
}

ThemeData buildTheme() {
  final base = ThemeData.dark();
  return base.copyWith(
    scaffoldBackgroundColor: Sk.ink,
    canvasColor: Sk.body,
    splashFactory: InkRipple.splashFactory,
    highlightColor: Sk.text.withValues(alpha: 0.05),
    splashColor: Sk.text.withValues(alpha: 0.05),
    colorScheme: base.colorScheme.copyWith(
      surface: Sk.body,
      primary: Sk.accent,
      onPrimary: Sk.onAccent,
    ),
    textSelectionTheme: const TextSelectionThemeData(
      cursorColor: Sk.accent,
      selectionColor: Color(0x552FA98A),
      selectionHandleColor: Sk.accent,
    ),
  );
}
