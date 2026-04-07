#version 300 es

// Full-screen quad positions in OpenGL NDC (–1 to 1)
in vec2 a_Position;

// UV coordinates produced by ARCore's transformCoordinates2d
in vec2 a_TexCoord;

out vec2 v_TexCoord;

void main() {
    gl_Position = vec4(a_Position, 0.0, 1.0);
    v_TexCoord  = a_TexCoord;
}
