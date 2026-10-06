package dev.glasslab.glass

/**
 * A geometry revision that is read while drawing instead of while composing. Reading snapshot
 * state (scroll offsets, animation frames) here invalidates only the draw of a glass surface, so a
 * scrolling parent does not recompose every tile on every frame.
 */
fun interface GlassGeometrySignal {
    fun read(): Any?
}

/** Returns the current revision, reading a [GlassGeometrySignal] in the caller's snapshot scope. */
fun resolveGlassGeometry(version: Any?): Any? =
    if (version is GlassGeometrySignal) version.read() else version
