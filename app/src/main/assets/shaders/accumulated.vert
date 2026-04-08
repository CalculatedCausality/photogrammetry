#version 300 es

// Accumulated world-space point cloud vertex shader.
// Each vertex is [x, y, z, confidence] stored in a_Position.w.
uniform mat4  u_ModelViewProjection;
uniform float u_PointSize;
uniform float u_DepthRange;  // max eye-depth (metres) mapped to v_Depth=1.0
uniform float u_Alpha;       // global cloud opacity scalar [0.0, 1.0]

in  vec4  a_Position;
out float v_Depth;       // 0 = near (white), 1 = far (deep blue)
out float v_Confidence;
out float v_Alpha;       // passed through to fragment shader

void main() {
    gl_Position  = u_ModelViewProjection * vec4(a_Position.xyz, 1.0);
    // Clip-space w = eye-space distance from camera plane (positive in front).
    float eyeDepth = gl_Position.w;
    v_Depth        = clamp(eyeDepth / u_DepthRange, 0.0, 1.0);
    v_Confidence   = a_Position.w;
    v_Alpha        = u_Alpha;
    // Perspective-correct point size: smaller when far, clamped to [1, 12]
    gl_PointSize   = clamp(u_PointSize / max(eyeDepth, 0.1), 1.0, 12.0);
}
