import 'package:flutter_test/flutter_test.dart';
import 'package:wa_hub/models/profile.dart';

void main() {
  group('WaProfile.initials', () {
    WaProfile named(String name) => WaProfile(id: 'a', name: name);

    test('takes two letters from a single word', () {
      expect(named('Work').initials, 'WO');
    });

    test('takes one letter from each of the first two words', () {
      expect(named('Client A').initials, 'CA');
      expect(named('my side project').initials, 'MS');
    });

    test('survives padding and empty names', () {
      expect(named('   Work   ').initials, 'WO');
      expect(named('').initials, '?');
      expect(named('   ').initials, '?');
    });
  });

  group('WaLayout', () {
    test('phone lays out at the device width, desktop at the measured floor', () {
      expect(WaLayout.phone.forcedWidth, 0);
      expect(WaLayout.phone.isPhone, isTrue);
      // Below ~748px WhatsApp's own layout clips, so the desktop fallback
      // must stay above it.
      expect(WaLayout.desktop.forcedWidth, greaterThan(748));
    });
  });

  group('WaRuntime', () {
    test('reports the state the session cards label', () {
      expect(const WaRuntime().stateLabel, 'asleep');
      expect(const WaRuntime(loading: true).stateLabel, 'connecting');
      expect(const WaRuntime(live: true).stateLabel, 'web session');
      expect(const WaRuntime(error: 'net::ERR').stateLabel, 'needs re-link');
    });

    test('clears an error when asked, and keeps it otherwise', () {
      const withError = WaRuntime(error: 'net::ERR_FAILED', unread: 3);
      expect(withError.copyWith(unread: 4).error, 'net::ERR_FAILED');
      expect(withError.copyWith(error: null).error, isNull);
      expect(withError.copyWith(error: null).unread, 3);
    });
  });


  group('WaProfile JSON', () {
    test('keeps colour and per-profile switches', () {
      const p = WaProfile(
          id: 'x', name: 'Work', color: 3, muted: true, keepLoaded: true);
      final back = WaProfile.fromJson(p.toJson());
      expect(back.color, 3);
      expect(back.muted, isTrue);
      expect(back.keepLoaded, isTrue);
      expect(back.layout, WaLayout.phone);
    });
  });
}
