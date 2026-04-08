#version 300 es
precision mediump float;

// GL_RG8 normalized texture.  Channel layout (set by DepthRenderer.kt):
//   R = depth normalised to [0,1] over MAX_DEPTH_MM (default 5000 mm)
//   G = confidence normalised to [0,1]  (7/7 → 1.0)
uniform sampler2D u_DepthTexture;
uniform float     u_Opacity;
uniform float     u_MaxDepthMm;    // kept for API compatibility (unused in shader)

in  vec2 v_TexCoord;
out vec4 fragColor;

// Classic Jet colourmap: blue (near) → cyan → green → yellow → red (far)
vec3 jetColor(float t) {
    t = clamp(t, 0.0, 1.0);
    float r = clamp(1.5 - abs(4.0 * t - 3.0), 0.0, 1.0);
    float g = clamp(1.5 - abs(4.0 * t - 2.0), 0.0, 1.0);
    float b = clamp(1.5 - abs(4.0 * t - 1.0), 0.0, 1.0);
    return vec3(r, g, b);
}

void main() {
    vec2  rg         = texture(u_DepthTexture, v_TexCoord).rg;
    float normDepth  = rg.r;
    float confidence = rg.g;

    // Discard invalid pixels and low-confidence noise (0.05 cutoff cleaner than 0.01)
    if (normDepth < 0.001 || confidence < 0.05) {
        discard;
    }

    vec3  colour = jetColor(normDepth);
    float alpha  = u_Opacity * confidence;
    fragColor    = vec4(colour, alpha);
}
