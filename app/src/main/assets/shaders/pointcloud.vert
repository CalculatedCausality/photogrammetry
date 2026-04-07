#version 300 es

uniform mat4  u_ModelViewProjection;
uniform float u_PointSize;

// Each ARCore feature point: [X, Y, Z, Confidence]
in vec3  a_Position;
in float a_Confidence;

out float v_Confidence;

void main() {
    v_Confidence  = a_Confidence;
    gl_Position   = u_ModelViewProjection * vec4(a_Position, 1.0);

    // Scale point size slightly with depth so distant points remain visible
    float depth   = gl_Position.w;
    gl_PointSize  = u_PointSize / max(depth, 0.1);
}
