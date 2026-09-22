import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';

import '../design/tokens.dart';
import '../models/profile.dart';
import '../state/profiles_controller.dart';
import 'parts.dart';

/// The diagonal hatch the artboard uses behind a session preview:
/// `repeating-linear-gradient(135deg, #121417 0 6px, #16191C 6px 12px)`
class Hatch extends StatelessWidget {
  const Hatch({
    super.key,
    this.a = const Color(0xFF121417),
    this.b = const Color(0xFF16191C),
    this.band = 6,
  });

  final Color a;
  final Color b;
  final double band;

  @override
  Widget build(BuildContext context) =>
      CustomPaint(painter: _HatchPainter(a, b, band), size: Size.infinite);
}

class _HatchPainter extends CustomPainter {
  _HatchPainter(this.a, this.b, this.band);

  final Color a;
  final Color b;
  final double band;

  @override
  void paint(Canvas canvas, Size size) {
    canvas.drawRect(Offset.zero & size, Paint()..color = a);
    final paint = Paint()
      ..color = b
      ..strokeWidth = band
      ..style = PaintingStyle.stroke;
    // 135deg stripes: step along x by twice the band, on the diagonal.
    final step = band * 2 * 1.4142;
    for (var x = -size.height; x < size.width + size.height; x += step) {
      canvas.drawLine(
        Offset(x, 0),
        Offset(x + size.height, size.height),
        paint,
      );
    }
  }

  @override
  bool shouldRepaint(_HatchPainter old) =>
      old.a != a || old.b != b || old.band != band;
}

/// `tabsOpen`: every running session as a card, two across.
class SessionsGrid extends StatelessWidget {
  const SessionsGrid({
    super.key,
    required this.controller,
    required this.onAdd,
  });

  final ProfilesController controller;
  final VoidCallback onAdd;

