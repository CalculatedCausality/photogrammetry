#version 300 es
precision mediump float;

in  float v_Confidence;
out vec4  fragColor;

void main() {
    // Render each point as a filled circle (discard corners of the point sprite)
    vec2  coord = gl_PointCoord - vec2(0.5);
    float r2    = dot(coord, coord);
    if (r2 > 0.25) {
        discard;
    }

    // Colour: red = low confidence, green = high confidence, with a soft glow rim
    float r = 1.0 - v_Confidence;
    float g = v_Confidence;
    float b = 0.15;

    // Slightly brighter at the centre for a glow effect
    float brightness = 1.0 - 2.0 * length(coord);
    // Soft anti-aliased disc edge (outer 20% of radius feathers out)
    float edge = smoothstep(0.25, 0.16, r2);
    fragColor = vec4(r * brightness, g * brightness, b, edge);
}
