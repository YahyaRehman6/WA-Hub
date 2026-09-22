import 'package:flutter/material.dart';

import '../design/tokens.dart';
import '../models/profile.dart';
import '../state/profiles_controller.dart';
import 'parts.dart';

/// `psOpen`: profile settings, as a bottom sheet.
class ProfileSheet extends StatefulWidget {
  const ProfileSheet({
    super.key,
    required this.controller,
    required this.profile,
  });

  final ProfilesController controller;
  final WaProfile profile;

  static Future<void> show(
    BuildContext context,
    ProfilesController controller,
    WaProfile profile,
  ) =>
      showModalBottomSheet<void>(
        context: context,
        isScrollControlled: true,
        backgroundColor: Colors.transparent,
        barrierColor: Sk.scrim,
        builder: (_) => ProfileSheet(controller: controller, profile: profile),
      );

  @override
  State<ProfileSheet> createState() => _ProfileSheetState();
}

class _ProfileSheetState extends State<ProfileSheet> {
  late final TextEditingController _name =
      TextEditingController(text: widget.profile.name);
  late int _color = widget.profile.color;
  late bool _phone = widget.profile.layout.isPhone;
  late bool _keep = widget.profile.keepLoaded;
  late bool _muted = widget.profile.muted;

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  Future<void> _save() async {
    await widget.controller.update(widget.profile.copyWith(
      name: _name.text.trim().isEmpty ? widget.profile.name : _name.text.trim(),
      color: _color,
      layout: _phone ? WaLayout.phone : WaLayout.desktop,
      keepLoaded: _keep,
      muted: _muted,
    ));
    if (mounted) Navigator.of(context).pop();
  }

  @override
  Widget build(BuildContext context) {
    final color = ProfileColors.at(_color);
    final runtime = widget.controller.runtime(widget.profile.id);
    return DesignSheet(
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        mainAxisSize: MainAxisSize.min,
        children: [
          Row(
            children: [
              ProfileMark(
                  color: color,
                  size: 42,
                  radius: 13,
                  glyphSize: 22,
                  photo: widget.controller.avatar(widget.profile.id)),
              const SizedBox(width: 12),
              Expanded(
                child: Text('Profile settings',
                    style:
                        T.s(17, weight: FontWeight.w700, tracking: -0.3)),
              ),
              ValueListenableBuilder<WaRuntime>(
                valueListenable: runtime,
                builder: (context, s, _) => Container(
                  padding:
                      const EdgeInsets.symmetric(horizontal: 7, vertical: 4),
                  decoration: BoxDecoration(
                    color: s.live ? const Color(0xFF16261F) : Sk.line,
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text(
                    s.stateLabel.toUpperCase(),
                    style: T.s(9.5,
                        weight: FontWeight.w700,
                        tracking: 0.7,
                        color: s.live ? Sk.accent : Sk.dim),
                  ),
                ),
              ),
            ],
          ),
          const SizedBox(height: 16),
          const FieldLabel('Name'),
          const SizedBox(height: 5),
          DesignField(controller: _name, hint: 'Profile name'),
          const SizedBox(height: 14),
          const FieldLabel('Profile colour'),
          const SizedBox(height: 8),
          ColorPicker(
              value: _color, onChanged: (v) => setState(() => _color = v)),
          const SizedBox(height: 6),
          ToggleRow(
            label: 'Phone layout',
            hint: 'One pane at a time, sized to this screen. Off shows the '
                'desktop two-pane page, zoomed to fit.',
            value: _phone,
            onChanged: (v) => setState(() => _phone = v),
          ),
          ToggleRow(
            label: 'Keep loaded',
            hint: 'Never close this session to reclaim memory.',
            value: _keep,
            onChanged: (v) => setState(() => _keep = v),
          ),
          ToggleRow(
            label: 'Mute notifications',
            hint: 'Silence this profile without touching the others.',
            value: _muted,
            onChanged: (v) => setState(() => _muted = v),
          ),
          const SizedBox(height: 18),
          Row(
            children: [
              GhostButton(
                label: 'Delete',
                color: Sk.danger,
                borderColor: Sk.dangerLine,
                leading: const Glyph(Ic.trash,
                    size: 15, viewBox: 16, color: Sk.danger, stroke: 1.6),
                onTap: () async {
                  Navigator.of(context).pop();
                  await widget.controller.remove(widget.profile.id);
                },
              ),
              const SizedBox(width: 10),
              Expanded(child: AccentButton(label: 'Done', onTap: _save)),
            ],
          ),
        ],
      ),
    );
  }
}
