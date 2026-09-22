import 'package:flutter/services.dart';

import '../models/profile.dart';

/// An event pushed up from a live WebView.
class WaEvent {
  WaEvent(this.profileId, this.type, this.data);
  final String profileId;
  final String type;
  final Map<Object?, Object?> data;
  int get intValue => (data['count'] ?? data['value'] ?? 0) as int;
  String get stringValue => (data['title'] ?? data['name'] ?? data['url'] ?? '') as String;
}
/// Native capabilities, read once at startup.
///
/// [multiProfile] is the one that matters: without it the platform cannot give
/// each account its own storage partition, and the app has to say so plainly
/// rather than pretend the accounts are separate.
class WaCapabilities {
  const WaCapabilities({
    required this.multiProfile,
    required this.documentStart,
    required this.webMessage,
    required this.webViewVersion,
    required this.notificationsAllowed,
  });
  final bool multiProfile;
  final bool documentStart;
  final bool webMessage;
  final String webViewVersion;
  final bool notificationsAllowed;
  bool get isolated => multiProfile;
  bool get complete => multiProfile && documentStart && webMessage;
}
class WaBridge {
  WaBridge() {
    _host.setMethodCallHandler(_onHostCall);
    _launch.setMethodCallHandler(_onLaunchCall);
  }
  static const _host = MethodChannel('wa_hub/host');
  static const _launch = MethodChannel('wa_hub/launch');
  final _listeners = <void Function(WaEvent)>[];
  final _launchListeners = <void Function(String)>[];
  void listen(void Function(WaEvent) fn) => _listeners.add(fn);
  void onLaunchProfile(void Function(String) fn) => _launchListeners.add(fn);
  Future<void> _onHostCall(MethodCall call) async {
    if (call.method != 'event') return;
    final args = (call.arguments as Map).cast<Object?, Object?>();
    final event = WaEvent(
      args['profileId']! as String,
      args['type']! as String,
      args,
    );
    for (final fn in List.of(_listeners)) {
      fn(event);
    }
  }
  Future<void> _onLaunchCall(MethodCall call) async {
    if (call.method != 'openProfile') return;
    final id = call.arguments as String;
    for (final fn in List.of(_launchListeners)) {
      fn(id);
    }
  }
  Future<WaCapabilities> capabilities() async {
    final map = (await _host.invokeMapMethod<String, Object?>('capabilities')) ?? const {};
    return WaCapabilities(
      multiProfile: map['multiProfile'] as bool? ?? false,
      documentStart: map['documentStart'] as bool? ?? false,
      webMessage: map['webMessage'] as bool? ?? false,
      webViewVersion: map['webViewVersion'] as String? ?? 'unknown',
      notificationsAllowed: map['notificationsAllowed'] as bool? ?? false,
    );
  }
  /// Which profile a notification tap asked for, if the app was cold-started by one.
  Future<String?> consumeLaunchProfile() =>
      _launch.invokeMethod<String>('consumeLaunchProfile');
  Future<void> attach(WaProfile profile) => _host.invokeMethod('attach', _spec(profile));
  Future<void> preload(WaProfile profile) => _host.invokeMethod('preload', _spec(profile));
  Future<void> applyLayout(WaProfile profile) =>
      _host.invokeMethod('applyLayout', _spec(profile));
  Future<void> release(String profileId) =>
      _host.invokeMethod('release', {'profileId': profileId});
  /// Destroys the session *and* its storage partition. Not reversible.
  Future<bool> forget(String profileId) async =>
      await _host.invokeMethod<bool>('forget', {'profileId': profileId}) ?? false;
  /// Dismisses the open conversation so the chat list comes back, the way the
  /// mobile app's back arrow does.
  /// Returns true when the page had something to dismiss — a dialog or an
  /// open conversation — so the caller knows not to go back further.
  Future<bool> closeChat(String profileId) async =>
      await _host.invokeMethod<bool>('closeChat', {'profileId': profileId}) ??
      false;

  Future<void> reload(String profileId) =>
      _host.invokeMethod('reload', {'profileId': profileId});
  Future<void> home(String profileId) =>
      _host.invokeMethod('home', {'profileId': profileId});
  Future<bool> goBack(String profileId) async =>
      await _host.invokeMethod<bool>('goBack', {'profileId': profileId}) ?? false;
  Future<bool> canGoBack(String profileId) async =>
      await _host.invokeMethod<bool>('canGoBack', {'profileId': profileId}) ?? false;
  Future<Uint8List?> thumbnail(String profileId, {int maxWidth = 480}) =>
      _host.invokeMethod<Uint8List>(
        'thumbnail',
        {'profileId': profileId, 'maxWidth': maxWidth},
      );
  /// Asked for when the first account is added, not on a cold first launch:
  /// a permission prompt before the user has done anything is just noise.
  Future<void> requestNotifications() =>
      _host.invokeMethod('requestNotifications');

  /// Forces buffered cookies to disk. Cheap, and the difference between a
  /// crash costing nothing and a crash costing every login.
  Future<void> flush() => _host.invokeMethod('flush');

  Future<bool> notificationsAllowed() async =>
      await _host.invokeMethod<bool>('notificationsAllowed') ?? false;

  /// Whether the OS has agreed not to kill this app in the background. On
  /// TECNO/HiOS and similar this is the difference between a link code that
  /// completes and one that "fails" after a long wait.
  Future<bool> isBatteryExempt() async =>
      await _host.invokeMethod<bool>('isBatteryExempt') ?? true;

  Future<void> requestBatteryExemption() =>
      _host.invokeMethod('requestBatteryExemption');

  Future<void> openNotificationSettings() =>
      _host.invokeMethod('openNotificationSettings');

  Future<void> setLiveLimit(int limit) =>
      _host.invokeMethod('setLiveLimit', {'limit': limit});
  /// Mute travels with the spec so the native notifier can honour it without
  /// a round trip on every incoming message.
  Future<void> setNotificationsEnabled(bool enabled) =>
      _host.invokeMethod('setNotificationsEnabled', {'enabled': enabled});

  Map<String, Object?> _spec(WaProfile profile) => {
        'profileId': profile.id,
        'name': profile.name,
        'forcedWidth': profile.layout.forcedWidth,
        'textZoom': 100,
        'muted': profile.muted,
      };
}
