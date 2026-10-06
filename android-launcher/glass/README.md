# FiiLDA glass engine

This module started as an adaptation of a small Jetpack Compose "liquid glass" starter written
for this project. It uses only public Android APIs (RenderEffect and AGSL RuntimeShader); it is
not a port of Apple's implementation.

The host launcher uses AGP 8.13.0, Kotlin 2.2.21, Compose 1.11.3, compileSdk 36,
and minSdk 29. The starter's AGP 9.2.1, Compose 1.12.0, compileSdk 37, and minSdk
36 contract were therefore adapted instead of copied into the host build.

The public engine separates a wallpaper-only `GlassBackdrop` from sharp foreground
scene contributors. `GlassSurface` and `Modifier.glassSurface` never register
themselves as contributors, so a navigation glass can sample wallpaper plus sharp
scrolling content without sampling another glass effect. `RenderEffect` and
`RuntimeShader` are isolated behind API guards: API 29-30 replays and tints the
backdrop, API 31-32 adds blur, and API 33+ adds the AGSL refraction effect.

Pass a changing `contentVersion` to `GlassBackdrop` when content below it changes
without recomposing the backdrop call site, such as a selected wallpaper bitmap.
Pass the matching local `cornerRadius` to `glassSceneContributor` when the sharp
source is rounded; the scene replays that rounded coverage for both captured and
fallback contributors. Arbitrary ancestor clip paths are not inferred.

The scene contributor API is intentionally opt-in. Apply it to sharp Compose
foreground content only. Ordinary ImageViews draw into Compose's supplied hardware
Canvas and can be recorded, preserving the launcher's dynamic icon implementation.
For VideoView/SurfaceView playback or unknown external widget content, pass a
`fallbackColor`; the child remains unchanged on screen while the scene receives a
solid representation. Pass a changing `geometryVersion` to a contributor and to
the glass surface when an ancestor `graphicsLayer` changes without a layout pass;
the draw-time coordinate matrix then samples the current scroll/scale position.
