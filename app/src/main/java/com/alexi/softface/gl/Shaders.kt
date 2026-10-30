package com.alexi.softface.gl

/**
 * GPU shaders. All frame-space passes use GL texture coords (s right, t up).
 * Distances are measured in "p" space = st * (aspect, 1) so circles stay round.
 */
object Shaders {
    const val MAX_SEGS = 12

    const val QUAD_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
layout(location = 1) in vec2 aTex;
out vec2 vTex;
void main() { vTex = aTex; gl_Position = vec4(aPos, 0.0, 1.0); }
"""

    const val COPY_FS = """#version 300 es
precision mediump float;
in vec2 vTex;
out vec4 o;
uniform sampler2D uTex;
void main() { o = vec4(texture(uTex, vTex).rgb, 1.0); }
"""

    /** 9-tap Gaussian along one direction. */
    const val BLUR_FS = """#version 300 es
precision mediump float;
in vec2 vTex;
out vec4 o;
uniform sampler2D uTex;
uniform vec2 uStep;
void main() {
    vec4 s = texture(uTex, vTex) * 0.2270270270;
    s += (texture(uTex, vTex + uStep * 1.3846153846) + texture(uTex, vTex - uStep * 1.3846153846)) * 0.3162162162;
    s += (texture(uTex, vTex + uStep * 3.2307692308) + texture(uTex, vTex - uStep * 3.2307692308)) * 0.0702702703;
    o = s;
}
"""

    /** Draws mask polygons. Positions arrive as st (0..1). */
    const val MASK_VS = """#version 300 es
layout(location = 0) in vec2 aPos;
void main() { gl_Position = vec4(aPos * 2.0 - 1.0, 0.0, 1.0); }
"""

    const val MASK_FS = """#version 300 es
precision mediump float;
out vec4 o;
uniform vec4 uColor;
void main() { o = uColor; }
"""

    /**
     * The look. Mask channels: r = skin, g = lips, b = around eyes.
     */
    const val COMPOSITE_FS = """#version 300 es
precision highp float;
in vec2 vTex;
out vec4 o;
uniform sampler2D uFrame;
uniform sampler2D uBlur;
uniform sampler2D uMask;
uniform float uAspect;
uniform float uFade;
uniform float uSmooth;
uniform float uContour;
uniform float uBlush;
uniform float uLip;
uniform float uEye;
uniform float uShowMask;
uniform vec2 uWarpC[4];
uniform vec2 uWarpD[4];
uniform float uWarpR;
uniform vec2 uBlushC[2];
uniform float uBlushR;
uniform vec4 uSeg[$MAX_SEGS];
uniform float uSegW[$MAX_SEGS];
uniform float uSegK[$MAX_SEGS];

const vec3 W = vec3(0.299, 0.587, 0.114);

float segDist(vec2 p, vec2 a, vec2 b) {
    vec2 pa = p - a, ba = b - a;
    float h = clamp(dot(pa, ba) / max(dot(ba, ba), 1e-6), 0.0, 1.0);
    return length(pa - ba * h);
}

vec3 setLum(vec3 c, float l) {
    c += l - dot(c, W);
    float L = dot(c, W);
    float n = min(min(c.r, c.g), c.b);
    float x = max(max(c.r, c.g), c.b);
    if (n < 0.0) c = L + (c - L) * L / max(L - n, 1e-4);
    if (x > 1.0) c = L + (c - L) * (1.0 - L) / max(x - L, 1e-4);
    return c;
}

vec3 softLight(vec3 b, vec3 s) {
    vec3 lo = 2.0 * b * s + b * b * (1.0 - 2.0 * s);
    vec3 hi = sqrt(b) * (2.0 * s - 1.0) + 2.0 * b * (1.0 - s);
    return mix(lo, hi, step(0.5, s));
}

void main() {
    vec2 asp = vec2(uAspect, 1.0);
    vec2 p = vTex * asp;

    // Face slim: pull the jaw and cheeks gently towards the middle.
    vec2 q = p;
    if (uFade > 0.0) {
        for (int i = 0; i < 4; i++) {
            float d = length(p - uWarpC[i]);
            float w = clamp(1.0 - d * d / (uWarpR * uWarpR), 0.0, 1.0);
            q -= uWarpD[i] * w * w * uFade;
        }
    }
    vec2 st = q / asp;
    vec3 c = texture(uFrame, st).rgb;
    if (uFade <= 0.0) { o = vec4(c, 1.0); return; }

    vec4 m = texture(uMask, st);
    float skin = m.r * uFade;
    float area = clamp(m.r * 2.0, 0.0, 1.0) * uFade;

    // Smooth skin: blend towards the blurred copy, but keep real edges sharp.
    vec3 b = texture(uBlur, st).rgb;
    float edge = smoothstep(0.04, 0.20, length(c - b));
    c = mix(c, b, skin * uSmooth * (1.0 - edge) * 0.9);
    c += skin * uSmooth * 0.012;

    // Contour: soft shade along cheek hollows, jaw, nose sides and temples.
    float sh = 0.0;
    for (int i = 0; i < $MAX_SEGS; i++) {
        float w = uSegW[i];
        if (w > 0.0) {
            float d = segDist(q, uSeg[i].xy, uSeg[i].zw);
            sh += exp(-d * d / (2.0 * w * w)) * uSegK[i];
        }
    }
    sh = min(sh, 1.0) * uContour * area;
    c *= mix(vec3(1.0), vec3(0.86, 0.78, 0.74), sh * 0.75);

    // Blush: rosy glow on the apples of the cheeks.
    float g = 0.0;
    for (int i = 0; i < 2; i++) {
        float d = length(q - uBlushC[i]);
        g += exp(-d * d / (2.0 * uBlushR * uBlushR));
    }
    g = min(g, 1.0) * uBlush * area;
    c = mix(c, softLight(c, vec3(0.95, 0.50, 0.55)), g * 0.7);
    c = mix(c, c * vec3(1.0, 0.90, 0.91), g * 0.25);

    // Lips: sheer rose tint that keeps your lip texture.
    float lip = m.g * uFade * uLip;
    vec3 tint = setLum(vec3(0.82, 0.32, 0.40), dot(c, W) * 0.97);
    c = mix(c, tint, lip * 0.6);

    // Eyes: lift brightness around the eyes.
    float e = m.b * uFade * uEye;
    c = mix(c, c + (1.0 - c) * 0.18, e);

    // Line-up helper: shows where the overlay sits.
    c = mix(c, vec3(1.0, 0.45, 0.7), m.r * 0.35 * uShowMask);

    o = vec4(clamp(c, 0.0, 1.0), 1.0);
}
"""
}
