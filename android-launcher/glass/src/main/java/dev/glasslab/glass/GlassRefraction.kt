package dev.glasslab.glass

/**
 * Original rounded-lens approximation, not Apple's private material implementation. Coordinates
 * are pixels in a padded, surface-local RenderNode.
 */
internal const val GLASS_REFRACTION = """
uniform shader backdrop;
uniform float2 surfaceSize;
uniform float padding;
uniform float radius;
uniform float refraction;
uniform float saturation;

float roundedDistance(float2 p) {
    float2 q = abs(p - surfaceSize * 0.5) - (surfaceSize * 0.5 - radius);
    return length(max(q, float2(0.0))) + min(max(q.x, q.y), 0.0) - radius;
}

half4 main(float2 coordinate) {
    float2 p = coordinate - float2(padding);
    float distance = roundedDistance(p);
    float band = max(1.0, min(min(surfaceSize.x, surfaceSize.y) * 0.24, radius + 12.0));
    float edge = 1.0 - smoothstep(0.0, band, max(0.0, -distance));
    float2 gradient = float2(
        roundedDistance(p + float2(0.5, 0.0)) - roundedDistance(p - float2(0.5, 0.0)),
        roundedDistance(p + float2(0.0, 0.5)) - roundedDistance(p - float2(0.0, 0.5))
    );
    float2 normal = gradient / max(length(gradient), 0.001);
    // Sample inward: pulling the backdrop from outside the surface draws neighbouring wallpaper
    // edges into the tile as straight seams, most visibly on high-density displays.
    float2 displacement = -normal * refraction * edge * edge;
    half4 sampled = backdrop.eval(coordinate + displacement);
    // Preserve premultiplied alpha while modestly increasing color separation.
    half luminance = dot(sampled.rgb, half3(0.2126, 0.7152, 0.0722));
    half3 color = clamp(mix(half3(luminance), sampled.rgb, half(saturation)),
                        half3(0.0), half3(sampled.a));
    half coverage = half(1.0 - smoothstep(-0.75, 0.75, distance));
    return half4(color, sampled.a) * coverage;
}
"""