  static Route<void> route({
    required ProfilesController controller,
    required VoidCallback onAdd,
  }) =>
      PageRouteBuilder<void>(
        opaque: false,
        barrierColor: Colors.transparent,
        transitionDuration: Mo.fade,
        reverseTransitionDuration: Mo.fade,
        pageBuilder: (_, __, ___) =>
            SessionsGrid(controller: controller, onAdd: onAdd),
        transitionsBuilder: (_, animation, __, child) =>
            FadeTransition(opacity: animation, child: child),
      );

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      backgroundColor: Sk.grid,
      body: SafeArea(
        child: Builder(
          builder: (context) => Column(
            children: [
              Padding(
                padding: const EdgeInsets.fromLTRB(18, 14, 18, 14),
                child: Row(
                  children: [
                    Expanded(
                      child: Text('Open sessions',
                          style: T.s(18,
                              weight: FontWeight.w700, tracking: -0.3)),
                    ),
                    GestureDetector(
                      onTap: () => Navigator.of(context).pop(),
                      child: Container(
                        width: 34,
                        height: 34,
                        alignment: Alignment.center,
                        decoration: BoxDecoration(
                          border: Border.all(color: Sk.line4),
                          borderRadius: BorderRadius.circular(17),
                        ),
                        child: const Glyph(Ic.close,
                            size: 11, viewBox: 12, color: Sk.soft, stroke: 1.7),
                      ),
                    ),
                  ],
                ),
              ),
              Expanded(
                child: ValueListenableBuilder<List<String>>(
                  valueListenable: controller.liveIds,
                  builder: (context, openIds, _) {
                    // Only sessions you actually opened are tabs; the rest live
                    // in the profiles list, behind the header's profiles icon.
                    final open = [
                      for (final id in openIds)
                        if (controller.byId(id) != null) controller.byId(id)!,
                    ];
                    return Column(
                      children: [
                        if (open.isEmpty) const _NoSessions(),
                        Expanded(
                          child: ValueListenableBuilder<String?>(
                      valueListenable: controller.activeId,
                      builder: (context, activeId, __) => GridView.builder(
                    padding: const EdgeInsets.fromLTRB(14, 4, 14, 26),
                    gridDelegate:
                        const SliverGridDelegateWithFixedCrossAxisCount(
                      crossAxisCount: 2,
                      crossAxisSpacing: 12,
                      mainAxisSpacing: 12,
                      mainAxisExtent: 148,
                    ),
                        itemCount: open.length + 1,
                        itemBuilder: (context, i) {
                          if (i == open.length) {
                            return _NewCard(onTap: () {
                              Navigator.of(context).pop();
                              onAdd();
                            });
                          }
                          final p = open[i];
                          return _SessionCard(
                            profile: p,
                            runtime: controller.runtime(p.id),
                            photo: controller.avatar(p.id),
                            preview: controller.thumbnail(p.id),
                            active: p.id == activeId,
                            onOpen: () {
                              controller.switchTo(p.id);
                              Navigator.of(context).pop();
                            },
                            onClose: () => controller.sleep(p.id),
                          );
                        },
                      ),
                          ),
                        ),
                      ],
                    );
                  },
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}

class _SessionCard extends StatefulWidget {
  const _SessionCard({
    required this.profile,
    required this.runtime,
    required this.photo,
    required this.preview,
    required this.active,
    required this.onOpen,
    required this.onClose,
  });

  final WaProfile profile;
  final ValueListenable<WaRuntime> runtime;
  final ValueListenable<Uint8List?> photo;
  final ValueListenable<Uint8List?> preview;
  final bool active;
  final VoidCallback onOpen;
  final VoidCallback onClose;

  @override
  State<_SessionCard> createState() => _SessionCardState();
}

class _SessionCardState extends State<_SessionCard> {
  bool _down = false;

  @override
  Widget build(BuildContext context) {
    final profile = widget.profile;
    final active = widget.active;
    final runtime = widget.runtime;
    final photo = widget.photo;
    final onClose = widget.onClose;
    final color = ProfileColors.at(profile.color);
    return GestureDetector(
      onTap: widget.onOpen,
      onTapDown: (_) => setState(() => _down = true),
      onTapCancel: () => setState(() => _down = false),
      onTapUp: (_) => setState(() => _down = false),
      child: AnimatedScale(
        scale: _down ? 0.97 : 1,
        duration: Mo.tap,
        curve: Curves.easeOut,
        child: AnimatedContainer(
        duration: Mo.tap,
        clipBehavior: Clip.antiAlias,
        decoration: BoxDecoration(
          color: Sk.card,
          borderRadius: BorderRadius.circular(14),
          // The tab you are on reads as selected the way a Chrome tab does:
          // its own colour around it, and it sits slightly above the others.
          border: Border.all(
            color: active ? color : Sk.line4,
            width: active ? 1.6 : 1,
          ),
          boxShadow: [
            BoxShadow(
              color: active
                  ? color.withValues(alpha: 0.22)
                  : Colors.black.withValues(alpha: 0.28),
              blurRadius: active ? 16 : 8,
              spreadRadius: active ? 0 : -2,
              offset: const Offset(0, 3),
            ),
          ],
        ),
        child: Column(
          children: [
            Container(
              color: Sk.cardHead,
              padding: const EdgeInsets.fromLTRB(10, 9, 8, 8),
              child: Row(
                children: [
                  ProfileMark(
                      color: color,
                      size: 18,
                      radius: 6,
                      glyphSize: 11,
                      stroke: 2.6,
                      photo: photo),
                  const SizedBox(width: 8),
                  Expanded(
                    child: Text(profile.name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: T.s(12.5, weight: FontWeight.w700)),
                  ),
                  ValueListenableBuilder<WaRuntime>(
                    valueListenable: runtime,
                    builder: (context, s, _) => s.unread > 0 && !profile.muted
                        ? Padding(
                            padding: const EdgeInsets.only(left: 4),
                            child: UnreadPill(s.unread),
                          )
                        : const SizedBox.shrink(),
                  ),
                  // Chrome's tab close is a comfortable target with a hover
                  // plate; 18px of glyph was a miss waiting to happen.
                  InkResponse(
                    onTap: onClose,
                    radius: 18,
                    containedInkWell: false,
                    child: Container(
                      width: 28,
                      height: 28,
                      alignment: Alignment.center,
                      child: const Glyph(Ic.closeSmall,
                          size: 9.5,
                          viewBox: 10,
                          color: Sk.icon,
                          stroke: 1.9),
                    ),
                  ),
                ],
              ),
            ),
            Expanded(
              child: ValueListenableBuilder<Uint8List?>(
                valueListenable: widget.preview,
                builder: (context, shot, _) => Stack(
                  fit: StackFit.expand,
                  children: [
                    const Hatch(),
                    if (shot != null)
                      Image.memory(
                        shot,
                        fit: BoxFit.cover,
                        alignment: Alignment.topCenter,
                        gaplessPlayback: true,
                      ),
                    if (shot == null)
                      Center(
                        child: ValueListenableBuilder<WaRuntime>(
                          valueListenable: runtime,
                          builder: (context, s, _) => Text(
                            s.stateLabel.toUpperCase(),
                            style: T.m(8.5, tracking: 0.6, color: Sk.fainter),
                          ),
                        ),
                      ),
                    // The preview is a picture of a page, so it needs its own
                    // floor under the card's chrome to stay readable.
                    Positioned(
                      left: 0,
                      right: 0,
                      bottom: 0,
                      height: 26,
                      child: DecoratedBox(
                        decoration: BoxDecoration(
                          gradient: LinearGradient(
                            begin: Alignment.bottomCenter,
                            end: Alignment.topCenter,
                            colors: [
                              Sk.card.withValues(alpha: 0.85),
                              Sk.card.withValues(alpha: 0),
                            ],
                          ),
                        ),
                      ),
                    ),
                  ],
                ),
              ),
            ),
          ],
        ),
        ),
      ),
    );
  }
}

class _NewCard extends StatelessWidget {
  const _NewCard({required this.onTap});

  final VoidCallback onTap;

  @override
  Widget build(BuildContext context) => GestureDetector(
        onTap: onTap,
        child: CustomPaint(
          painter: const DashedBorder(color: Sk.line6, radius: 14, width: 1.5),
          child: const Center(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Glyph(Ic.plus,
                    size: 18, viewBox: 16, color: Sk.icon, stroke: 1.7),
                SizedBox(height: 8),
                _NewCardLabel(),
              ],
            ),
          ),
        ),
      );
}

class _NewCardLabel extends StatelessWidget {
  const _NewCardLabel();

  @override
  Widget build(BuildContext context) =>
      Text('New profile', style: T.s(12, weight: FontWeight.w600, color: Sk.icon));
}

/// `border: 1.5px dashed` — Flutter has no dashed border, so it is painted.
class DashedBorder extends CustomPainter {
  const DashedBorder({
    required this.color,
    required this.radius,
    this.width = 1.5,
    this.dash = 6,
    this.gap = 5,
  });

  final Color color;
  final double radius;
  final double width;
  final double dash;
  final double gap;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = width;
    final path = Path()
      ..addRRect(RRect.fromRectAndRadius(
          Offset.zero & size, Radius.circular(radius)));
    for (final metric in path.computeMetrics()) {
      var d = 0.0;
      while (d < metric.length) {
        final next = (d + dash).clamp(0.0, metric.length);
        canvas.drawPath(metric.extractPath(d, next), paint);
        d = next + gap;
      }
    }
  }

  @override
  bool shouldRepaint(DashedBorder old) =>
      old.color != color || old.radius != radius || old.width != width;
}

/// `noSessions`: nothing running yet.
class _NoSessions extends StatelessWidget {
  const _NoSessions();

  @override
  Widget build(BuildContext context) => Padding(
        padding: const EdgeInsets.fromLTRB(20, 24, 20, 20),
        child: Text(
          'No sessions running. Open a profile to start one.',
          textAlign: TextAlign.center,
          style: T.s(13, height: 1.5, color: Sk.muted),
        ),
      );
}
