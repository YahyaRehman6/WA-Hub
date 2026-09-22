import 'dart:async';
import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:shared_preferences/shared_preferences.dart';

import '../models/profile.dart';
import '../native/wa_bridge.dart';

/// Owns the account list, which one is on screen, and what each one is doing.
///
/// Deliberately *not* one big ChangeNotifier. A loading page emits a progress
/// event several times a second, and a single notifier turned each of those
/// into a rebuild of the whole screen — the rail, the bars, everything — which
/// is what made switching and loading feel heavy. State is split so a progress
/// tick repaints the two-pixel loading rule and nothing else.
class ProfilesController {
  ProfilesController(this._bridge) {
    _bridge.listen(_onEvent);
    _bridge.onLaunchProfile(switchTo);
  }

  static const _profilesKey = 'profiles.v1';
  static const _activeKey = 'profiles.active.v1';
  static const _avatarKey = 'profiles.avatar.v1.';
  static const _batteryAskedKey = 'profiles.battery.asked.v1';
  static const _layoutMigratedKey = 'profiles.layout.migrated.v2';

  final WaBridge _bridge;
  SharedPreferences? _prefs;

  final profiles = ValueNotifier<List<WaProfile>>(const []);
  final activeId = ValueNotifier<String?>(null);
  final ready = ValueNotifier<bool>(false);

  /// Set, shown once, then cleared. Not a queue — a burst of failures should
  /// not become a burst of toasts.
  final notice = ValueNotifier<String?>(null);

  final _runtimes = <String, ValueNotifier<WaRuntime>>{};
  final _thumbnails = <String, ValueNotifier<Uint8List?>>{};

  /// Each account's own WhatsApp profile photo, lifted out of the live session.
  final _avatars = <String, ValueNotifier<Uint8List?>>{};

  WaCapabilities? capabilities;

  /// False when Android is dropping our notifications on the floor. Surfaced
  /// in the shell, because a silent failure here just looks like the app is
  /// broken.
  final notificationsAllowed = ValueNotifier<bool>(true);


  /// Sum of every profile's unread count, for the header badge.
  final totalUnread = ValueNotifier<int>(0);

  /// Ids of the sessions that are actually open, in profile order.
  ///
  /// The tab counter and the sessions grid show these and nothing else — a
  /// profile you have not opened is not a tab, it is something you can open
  /// from the profiles list.
  final liveIds = ValueNotifier<List<String>>(const []);

  /// Per-account state. Created on demand so a widget can listen before the
  /// account has ever reported anything.
  ValueNotifier<WaRuntime> runtime(String id) =>
      _runtimes.putIfAbsent(id, () => ValueNotifier(const WaRuntime()));

  ValueNotifier<Uint8List?> thumbnail(String id) =>
      _thumbnails.putIfAbsent(id, () => ValueNotifier(null));

  ValueNotifier<Uint8List?> avatar(String id) =>
      _avatars.putIfAbsent(id, () => ValueNotifier(null));

  WaProfile? byId(String id) {
    for (final p in profiles.value) {
      if (p.id == id) return p;
    }
    return null;
  }

  WaProfile? get active {
    final id = activeId.value;
    return id == null ? null : byId(id);
  }

  // ---- Startup ---------------------------------------------------------

  Future<void> load() async {
    capabilities = await _bridge.capabilities();
    _prefs = await SharedPreferences.getInstance();

    final raw = _prefs!.getString(_profilesKey);
    if (raw != null) {
      try {
        final decoded = jsonDecode(raw) as List<Object?>;
        profiles.value = decoded
            .map((e) => WaProfile.fromJson((e! as Map).cast<String, Object?>()))
            .toList(growable: false);
      } on Object catch (_) {
        // A corrupt record must not brick the app on every launch. Better to
        // start empty than to fail to boot.
        profiles.value = const [];
      }
    }

    // A notification tap wins over whatever was last on screen.
    final launched = await _bridge.consumeLaunchProfile();
    final stored = _prefs!.getString(_activeKey);
    String? pick;
    for (final candidate in [launched, stored]) {
      if (candidate != null && byId(candidate) != null) {
        pick = candidate;
        break;
      }
    }
    activeId.value =
        pick ?? (profiles.value.isEmpty ? null : profiles.value.first.id);

    // One-time correction. An earlier migration mapped the old compact /
    // balanced / wide values to desktop, and that got persisted — so profiles
    // the user never chose desktop for were rendering the desktop page. The
    // phone shell is the product; everything goes back to it, once.
    if (!(_prefs!.getBool(_layoutMigratedKey) ?? false)) {
      profiles.value = [
        for (final p in profiles.value) p.copyWith(layout: WaLayout.phone),
      ];
      await _persist();
      await _prefs!.setBool(_layoutMigratedKey, true);
    }

    for (final profile in profiles.value) {
      final cached = _prefs!.getString('$_avatarKey${profile.id}');
      if (cached == null) continue;
      try {
        avatar(profile.id).value = base64Decode(cached);
      } on Object catch (_) {
        await _prefs!.remove('$_avatarKey${profile.id}');
      }
    }

    ready.value = true;

    final current = active;
    if (current != null) await _bridge.attach(current);

    // Accounts added before this app asked for POST_NOTIFICATIONS never
    // triggered the prompt, so their notifications failed silently. Ask once
    // there is actually something to be notified about.
    if (profiles.value.isNotEmpty) {
      await _bridge.requestNotifications();
      await refreshNotificationPermission();
      await _ensureBatteryExemption();
    }

  }


