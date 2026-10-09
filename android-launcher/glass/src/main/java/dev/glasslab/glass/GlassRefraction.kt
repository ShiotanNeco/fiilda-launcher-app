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
    float2 centered = p - surfaceSize * 0.5;
    float2 q = abs(centered) - (surfaceSize * 0.5 - radius);
    float2 outside = max(q, float2(0.0));
    float outsideLength = length(outside);
    float distance = outsideLength + min(max(q.x, q.y), 0.0) - radius;
    float band = max(1.0, min(min(surfaceSize.x, surfaceSize.y) * 0.24, radius + 12.0));
    float edge = 1.0 - smoothstep(0.0, band, max(0.0, -distance));
    float2 displacement = float2(0.0);
    // The flat interior has no refraction. Avoid four extra distance evaluations per pixel there.
    if (edge > 0.0) {
        float2 normal;
        if (outsideLength > 0.0 && min(abs(q.x), abs(q.y)) >= 0.5) {
            normal = sign(centered) * outside / outsideLength;
        } else if (outsideLength == 0.0 && abs(q.x - q.y) >= 0.5) {
            normal = q.x > q.y
                ? float2(sign(centered.x), 0.0)
                : float2(0.0, sign(centered.y));
        } else {
            // Keep the original smoothing at straight/corner joins and edge-normal boundaries.
            float2 gradient = float2(
                roundedDistance(p + float2(0.5, 0.0)) - roundedDistance(p - float2(0.5, 0.0)),
                roundedDistance(p + float2(0.0, 0.5)) - roundedDistance(p - float2(0.0, 0.5))
            );
            normal = gradient / max(length(gradient), 0.001);
        }
        // Sample inward so neighbouring wallpaper edges do not appear as straight tile seams.
        displacement = -normal * refraction * edge * edge;
    }
    half4 sampled = backdrop.eval(coordinate + displacement);
    // Preserve premultiplied alpha while modestly increasing color separation.
    half luminance = dot(sampled.rgb, half3(0.2126, 0.7152, 0.0722));
    half3 color = clamp(mix(half3(luminance), sampled.rgb, half(saturation)),
                        half3(0.0), half3(sampled.a));
    half coverage = half(1.0 - smoothstep(-0.75, 0.75, distance));
    return half4(color, sampled.a) * coverage;
}
"""
