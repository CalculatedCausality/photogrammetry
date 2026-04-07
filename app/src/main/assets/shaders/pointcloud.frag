#version 300 es
precision mediump float;

in  float v_Confidence;
out vec4  fragColor;

void main() {
    // Render each point as a filled circle (discard corners of the point sprite)
    vec2 coord = gl_PointCoord - vec2(0.5);
    if (dot(coord, coord) > 0.25) {
        discard;
    }

    // Colour: red = low confidence, green = high confidence, with a soft glow rim
    float r = 1.0 - v_Confidence;
    float g = v_Confidence;
    float b = 0.15;

    // Slightly brighter at the centre for a glow effect
    float brightness = 1.0 - 2.0 * length(coord);
    fragColor = vec4(r * brightness, g * brightness, b, 1.0);
}