  /// Asks the OS to stop optimising this app away. Asked once at launch if
  /// never granted, and again whenever a profile is added, because that is
  /// the moment the process is about to be backgrounded mid-handshake.
  Future<void> _ensureBatteryExemption({bool force = false}) async {
    if (await _bridge.isBatteryExempt()) return;
    final asked = _prefs?.getBool(_batteryAskedKey) ?? false;
    if (asked && !force) return;
    await _prefs?.setBool(_batteryAskedKey, true);
    await _bridge.requestBatteryExemption();
  }

  Future<void> refreshNotificationPermission() async {
    notificationsAllowed.value = await _bridge.notificationsAllowed();
  }

  Future<void> openNotificationSettings() => _bridge.openNotificationSettings();

  // ---- Switching -------------------------------------------------------

  Future<void> switchTo(String id) async {
    final profile = byId(id);
    if (profile == null || activeId.value == id) return;
    activeId.value = id;
    // Written straight away: the process can be killed at any moment and the
    // next launch should still open the account you were last in.
    await _prefs?.setString(_activeKey, id);
    await _bridge.attach(profile);
    _recountLive();
  }

  /// Snapshots every live account. Called when the switcher opens, and only
  /// then: drawing a WebView into a bitmap is expensive, and doing it on every
  /// switch made switching stutter for a picture nobody was looking at.
  Future<void> captureThumbnails() async {
    for (final profile in profiles.value) {
      if (!runtime(profile.id).value.live) continue;
      final bytes = await _bridge.thumbnail(profile.id);
      if (bytes != null && bytes.isNotEmpty) {
        thumbnail(profile.id).value = bytes;
      }
    }
  }

  // ---- Editing ---------------------------------------------------------

  Future<WaProfile> add(WaProfile draft) async {
    final profile = WaProfile(
      id: DateTime.now().microsecondsSinceEpoch.toRadixString(36),
      name: draft.name.trim().isEmpty
          ? 'Profile ${profiles.value.length + 1}'
          : draft.name.trim(),
      color: draft.color,
      layout: draft.layout,
    );
    profiles.value = [...profiles.value, profile];
    activeId.value = profile.id;
    await _persist();
    await _prefs?.setString(_activeKey, profile.id);
    await _bridge.attach(profile);
    await _bridge.requestNotifications();
    await refreshNotificationPermission();
    // Linking by code means leaving for WhatsApp; the session has to survive
    // being backgrounded while the user is there.
    await _ensureBatteryExemption(force: true);
    return profile;
  }

  Future<void> update(WaProfile profile) async {
    final index = profiles.value.indexWhere((p) => p.id == profile.id);
    if (index < 0) return;
    final previous = profiles.value[index];
    final next = [...profiles.value]..[index] = profile;
    profiles.value = next;
    await _persist();

    // Only a layout or zoom change costs a reload; renaming must never
    // interrupt a live conversation.
    if (previous.layout != profile.layout) {
      if (runtime(profile.id).value.live) await _bridge.applyLayout(profile);
    } else if (previous.muted != profile.muted) {
      if (runtime(profile.id).value.live) await _bridge.preload(profile);
      _recountUnread();
    }
    _recountLive();
  }

  /// Puts an account to sleep: the session closes, the login survives.
  Future<void> sleep(String id) async {
    await _bridge.release(id);
    thumbnail(id).value = null;
    if (activeId.value == id) {
      final next = profiles.value.where((p) => p.id != id);
      final replacement = next.isEmpty ? null : next.first;
      activeId.value = replacement?.id;
      if (replacement != null) await _bridge.attach(replacement);
    }
  }

