import 'dart:async';
import 'package:flutter/foundation.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import '../design/tokens.dart';
import '../models/profile.dart';
import '../native/wa_stage.dart';
import '../state/profiles_controller.dart';
import 'add_sheet.dart';
import 'parts.dart';
import 'profile_sheet.dart';
import 'profiles_panel.dart';
import 'sessions_grid.dart';

/// The `Profile Shell` artboard: a header, the web session viewport, and the
/// overlays that manage profiles.
class Shell extends StatefulWidget {
  const Shell({super.key, required this.controller});

  final ProfilesController controller;

  @override
  State<Shell> createState() => _ShellState();
}

class _ShellState extends State<Shell> {
  ProfilesController get _c => widget.controller;

  /// Built once. Rebuilding it would tear down every logged-in session.
  static const _stage = WaStage();

  @override
  void initState() {
    super.initState();
    _c.notice.addListener(_showNotice);
  }

  @override
  void dispose() {
    _c.notice.removeListener(_showNotice);
    super.dispose();
  }

  void _showNotice() {
    final notice = _c.notice.value;
    if (notice == null || notice.isEmpty || !mounted) return;
    ScaffoldMessenger.of(context)
      ..clearSnackBars()
      ..showSnackBar(
        SnackBar(
          content: Text(notice, style: T.s(13.5)),
          backgroundColor: Sk.menu,
          behavior: SnackBarBehavior.floating,
          elevation: 0,
          margin: const EdgeInsets.fromLTRB(16, 0, 16, 22),
          shape: RoundedRectangleBorder(
            borderRadius: BorderRadius.circular(12),
            side: const BorderSide(color: Sk.line4),
          ),
          duration: const Duration(seconds: 3),
        ),
      );
    _c.notice.value = null;
  }

  Future<void> _addProfile() async {
    final draft = await AddProfileSheet.show(context, _c);
    if (draft != null) await _c.add(draft);
  }

  Future<void> _openProfileSettings(WaProfile profile) =>
      ProfileSheet.show(context, _c, profile);

  void _openSessions() {
    // The cards show the pages themselves, so snapshot them as the switcher
    // opens — the one moment the pictures are worth their cost.
    _c.captureThumbnails();
    Navigator.of(context).push(
      SessionsGrid.route(controller: _c, onAdd: _addProfile),
    );
  }

  void _openPanel() => ProfilesPanel.show(
        context,
        _c,
        onAdd: _addProfile,
        onProfileSettings: _openProfileSettings,
      );


  @override
  Widget build(BuildContext context) {
    return ValueListenableBuilder<bool>(
      valueListenable: _c.ready,
      builder: (context, ready, _) {
        if (!ready) return const ColoredBox(color: Sk.ink, child: SizedBox());

        return ValueListenableBuilder<List<WaProfile>>(
          valueListenable: _c.profiles,
          builder: (context, list, _) => ValueListenableBuilder<String?>(
            valueListenable: _c.activeId,
            builder: (context, activeId, _) {
              final profile = _c.byId(activeId ?? '');
              final runtime =
                  profile == null ? null : _c.runtime(profile.id);

              return PopScope(
                canPop: false,
                onPopInvokedWithResult: (didPop, _) async {
                  if (didPop || profile == null) {
                    if (!didPop) await SystemNavigator.pop();
                    return;
                  }
                  // Back means what it means on a phone: leave the
                  // conversation, then the page, then the app.
                  // The page decides first: it dismisses an open dialog, then
                  // an open conversation. Only when it had nothing to close
                  // does back fall through to page history, then the app.
                  if (await _c.closeChat(profile.id)) return;
                  if (!await _c.goBack(profile.id)) {
                    await SystemNavigator.pop();
                  }
                },
                child: Scaffold(
                  // The stage resizes once, when the keyboard has settled —
                  // see _SettledInset — not on every frame of its animation.
                  resizeToAvoidBottomInset: false,
                  backgroundColor: Sk.ink,
                  body: _SettledInset(
                    child: SafeArea(
                    bottom: false,
                    child: Column(
                      children: [
                        _Header(
                          controller: _c,
                          profile: profile,
                          onReload: profile == null
                              ? null
                              : () => _c.reload(profile.id),
                          onSessions: _openSessions,
                          onPanel: _openPanel,
                        ),
                        Expanded(
                          child: Container(
                            color: Sk.body,
                            child: Column(
                              children: [
                                _LoadRule(runtime: runtime),
                                Expanded(
                                  child: DecoratedBox(
                                    decoration: const BoxDecoration(
                                      color: Sk.stage,
                                      border: Border(
                                          top: BorderSide(color: Sk.line)),
                                    ),
                                    child: _Stage(
                                      controller: _c,
                                      profile: profile,
                                      runtime: runtime,
                                      stage: _stage,
                                      onSessions: _openSessions,
                                      onAdd: _addProfile,
                                    ),
                                  ),
                                ),
                              ],
                            ),
                          ),
                        ),
                        ValueListenableBuilder<bool>(
                          valueListenable: _c.notificationsAllowed,
                          builder: (context, allowed, _) => allowed
                              ? const SizedBox.shrink()
                              : _NotificationsOff(
                                  onFix: _c.openNotificationSettings),
                        ),
                        SafeArea(top: false, child: const SizedBox(height: 4)),
                      ],
                    ),
                  ),
                  ),
                ),
              );
            },
          ),
        );
      },
    );
  }
}

