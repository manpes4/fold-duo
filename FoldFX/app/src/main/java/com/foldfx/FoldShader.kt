package com.foldfx

/**
 * Shader AGSL (Android 13+) qui produit la transition.
 *
 * progress : 0 = téléphone fermé, 1 = complètement ouvert (piloté par l'angle de charnière).
 * mode     : 0 = écran interne (effet charnière), 1 = écran externe (simple "mise au point").
 * axis     : 0 = charnière verticale (portrait), 1 = charnière horizontale (paysage).
 * side     : +1 si la moitié qui bouge est à droite / en bas, -1 si à gauche / en haut.
 */
object FoldShader {
    const val AGSL = """
uniform shader image;
uniform float2 resolution;
uniform float progress;
uniform float axis;
uniform float side;
uniform float mode;
uniform float intensity;
uniform float time;

float hash(float2 p) {
    return fract(sin(dot(p, float2(127.1, 311.7))) * 43758.5453);
}

float vnoise(float2 p) {
    float2 i = floor(p);
    float2 f = fract(p);
    float a = hash(i);
    float b = hash(i + float2(1.0, 0.0));
    float c = hash(i + float2(0.0, 1.0));
    float d = hash(i + float2(1.0, 1.0));
    float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(a, b, u.x), mix(c, d, u.x), u.y);
}

half4 main(float2 fc) {
    float p = clamp(progress, 0.0, 1.0);
    float e = p * p * (3.0 - 2.0 * p);
    float inv = (1.0 - e) * intensity;

    // Position le long de l'axe perpendiculaire à la charnière
    float sizeAlong = mix(resolution.x, resolution.y, axis);
    float h = sizeAlong * 0.5;
    float pa = mix(fc.x, fc.y, axis);
    float t = (pa - h) / h * side;          // -1..1, > 0 = moitié mobile
    float hingeFx = 1.0 - mode;
    float mobile = step(0.0, t) * hingeFx;
    float td = max(t, 0.0) * hingeFx;

    // Le contenu de la moitié mobile semble sortir de la charnière (étirement)
    float stretch = mix(0.45, 1.0, e);
    float na = mix(pa, h + (pa - h) * stretch, mobile);
    float2 sp = mix(float2(na, fc.y), float2(fc.x, na), axis);

    // Écran externe : léger zoom pendant la mise au point
    float2 center = resolution * 0.5;
    sp = center + (sp - center) * mix(1.0, 0.93, (1.0 - e) * mode);

    // Flou de mise au point (disque) + flou de mouvement (le long du pli)
    float2 dir = mix(float2(side, 0.0), float2(0.0, side), axis);
    float blurR = inv * 34.0;
    float motion = inv * mix(24.0, 70.0 + 160.0 * td, mobile) * hingeFx;

    float4 acc = float4(0.0);
    for (int i = 0; i < 16; i++) {
        float fi = float(i);
        float k = (fi + 0.5) / 16.0;
        float ang = fi * 2.39996;
        float2 disk = float2(cos(ang), sin(ang)) * sqrt(k) * blurR;
        float2 mo = dir * (k - 0.5) * motion;
        acc += float4(image.eval(sp + disk + mo));
    }
    float3 col = acc.rgb / 16.0;

    // Front de dissolution qui balaie la moitié mobile depuis la charnière
    float front = p * 1.35 - 0.1;
    float n = vnoise(fc / 22.0 + float2(time * 0.4, time * 0.2));
    float reveal = 1.0 - smoothstep(front - 0.14, front + 0.14, td + (n - 0.5) * 0.2);
    reveal = mix(1.0, mix(0.12, 1.0, reveal), mobile);

    // Luminosité qui remonte, ombre douce sur le pli, liseré lumineux sur le front
    float bright = mix(0.45, 1.0, e);
    float crease = 1.0 - 0.35 * (1.0 - e) * exp(-abs(t) * 9.0) * hingeFx;
    float glow = exp(-abs(td - front) * 16.0) * (1.0 - e) * 0.35 * mobile;

    float3 outc = col * (bright * crease * reveal) + float3(glow);
    return half4(half3(outc), 1.0);
}
"""
}
