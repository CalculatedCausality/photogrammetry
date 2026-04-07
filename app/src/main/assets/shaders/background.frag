#version 300 es
// samplerExternalOES lets us sample directly from the YUV camera buffer
// without an extra YUV→RGB conversion pass on the CPU.
#extension GL_OES_EGL_image_external_essl3 : require

precision mediump float;

uniform samplerExternalOES u_Texture;

in  vec2 v_TexCoord;
out vec4 fragColor;

void main() {
    fragColor = texture(u_Texture, v_TexCoord);
}