  /// Removes the account and erases its storage partition for good.
  Future<void> remove(String id) async {
    await _bridge.forget(id);
    profiles.value =
        profiles.value.where((p) => p.id != id).toList(growable: false);
    _runtimes.remove(id)?.dispose();
    _thumbnails.remove(id)?.dispose();
    _avatars.remove(id)?.dispose();
    await _prefs?.remove('$_avatarKey$id');
    _recountLive();

    if (activeId.value == id) {
      activeId.value = profiles.value.isEmpty ? null : profiles.value.first.id;
      final next = active;
      if (next != null) {
        await _prefs?.setString(_activeKey, next.id);
        await _bridge.attach(next);
      } else {
        await _prefs?.remove(_activeKey);
      }
    }
    await _persist();
  }

  /// Signs the account out by wiping its storage, then loads it fresh.
  Future<void> signOut(String id) async {
    final profile = byId(id);
    if (profile == null) return;
    await _bridge.forget(id);
    runtime(id).value = const WaRuntime();
    thumbnail(id).value = null;
    if (activeId.value == id) await _bridge.attach(profile);
  }

  Future<void> reload(String id) => _bridge.reload(id);

  Future<bool> closeChat(String id) => _bridge.closeChat(id);

  Future<bool> goBack(String id) => _bridge.goBack(id);

  /// Pushes pending cookies to disk. Called when the app leaves the foreground,
  /// because Android can kill the process without warning and anything still
  /// buffered would be lost — which shows up as being logged out again.
  Future<void> flush() => _bridge.flush();


  Future<void> _persist() async {
    await _prefs?.setString(
      _profilesKey,
      jsonEncode(profiles.value.map((p) => p.toJson()).toList()),
    );
  }

  // ---- Events ----------------------------------------------------------

  void _recountLive() {
    liveIds.value = [
      for (final p in profiles.value)
        if (_runtimes[p.id]?.value.live ?? false) p.id,
    ];
  }

  void _recountUnread() {
    var total = 0;
    for (final profile in profiles.value) {
      if (profile.muted) continue;
      total += (_runtimes[profile.id]?.value.unread ?? 0);
    }
    totalUnread.value = total;
  }

  void _onEvent(WaEvent event) {
    final id = event.profileId;
    final slot = runtime(id);
    final current = slot.value;

    switch (event.type) {
      case 'live':
        slot.value = current.copyWith(live: event.data['live'] as bool? ?? false);
        _recountLive();
      case 'loading':
        slot.value = current.copyWith(loading: true, progress: 0, error: null);
      case 'progress':
        slot.value = current.copyWith(progress: event.intValue);
      case 'loaded':
        slot.value = current.copyWith(loading: false, progress: 100);
      case 'avatar':
        final b64 = event.data['base64'] as String? ?? '';
        if (b64.isNotEmpty) {
          try {
            avatar(id).value = base64Decode(b64);
            _prefs?.setString('$_avatarKey$id', b64);
          } on Object catch (_) {
            // A malformed frame is not worth failing the session over.
          }
        }
      case 'chatOpen':
        slot.value = current.copyWith(chatOpen: event.data['open'] as bool? ?? false);
      case 'booted':
        slot.value = current.copyWith(booted: true, error: null);
      case 'unread':
        slot.value = current.copyWith(unread: event.intValue);
        _recountUnread();
      case 'title':
        final match = RegExp(r'^\((\d+)\)').firstMatch(event.stringValue);
        slot.value = current.copyWith(
          unread: match == null ? 0 : int.parse(match.group(1)!),
        );
        _recountUnread();
      case 'error':
        slot.value = current.copyWith(
          loading: false,
          error: (event.data['message'] as String?)?.trim(),
        );
      case 'requestFocus':
        switchTo(id);
      default:
        final message = _noticeFor(event);
        if (message.isNotEmpty) notice.value = message;
    }
  }

  String _noticeFor(WaEvent event) => switch (event.type) {
        'downloadSaved' => 'Saved ${event.stringValue} to Downloads',
        'downloadStarted' => 'Downloading ${event.stringValue}',
        'downloadFailed' => 'Could not save ${event.stringValue}',
        'downloadTooLarge' => '${event.stringValue} is too large to save here',
        _ => '',
      };

  void dispose() {
    for (final n in _runtimes.values) {
      n.dispose();
    }
    for (final n in _thumbnails.values) {
      n.dispose();
    }
    for (final n in _avatars.values) {
      n.dispose();
    }
    profiles.dispose();
    activeId.dispose();
    ready.dispose();
    notice.dispose();
    notificationsAllowed.dispose();
    totalUnread.dispose();
    liveIds.dispose();
  }
}
