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

    // Clamp point size: divide by depth for perspective, but cap at 16 px so
    // near-objects don't produce enormous sprites (was unbounded before).
    float depth   = max(gl_Position.w, 0.1);
    gl_PointSize  = clamp(u_PointSize / depth, 1.0, 16.0);
}
