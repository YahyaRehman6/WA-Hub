import 'package:flutter/material.dart';

import '../design/tokens.dart';
import '../models/profile.dart';
import '../state/profiles_controller.dart';
import 'parts.dart';

/// `panelOpen`: the profiles dropdown anchored under the header, with a
/// per-row menu.
class ProfilesPanel {
  static Future<void> show(
    BuildContext context,
    ProfilesController controller, {
    required VoidCallback onAdd,
    required void Function(WaProfile) onProfileSettings,
  }) =>
      showGeneralDialog<void>(
        context: context,
        barrierDismissible: true,
        barrierLabel: 'Profiles',
        barrierColor: Sk.overlayScrim,
        transitionDuration: Mo.fade,
        pageBuilder: (context, _, __) => _Panel(
          controller: controller,
          onAdd: onAdd,
          onProfileSettings: onProfileSettings,
        ),
        transitionBuilder: (context, anim, _, child) => FadeTransition(
          opacity: anim,
          child: SlideTransition(
            position: Tween(begin: const Offset(0, -0.02), end: Offset.zero)
                .animate(CurvedAnimation(parent: anim, curve: Mo.curve)),
            child: child,
          ),
        ),
      );
}

class _Panel extends StatefulWidget {
  const _Panel({
    required this.controller,
    required this.onAdd,
    required this.onProfileSettings,
  });

  final ProfilesController controller;
  final VoidCallback onAdd;
  final void Function(WaProfile) onProfileSettings;

  @override
  State<_Panel> createState() => _PanelState();
}

class _PanelState extends State<_Panel> {
  String? _menuFor;

