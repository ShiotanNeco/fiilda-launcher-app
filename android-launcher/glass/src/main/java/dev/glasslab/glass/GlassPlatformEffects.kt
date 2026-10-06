package dev.glasslab.glass

import android.graphics.RenderEffect as AndroidRenderEffect
import android.graphics.RuntimeShader
import android.graphics.Shader
import android.os.Build
import androidx.annotation.DoNotInline
import androidx.annotation.RequiresApi
import androidx.compose.ui.graphics.RenderEffect as ComposeRenderEffect
import androidx.compose.ui.graphics.asComposeRenderEffect

/** A type-erased shader handle keeps API 33 classes out of the main renderer's signatures. */
internal class GlassShaderHandle internal constructor(internal val raw: Any)

/**
 * API-specific drawing entry points. New framework classes are referenced only from nested
 * helpers that are called after the corresponding SDK check, which avoids class loading on API
 * 29-30 while retaining the native RenderEffect path on newer devices.
 */
internal object GlassPlatformEffects {
    // Compiling AGSL is costly, and every surface used to compile its own copy as it was first
    // composed (a page swipe composed a whole page of them at once). One shader per source is
    // shared: each RenderEffect captures the uniforms set just before it is created, and effects
    // are only created on the main thread.
    private val shaderCache = HashMap<String, GlassShaderHandle?>()

    fun createShader(source: String): GlassShaderHandle? =
        if (Build.VERSION.SDK_INT >= 33) {
            shaderCache.getOrPut(source) { Api33.createShader(source) }
        } else {
            null
        }

    fun createEffect(
        shader: GlassShaderHandle?,
        surfaceWidth: Float,
        surfaceHeight: Float,
        paddingPx: Float,
        radiusPx: Float,
        refractionPx: Float,
        blurPx: Float,
        saturation: Float,
    ): ComposeRenderEffect? {
        if (Build.VERSION.SDK_INT >= 33 && shader != null && refractionPx > 0f) {
            return Api33.createEffect(
                shader = shader,
                surfaceWidth = surfaceWidth,
                surfaceHeight = surfaceHeight,
                paddingPx = paddingPx,
                radiusPx = radiusPx,
                refractionPx = refractionPx,
                blurPx = blurPx,
                saturation = saturation,
            )
        }
        return if (Build.VERSION.SDK_INT >= 31 && blurPx > 0f) {
            Api31.createBlurEffect(blurPx)
        } else {
            null
        }
    }

    /**
     * The refraction shader reading [input] directly (no padding, no offscreen layer), or null when
     * refraction is unavailable. The shared shader is safe to reuse: a draw captures its inputs.
     */
    fun refractedShader(
        shader: GlassShaderHandle?,
        input: android.graphics.Shader,
        surfaceWidth: Float,
        surfaceHeight: Float,
        radiusPx: Float,
        refractionPx: Float,
        saturation: Float,
    ): android.graphics.Shader? =
        if (Build.VERSION.SDK_INT >= 33 && shader != null && refractionPx > 0f) {
            Api33.refractedShader(shader, input, surfaceWidth, surfaceHeight, radiusPx, refractionPx, saturation)
        } else {
            null
        }

    private object Api31 {
        @DoNotInline
        @RequiresApi(31)
        fun createBlurEffect(radiusPx: Float): ComposeRenderEffect =
            AndroidRenderEffect.createBlurEffect(
                radiusPx,
                radiusPx,
                Shader.TileMode.CLAMP,
            ).asComposeRenderEffect()
    }

    private object Api33 {
        @DoNotInline
        @RequiresApi(33)
        fun refractedShader(
            shader: GlassShaderHandle,
            input: android.graphics.Shader,
            surfaceWidth: Float,
            surfaceHeight: Float,
            radiusPx: Float,
            refractionPx: Float,
            saturation: Float,
        ): android.graphics.Shader {
            val runtimeShader = shader.raw as RuntimeShader
            runtimeShader.setFloatUniform("surfaceSize", surfaceWidth, surfaceHeight)
            runtimeShader.setFloatUniform("padding", 0f)
            runtimeShader.setFloatUniform("radius", radiusPx)
            runtimeShader.setFloatUniform("refraction", refractionPx)
            runtimeShader.setFloatUniform("saturation", saturation)
            runtimeShader.setInputShader("backdrop", input)
            return runtimeShader
        }

        @DoNotInline
        @RequiresApi(33)
        fun createShader(source: String): GlassShaderHandle =
            GlassShaderHandle(RuntimeShader(source))

        @DoNotInline
        @RequiresApi(33)
        fun createEffect(
            shader: GlassShaderHandle,
            surfaceWidth: Float,
            surfaceHeight: Float,
            paddingPx: Float,
            radiusPx: Float,
            refractionPx: Float,
            blurPx: Float,
            saturation: Float,
        ): ComposeRenderEffect {
            val runtimeShader = shader.raw as RuntimeShader
            runtimeShader.setFloatUniform("surfaceSize", surfaceWidth, surfaceHeight)
            runtimeShader.setFloatUniform("padding", paddingPx)
            runtimeShader.setFloatUniform("radius", radiusPx)
            runtimeShader.setFloatUniform("refraction", refractionPx)
            runtimeShader.setFloatUniform("saturation", saturation)
            val refract = AndroidRenderEffect.createRuntimeShaderEffect(runtimeShader, "backdrop")
            val effect = if (blurPx > 0f) {
                AndroidRenderEffect.createChainEffect(
                    refract,
                    AndroidRenderEffect.createBlurEffect(
                        blurPx,
                        blurPx,
                        Shader.TileMode.CLAMP,
                    ),
                )
            } else {
                refract
            }
            return effect.asComposeRenderEffect()
        }
    }
}
