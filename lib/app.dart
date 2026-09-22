import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

import 'design/tokens.dart';
import 'native/wa_bridge.dart';
import 'state/profiles_controller.dart';
import 'ui/shell.dart';

class WaHubApp extends StatefulWidget {
  const WaHubApp({super.key});

  @override
  State<WaHubApp> createState() => _WaHubAppState();
}

class _WaHubAppState extends State<WaHubApp> {
  late final WaBridge _bridge = WaBridge();
  late final ProfilesController _controller = ProfilesController(_bridge);
  late final AppLifecycleListener _lifecycle;

  @override
  void initState() {
    super.initState();
    _controller.load();

    // Android can kill this process the moment it leaves the foreground, and
    // anything Chromium still had buffered would go with it — which the user
    // would meet as being logged out again.
    _lifecycle = AppLifecycleListener(
      onInactive: _controller.flush,
      onHide: _controller.flush,
      onPause: _controller.flush,
      onDetach: _controller.flush,
      onResume: _controller.refreshNotificationPermission,
    );
  }

  @override
  void dispose() {
    _lifecycle.dispose();
    _controller.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) => MaterialApp(
        title: 'WA Hub',
        debugShowCheckedModeBanner: false,
        // The design is a single dark scheme.
        theme: buildTheme(),
        builder: (context, child) {
          SystemChrome.setSystemUIOverlayStyle(const SystemUiOverlayStyle(
            statusBarColor: Colors.transparent,
            statusBarIconBrightness: Brightness.light,
            systemNavigationBarColor: Sk.ink,
            systemNavigationBarIconBrightness: Brightness.light,
          ));
          // Clamped rather than disabled: the header is compact and breaks
          // past ~1.3, but ignoring the reader's font size is not ours to do.
          return MediaQuery.withClampedTextScaling(
            maxScaleFactor: 1.3,
            child: child!,
          );
        },
        home: Shell(controller: _controller),
      );
}
