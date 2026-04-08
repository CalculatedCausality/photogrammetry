#version 300 es
precision mediump float;

in  float v_Depth;
in  float v_Confidence;
in  float v_Alpha;      // global cloud opacity from u_Alpha
out vec4  fragColor;

void main() {
    // Discard very-low-confidence noise (0.05 threshold shows more depth detail
    // than the old 0.2 while still suppressing very noisy pixels)
    if (v_Confidence < 0.05) discard;

    // Circular point sprite
    vec2  coord = gl_PointCoord - 0.5;
    float r2    = dot(coord, coord);
    if (r2 > 0.25) discard;

    // Colour: white (near) → sky-blue (mid) → deep blue (far)
    vec3 near  = vec3(1.0, 1.0, 1.0);  // white
    vec3 mid   = vec3(0.4, 0.7, 1.0);  // sky blue
    vec3 far   = vec3(0.05, 0.1, 0.6); // deep blue

    vec3 colour;
    if (v_Depth < 0.5) {
        colour = mix(near, mid, v_Depth * 2.0);
    } else {
        colour = mix(mid, far, (v_Depth - 0.5) * 2.0);
    }

    // Depth-based alpha fade: far points become more transparent to reduce clutter
    float alpha = (0.75 + v_Confidence * 0.25) * (1.0 - v_Depth * 0.4);

    // Soft anti-aliased disc edge (outer 20% of radius feathers out)
    float edge   = smoothstep(0.25, 0.16, r2);
    // Centre glow highlight
    float r2_    = r2;  // already computed
    float glow   = max((0.25 - r2) * 4.0, 0.0);  // 1 at centre, 0 at edge, clamped
    colour = mix(colour, vec3(1.0), glow * 0.35 * v_Confidence);

    fragColor = vec4(colour, alpha * edge * v_Alpha);
}
