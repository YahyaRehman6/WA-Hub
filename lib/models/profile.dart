import 'package:flutter/foundation.dart';

/// How wide the page believes the screen is.
///
/// Measured against the real logged-in app over Chrome DevTools: WhatsApp
/// serves its desktop layout, whose columns have an intrinsic floor near
/// 748px. Below that it is clipped by an ancestor with `overflow: hidden`,
/// and `scrollWidth` still reports no overflow so nothing scrolls to reveal
/// it. `phone` is therefore not merely a narrow viewport — injected CSS
/// rebuilds the three desktop columns into a single-pane phone layout.
enum WaLayout {
  phone(0),
  desktop(1024);

  const WaLayout(this.forcedWidth);

  /// CSS pixels the page lays out at, or 0 for the device's own width.
  final int forcedWidth;

  bool get isPhone => this == WaLayout.phone;
}

@immutable
class WaProfile {
  const WaProfile({
    required this.id,
    required this.name,
    this.color = 0,
    this.layout = WaLayout.phone,
    this.keepLoaded = false,
    this.muted = false,
  });

  final String id;
  final String name;

  /// Index into `ProfileColors.values`; the tab, avatar and card accent.
  final int color;

  final WaLayout layout;

  /// Exempt from the least-recently-used release, so the session stays warm.
  final bool keepLoaded;

  /// Suppress this profile's notifications without touching the others.
  final bool muted;

  /// Up to two letters, taken across words so "Client A" reads as "CA".
  String get initials {
    final words = name.trim().split(RegExp(r'\s+')).where((w) => w.isNotEmpty);
    if (words.isEmpty) return '?';
    if (words.length == 1) return _take(words.first, 2).toUpperCase();
    return (_take(words.first, 1) + _take(words.elementAt(1), 1)).toUpperCase();
  }

  static String _take(String word, int count) =>
      word.runes.take(count).map(String.fromCharCode).join();

  WaProfile copyWith({
    String? name,
    int? color,
    WaLayout? layout,
    bool? keepLoaded,
    bool? muted,
  }) =>
      WaProfile(
        id: id,
        name: name ?? this.name,
        color: color ?? this.color,
        layout: layout ?? this.layout,
        keepLoaded: keepLoaded ?? this.keepLoaded,
        muted: muted ?? this.muted,
      );

  Map<String, Object?> toJson() => {
        'id': id,
        'name': name,
        'color': color,
        'layout': layout.name,
        'keepLoaded': keepLoaded,
        'muted': muted,
      };

  static WaProfile fromJson(Map<String, Object?> json) => WaProfile(
        id: json['id']! as String,
        name: json['name']! as String,
        color: (json['color'] as num?)?.toInt() ?? 0,
        // Only an explicit desktop choice opts out of the phone layout;
        // records written before the design rewrite used compact/balanced/wide
        // and should land on the phone shell, which is the design's default.
        layout:
            json['layout'] == 'desktop' ? WaLayout.desktop : WaLayout.phone,
        keepLoaded: json['keepLoaded'] as bool? ?? false,
        muted: json['muted'] as bool? ?? false,
      );
}

/// The session states the shell renders: loading, linked, or needing a re-link.
enum WaState { idle, loading, linked, unlinked }

/// Everything about a profile that lives only while the app is running.
@immutable
class WaRuntime {
  const WaRuntime({
    this.live = false,
    this.loading = false,
    this.progress = 0,
    this.unread = 0,
    this.booted = false,
    this.chatOpen = false,
    this.error,
  });

  final bool live;
  final bool loading;
  final int progress;
  final int unread;
  final bool booted;

  /// True while a conversation covers the chat list, in phone layout.
  final bool chatOpen;

  final String? error;

  WaState get state {
    if (error != null) return WaState.unlinked;
    if (loading) return WaState.loading;
    if (live) return WaState.linked;
    return WaState.idle;
  }

  /// The uppercase mono label the session cards show.
  String get stateLabel => switch (state) {
        WaState.loading => 'connecting',
        WaState.linked => 'web session',
        WaState.unlinked => 'needs re-link',
        WaState.idle => 'asleep',
      };

  WaRuntime copyWith({
    bool? live,
    bool? loading,
    int? progress,
    int? unread,
    bool? booted,
    bool? chatOpen,
    Object? error = _keep,
  }) =>
      WaRuntime(
        live: live ?? this.live,
        loading: loading ?? this.loading,
        progress: progress ?? this.progress,
        unread: unread ?? this.unread,
        booted: booted ?? this.booted,
        chatOpen: chatOpen ?? this.chatOpen,
        error: identical(error, _keep) ? this.error : error as String?,
      );

  @override
  bool operator ==(Object other) =>
      other is WaRuntime &&
      other.live == live &&
      other.loading == loading &&
      other.progress == progress &&
      other.unread == unread &&
      other.booted == booted &&
      other.chatOpen == chatOpen &&
      other.error == error;

  @override
  int get hashCode =>
      Object.hash(live, loading, progress, unread, booted, chatOpen, error);

  static const _keep = Object();
}