/// `flex: 0 0 52px; padding: 0 8px 0 16px`
class _Header extends StatelessWidget {
  const _Header({
    required this.controller,
    required this.profile,
    required this.onReload,
    required this.onSessions,
    required this.onPanel,
  });

  final ProfilesController controller;
  final WaProfile? profile;
  final VoidCallback? onReload;
  final VoidCallback onSessions;
  final VoidCallback onPanel;

  @override
  Widget build(BuildContext context) {
    return SizedBox(
      height: 58,
      child: Padding(
        padding: const EdgeInsets.only(left: 6, right: 8),
        child: Row(
          children: [
            // Back, ahead of the identity: closes what the page has open — a
            // viewer, a drawer, the conversation — and stops there rather than
            // leaving the app.
            GestureDetector(
              behavior: HitTestBehavior.opaque,
              onTap: profile == null
                  ? null
                  : () => controller.closeChat(profile!.id),
              child: const SizedBox(
                width: 34,
                height: 46,
                child: Center(
                  child: Glyph(Ic.chevronLeft,
                      size: 17, viewBox: 18, color: Sk.soft, stroke: 1.9),
                ),
              ),
            ),
            // App icon: 30x30, radius 9, artwork at 138% so it fills the tile.
            ClipRRect(
              borderRadius: BorderRadius.circular(9),
              child: SizedBox(
                width: 30,
                height: 30,
                child: OverflowBox(
                  maxWidth: 30 * 1.38,
                  maxHeight: 30 * 1.38,
                  child: Image.asset(
                    'assets/brand/app-icon.png',
                    width: 30 * 1.38,
                    height: 30 * 1.38,
                    fit: BoxFit.cover,
                    filterQuality: FilterQuality.medium,
                  ),
                ),
              ),
            ),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                profile?.name ?? 'No profile open',
                maxLines: 1,
                overflow: TextOverflow.ellipsis,
                style: T.s(15.5, weight: FontWeight.w700, tracking: -0.3),
              ),
            ),
            IconSlot(
              tooltip: 'Reload',
              onTap: onReload ?? () {},
              child: const Glyph(Ic.reload, size: 18, color: Sk.icon),
            ),
            IconSlot(
              tooltip: 'Open sessions',
              onTap: onSessions,
              child: Container(
                width: 24,
                height: 24,
                alignment: Alignment.center,
                decoration: BoxDecoration(
                  border: Border.all(color: Sk.icon, width: 1.8),
                  borderRadius: BorderRadius.circular(7),
                ),
                child: ValueListenableBuilder<List<String>>(
                  valueListenable: controller.liveIds,
                  builder: (context, open, _) => Text('${open.length}',
                      style: T.m(12,
                          weight: FontWeight.w700, color: Sk.icon)),
                ),
              ),
            ),
            IconSlot(
              tooltip: 'Profiles',
              onTap: onPanel,
              child: Stack(
                clipBehavior: Clip.none,
                children: [
                  const Glyph(Ic.profiles, size: 23, color: Sk.icon),
                  Positioned(
                    top: -4,
                    right: -7,
                    child: ValueListenableBuilder<int>(
                      valueListenable: controller.totalUnread,
                      builder: (context, total, _) =>
                          UnreadPill(total,
                              size: 15, fontSize: 9, border: Sk.ink),
                    ),
                  ),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }
}

/// `flex: 0 0 2px; background: #1B1E22` with a 36% accent bar while loading.
class _LoadRule extends StatefulWidget {
  const _LoadRule({required this.runtime});

  final ValueListenable<WaRuntime>? runtime;

  @override
  State<_LoadRule> createState() => _LoadRuleState();
}

class _LoadRuleState extends State<_LoadRule>
    with SingleTickerProviderStateMixin {
  late final AnimationController _anim = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1100),
  );

  @override
  void dispose() {
    _anim.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final runtime = widget.runtime;
    if (runtime == null) return const SizedBox(height: 2, child: ColoredBox(color: Sk.line));
    return SizedBox(
      height: 2,
      child: ValueListenableBuilder<WaRuntime>(
        valueListenable: runtime,
        builder: (context, state, _) {
          if (!state.loading) {
            _anim.stop();
            return const ColoredBox(color: Sk.line);
          }
          if (!_anim.isAnimating) _anim.repeat();
          return ColoredBox(
            color: Sk.line,
            child: AnimatedBuilder(
              animation: _anim,
              builder: (context, __) => FractionallySizedBox(
                alignment: Alignment(_anim.value * 2 - 1, 0),
                widthFactor: 0.36,
                child: const ColoredBox(color: Sk.accent),
              ),
            ),
          );
        },
      ),
    );
  }
}

/// The web session viewport and the states that replace it.
class _Stage extends StatelessWidget {
  const _Stage({
    required this.controller,
    required this.profile,
    required this.runtime,
    required this.stage,
    required this.onSessions,
    required this.onAdd,
  });

  final ProfilesController controller;
  final WaProfile? profile;
  final ValueListenable<WaRuntime>? runtime;
  final Widget stage;
  final VoidCallback onSessions;
  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) {
    if (profile == null) return _NoProfile(onSessions: onSessions, onAdd: onAdd);
    return ValueListenableBuilder<WaRuntime>(
      valueListenable: runtime!,
      builder: (context, state, child) {
        if (state.error != null) {
          return _Unlinked(
            note: state.error!,
            onRelink: () => controller.reload(profile!.id),
          );
        }
        return Stack(
          children: [
            Positioned.fill(child: RepaintBoundary(child: child!)),
            if (state.loading && !state.booted)
              const Positioned.fill(child: _Connecting()),
          ],
        );
      },
      child: stage,
    );
  }
}

class _Connecting extends StatefulWidget {
  const _Connecting();

  @override
  State<_Connecting> createState() => _ConnectingState();
}

class _ConnectingState extends State<_Connecting>
    with SingleTickerProviderStateMixin {
  late final AnimationController _a = AnimationController(
    vsync: this,
    duration: const Duration(milliseconds: 1200),
  )..repeat();

  @override
  void dispose() {
    _a.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => ColoredBox(
        color: Sk.stage,
        child: Center(
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              Row(
                mainAxisSize: MainAxisSize.min,
                children: [
                  for (var i = 0; i < 3; i++) ...[
                    AnimatedBuilder(
                      animation: _a,
                      builder: (context, _) {
                        final t = (_a.value - i * 0.167) % 1.0;
                        final o = 0.35 + 0.65 * (1 - (t * 2 - 1).abs());
                        return Opacity(
                          opacity: o.clamp(0.0, 1.0),
                          child: Container(
                            width: 7,
                            height: 7,
                            decoration: BoxDecoration(
                              color: Sk.accent,
                              borderRadius: BorderRadius.circular(4),
                            ),
                          ),
                        );
                      },
                    ),
                    if (i != 2) const SizedBox(width: 6),
                  ],
                ],
              ),
              const SizedBox(height: 14),
              Text('opening web session',
                  style: T.m(11, tracking: 0.5, color: Sk.dim)),
            ],
          ),
        ),
      );
}

class _Unlinked extends StatelessWidget {
  const _Unlinked({required this.note, required this.onRelink});

