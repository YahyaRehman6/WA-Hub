import 'package:flutter/foundation.dart';
import 'package:flutter/gestures.dart';
import 'package:flutter/material.dart';
import 'package:flutter/rendering.dart';
import 'package:flutter/services.dart';

/// The one native surface every WhatsApp session renders into.
///
/// Built once and kept alive for the life of the app: rebuilding it would tear
/// down every logged-in session underneath it.
class WaStage extends StatelessWidget {
  const WaStage({super.key});

  static const _viewType = 'wa_hub/stage';

  /// Texture-layer composition (`initSurfaceAndroidView`) rather than hybrid
  /// composition (`initExpensiveAndroidView`).
  ///
  /// Measured on device: hybrid composition put the WebView into the *Android*
  /// view hierarchy, so every frame was composited twice — once by Flutter's
  /// Impeller pass and again by Android's Skia pipeline. `dumpsys gfxinfo`
  /// showed 27.7% janky frames with 540 "slow issue draw commands" while the
  /// GPU sat idle at 7ms per frame. The work was in issuing draw commands, not
  /// in drawing.
  ///
  /// The trade-off is text input: hybrid composition has historically been the
  /// safer mode for a WebView's soft keyboard. Typing is the whole point of
  /// this app, so if the keyboard ever misbehaves, flip this one flag back.
  static const _useTextureLayer = true;

  @override
  Widget build(BuildContext context) {
    return PlatformViewLink(
      viewType: _viewType,
      surfaceFactory: (context, controller) => AndroidViewSurface(
        controller: controller as AndroidViewController,
        hitTestBehavior: PlatformViewHitTestBehavior.opaque,
        // WhatsApp needs every touch: swipe-to-reply, long-press, drag-to-
        // scroll. Anything less and Flutter starts arbitrating gestures the
        // page owns.
        gestureRecognizers: <Factory<OneSequenceGestureRecognizer>>{
          Factory<OneSequenceGestureRecognizer>(EagerGestureRecognizer.new),
        },
      ),
      onCreatePlatformView: (params) {
        final controller = _useTextureLayer
            ? PlatformViewsService.initSurfaceAndroidView(
                id: params.id,
                viewType: _viewType,
                layoutDirection: TextDirection.ltr,
                creationParamsCodec: const StandardMessageCodec(),
                onFocus: () => params.onFocusChanged(true),
              )
            : PlatformViewsService.initExpensiveAndroidView(
                id: params.id,
                viewType: _viewType,
                layoutDirection: TextDirection.ltr,
                creationParamsCodec: const StandardMessageCodec(),
                onFocus: () => params.onFocusChanged(true),
              );
        return controller
          ..addOnPlatformViewCreatedListener(params.onPlatformViewCreated)
          ..create();
      },
    );
  }
}