  @override
  Widget build(BuildContext context) {
    final top = MediaQuery.paddingOf(context).top + 54;
    return Stack(
      children: [
        Positioned(
          top: top,
          right: 10,
          child: Material(
            color: Colors.transparent,
            child: Container(
              width: 312,
              constraints: const BoxConstraints(maxHeight: 620),
              decoration: BoxDecoration(
                color: Sk.body,
                border: Border.all(color: Sk.line4),
                borderRadius: BorderRadius.circular(16),
                boxShadow: const [
                  BoxShadow(
                      color: Color(0x9908090A),
                      blurRadius: 60,
                      offset: Offset(0, 26)),
                ],
              ),
              clipBehavior: Clip.antiAlias,
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Container(
                    padding: const EdgeInsets.fromLTRB(14, 12, 10, 12),
                    decoration: const BoxDecoration(
                      border: Border(bottom: BorderSide(color: Sk.line2)),
                    ),
                    child: Row(
                      children: [
                        Expanded(
                          child: Text('Profiles',
                              style: T.s(13.5,
                                  weight: FontWeight.w700, tracking: -0.1)),
                        ),
                      ],
                    ),
                  ),
                  Flexible(
                    child: ValueListenableBuilder<List<WaProfile>>(
                      valueListenable: widget.controller.profiles,
                      builder: (context, list, _) =>
                          ValueListenableBuilder<String?>(
                        valueListenable: widget.controller.activeId,
                        builder: (context, activeId, __) => ListView.builder(
                          shrinkWrap: true,
                          padding: const EdgeInsets.all(6),
                          itemCount: list.length,
                          itemBuilder: (context, i) => _Row(
                            controller: widget.controller,
                            profile: list[i],
                            active: list[i].id == activeId,
                            menuOpen: _menuFor == list[i].id,
                            onToggleMenu: () => setState(() =>
                                _menuFor = _menuFor == list[i].id
                                    ? null
                                    : list[i].id),
                            onOpen: () {
                              widget.controller.switchTo(list[i].id);
                              Navigator.of(context).pop();
                            },
                            onSettings: () {
                              Navigator.of(context).pop();
                              widget.onProfileSettings(list[i]);
                            },
                          ),
                        ),
                      ),
                    ),
                  ),
                  InkWell(
                    onTap: () {
                      Navigator.of(context).pop();
                      widget.onAdd();
                    },
                    child: Container(
                      padding: const EdgeInsets.symmetric(
                          horizontal: 14, vertical: 12),
                      decoration: const BoxDecoration(
                        border: Border(top: BorderSide(color: Sk.line2)),
                      ),
                      child: Row(
                        children: [
                          CustomPaint(
                            painter: const DashedBorderPaint(
                                color: Sk.line8, radius: 11, width: 1.5),
                            child: const SizedBox(
                              width: 34,
                              height: 34,
                              child: Center(
                                  child: Glyph(Ic.plus,
                                      size: 15,
                                      viewBox: 16,
                                      color: Sk.soft,
                                      stroke: 1.8)),
                            ),
                          ),
                          const SizedBox(width: 11),
                          Expanded(
                            child: Text('Add profile',
                                style: T.s(14,
                                    weight: FontWeight.w600,
                                    tracking: -0.15,
                                    color: Sk.soft)),
                          ),
                          const Glyph(Ic.arrowRight,
                              size: 16, color: Sk.soft, stroke: 2),
                        ],
                      ),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
      ],
    );
  }
}

class _Row extends StatelessWidget {
  const _Row({
    required this.controller,
    required this.profile,
    required this.active,
    required this.menuOpen,
    required this.onToggleMenu,
    required this.onOpen,
    required this.onSettings,
  });

  final ProfilesController controller;
  final WaProfile profile;
  final bool active;
  final bool menuOpen;
  final VoidCallback onToggleMenu;
  final VoidCallback onOpen;
  final VoidCallback onSettings;

  @override
  Widget build(BuildContext context) {
    final color = ProfileColors.at(profile.color);
    return Column(
      children: [
        Material(
          color: active ? Sk.rowHover : Colors.transparent,
          borderRadius: BorderRadius.circular(12),
          child: InkWell(
            onTap: onOpen,
            borderRadius: BorderRadius.circular(12),
            child: Padding(
              padding: const EdgeInsets.fromLTRB(9, 9, 6, 9),
              child: Row(
                children: [
                  ProfileMark(
                      color: color,
                      size: 34,
                      radius: 11,
                      glyphSize: 18,
                      photo: controller.avatar(profile.id)),
                  const SizedBox(width: 11),
                  Expanded(
                    child: Text(profile.name,
                        maxLines: 1,
                        overflow: TextOverflow.ellipsis,
                        style: T.s(14,
                            weight: FontWeight.w600, tracking: -0.15)),
                  ),
                  ValueListenableBuilder<WaRuntime>(
                    valueListenable: controller.runtime(profile.id),
                    builder: (context, s, _) =>
                        (s.unread > 0 && !profile.muted)
                            ? Padding(
                                padding: const EdgeInsets.only(right: 4),
                                child: UnreadPill(s.unread,
                                    size: 18, fontSize: 10),
                              )
                            : const SizedBox.shrink(),
                  ),
                  InkResponse(
                    onTap: onToggleMenu,
                    radius: 18,
                    child: const SizedBox(
                      width: 28,
                      height: 28,
                      child: Center(
                          child: Glyph(Ic.dots,
                              size: 16, color: Sk.iconDim, stroke: 2)),
                    ),
                  ),
                ],
              ),
            ),
          ),
        ),
        if (menuOpen)
          _RowMenu(
            controller: controller,
            profile: profile,
            onSettings: onSettings,
          ),
      ],
    );
  }
}

class _RowMenu extends StatelessWidget {
  const _RowMenu({
    required this.controller,
    required this.profile,
    required this.onSettings,
  });

  final ProfilesController controller;
  final WaProfile profile;
  final VoidCallback onSettings;

  @override
  Widget build(BuildContext context) {
    return Container(
      margin: const EdgeInsets.fromLTRB(40, 2, 4, 6),
      padding: const EdgeInsets.all(5),
      decoration: BoxDecoration(
        color: Sk.menu,
        border: Border.all(color: Sk.line7),
        borderRadius: BorderRadius.circular(12),
      ),
      child: Column(
        children: [
          _MenuItem(
            icon: Ic.gear,
            label: 'Profile settings',
            onTap: onSettings,
          ),
          _MenuItem(
            icon: Ic.reload,
            label: 'Reload page',
            onTap: () {
              controller.reload(profile.id);
              Navigator.of(context).pop();
            },
          ),
          ValueListenableBuilder<WaRuntime>(
            valueListenable: controller.runtime(profile.id),
            builder: (context, s, _) => _MenuItem(
              icon: Ic.tab,
              label: s.live ? 'Close session' : 'Open session',
              onTap: () {
                if (s.live) {
                  controller.sleep(profile.id);
                } else {
                  controller.switchTo(profile.id);
                }
                Navigator.of(context).pop();
              },
            ),
          ),
          _MenuItem(
            icon: Ic.trash,
            label: 'Delete profile',
            color: Sk.danger,
            onTap: () {
              Navigator.of(context).pop();
              controller.remove(profile.id);
            },
          ),
        ],
      ),
    );
  }
}

class _MenuItem extends StatelessWidget {
  const _MenuItem({
    required this.icon,
    required this.label,
    required this.onTap,
    this.color = Sk.soft,
  });

  final String icon;
  final String label;
  final VoidCallback onTap;
  final Color color;

  @override
  Widget build(BuildContext context) => InkWell(
        onTap: onTap,
        borderRadius: BorderRadius.circular(9),
        child: Padding(
          padding: const EdgeInsets.all(9),
          child: Row(
            children: [
              Glyph(icon,
                  size: 15,
                  viewBox: icon == Ic.trash
                      ? 16
                      : icon == Ic.tab
                          ? 18
                          : 24,
                  color: color,
                  stroke: 1.9),
              const SizedBox(width: 9),
              Expanded(
                child: Text(label,
                    style: T.s(12.5,
                        weight: FontWeight.w600,
                        tracking: -0.1,
                        color: color)),
              ),
            ],
          ),
        ),
      );
}

/// Painter form of the dashed outline, for use inside a widget tree.
class DashedBorderPaint extends CustomPainter {
  const DashedBorderPaint({
    required this.color,
    required this.radius,
    this.width = 1.5,
  });

  final Color color;
  final double radius;
  final double width;

  @override
  void paint(Canvas canvas, Size size) {
    final paint = Paint()
      ..color = color
      ..style = PaintingStyle.stroke
      ..strokeWidth = width;
    final path = Path()
      ..addRRect(RRect.fromRectAndRadius(
          Offset.zero & size, Radius.circular(radius)));
    for (final m in path.computeMetrics()) {
      var d = 0.0;
      while (d < m.length) {
        final n = (d + 5).clamp(0.0, m.length);
        canvas.drawPath(m.extractPath(d, n), paint);
        d = n + 4;
      }
    }
  }

  @override
  bool shouldRepaint(DashedBorderPaint old) => old.color != color;
}