  final String note;
  final VoidCallback onRelink;

  @override
  Widget build(BuildContext context) => ColoredBox(
        color: Sk.stage,
        child: Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 26),
            child: Container(
              padding: const EdgeInsets.all(20),
              decoration: BoxDecoration(
                color: const Color(0xFF16191D),
                border: Border.all(color: Sk.line3),
                borderRadius: BorderRadius.circular(16),
              ),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text('Session needs re-linking',
                      textAlign: TextAlign.center,
                      style:
                          T.s(15.5, weight: FontWeight.w700, tracking: -0.2)),
                  const SizedBox(height: 6),
                  Text(
                    note.isEmpty ? 'The web session dropped.' : note,
                    textAlign: TextAlign.center,
                    style: T.s(12.5, height: 1.5, color: Sk.muted),
                  ),
                  const SizedBox(height: 16),
                  SizedBox(
                    width: double.infinity,
                    child: AccentButton(
                        label: 'Reload session',
                        height: 42,
                        fontSize: 14,
                        onTap: onRelink),
                  ),
                ],
              ),
            ),
          ),
        ),
      );
}

class _NoProfile extends StatelessWidget {
  const _NoProfile({required this.onSessions, required this.onAdd});

  final VoidCallback onSessions;
  final VoidCallback onAdd;

