import 'package:flutter/material.dart';

import '../design/tokens.dart';
import '../models/profile.dart';
import '../state/profiles_controller.dart';
import 'parts.dart';

/// `addOpen`: link a new profile.
class AddProfileSheet extends StatefulWidget {
  const AddProfileSheet({super.key, required this.controller});

  final ProfilesController controller;

  static Future<WaProfile?> show(
          BuildContext context, ProfilesController controller) =>
      showModalBottomSheet<WaProfile>(
        context: context,
        isScrollControlled: true,
        backgroundColor: Colors.transparent,
        barrierColor: Sk.scrim,
        builder: (_) => AddProfileSheet(controller: controller),
      );

  @override
  State<AddProfileSheet> createState() => _AddProfileSheetState();
}

class _AddProfileSheetState extends State<AddProfileSheet> {
  final _name = TextEditingController();
  late int _color =
      widget.controller.profiles.value.length % ProfileColors.values.length;
  bool _phone = true;

  @override
  void dispose() {
    _name.dispose();
    super.dispose();
  }

  void _create() => Navigator.of(context).pop(
        WaProfile(
          id: '',
          name: _name.text,
          color: _color,
          layout: _phone ? WaLayout.phone : WaLayout.desktop,
        ),
      );

  @override
  Widget build(BuildContext context) => DesignSheet(
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          mainAxisSize: MainAxisSize.min,
          children: [
            Text('Add a profile',
                style: T.s(18, weight: FontWeight.w700, tracking: -0.3)),
            const SizedBox(height: 16),
            DesignField(
              controller: _name,
              hint: 'Name for Profile',
              autofocus: true,
              height: 36,
              fontSize: 14,
              onSubmitted: (_) => _create(),
            ),
            const SizedBox(height: 14),
            const FieldLabel('Profile colour'),
            const SizedBox(height: 8),
            ColorPicker(
                value: _color, onChanged: (v) => setState(() => _color = v)),
            const SizedBox(height: 12),
            Container(
              decoration:
                  const BoxDecoration(border: Border(top: BorderSide(color: Sk.lineSoft))),
              child: ToggleRow(
                label: 'Phone layout',
                hint: 'One pane at a time, like the mobile app.',
                value: _phone,
                onChanged: (v) => setState(() => _phone = v),
                padding: const EdgeInsets.symmetric(vertical: 12),
              ),
            ),
            const SizedBox(height: 18),
            Row(
              children: [
                GhostButton(
                    label: 'Cancel',
                    onTap: () => Navigator.of(context).pop()),
                const SizedBox(width: 10),
                Expanded(
                  child: AccentButton(
                    label: 'Link profile',
                    onTap: _create,
                    trailing: const Glyph(Ic.arrowRight,
                        size: 18, color: Sk.onAccent, stroke: 2.2),
                  ),
                ),
              ],
            ),
          ],
        ),
      );
}