  @override
  Widget build(BuildContext context) => ColoredBox(
        color: Sk.stage,
        child: Center(
          child: Padding(
            padding: const EdgeInsets.symmetric(horizontal: 40),
            child: Column(
              mainAxisSize: MainAxisSize.min,
              children: [
                Text('No profile open',
                    style: T.s(16, weight: FontWeight.w700)),
                const SizedBox(height: 6),
                Text(
                  'Open one from the grid, or link a new profile.',
                  textAlign: TextAlign.center,
                  style: T.s(13, height: 1.5, color: Sk.muted),
                ),
                const SizedBox(height: 16),
                GhostButton(
                    label: 'Link a profile', height: 40, onTap: onAdd),
              ],
            ),
          ),
        ),
      );
}

class _NotificationsOff extends StatelessWidget {
  const _NotificationsOff({required this.onFix});

  final VoidCallback onFix;

  @override
  Widget build(BuildContext context) => Container(
        width: double.infinity,
        color: Sk.menu,
        padding: const EdgeInsets.fromLTRB(16, 10, 8, 10),
        child: Row(
          children: [
            Expanded(
              child: Text('Notifications are turned off for this app',
                  style: T.s(12.5, color: Sk.muted)),
            ),
            TextButton(
              onPressed: onFix,
              child: Text('Turn on',
                  style: T.s(13, weight: FontWeight.w600, color: Sk.accent)),
            ),
          ],
        ),
      );
}


/// Pads the shell for the keyboard, but only once the keyboard has stopped
/// moving. Android reports the inset on every frame of the keyboard animation;
/// resizing the WebView on each of them made WhatsApp re-lay out its whole
/// page a dozen times per keyboard — the lag felt on every composer tap. One
/// resize at the end is the whole cost.
class _SettledInset extends StatefulWidget {
  const _SettledInset({required this.child});
  final Widget child;

  @override
  State<_SettledInset> createState() => _SettledInsetState();
}

class _SettledInsetState extends State<_SettledInset> {
  double _committed = 0;
  Timer? _settle;

  @override
  void dispose() {
    _settle?.cancel();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    final inset = MediaQuery.viewInsetsOf(context).bottom;
    if (inset != _committed) {
      _settle?.cancel();
      _settle = Timer(const Duration(milliseconds: 55), () {
        if (mounted) setState(() => _committed = inset);
      });
    }
    return Padding(
      padding: EdgeInsets.only(bottom: _committed),
      child: widget.child,
    );
  }
}
