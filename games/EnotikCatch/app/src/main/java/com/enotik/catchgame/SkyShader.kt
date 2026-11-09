package com.enotik.catchgame

import android.annotation.TargetApi
import android.graphics.Paint
import android.graphics.RuntimeShader

/**
 * Шейдер неба на AGSL (Android 13+): закат с мерцающими звёздами и северным сиянием.
 * Считается на видеокарте, поэтому почти ничего не стоит процессору.
 */
@TargetApi(33)
class SkyShader(w: Float, horizon: Float) {
    private val shader = RuntimeShader(SRC)
    val paint = Paint()

    init {
        shader.setFloatUniform("res", w, horizon)
        shader.setFloatUniform("time", 0f)
        paint.shader = shader
    }

    fun update(time: Float) {
        shader.setFloatUniform("time", time)
    }

    companion object {
        const val SRC = """
uniform float2 res;
uniform float time;

half4 main(float2 fc) {
    float t = clamp(fc.y / res.y, 0.0, 1.0);
    float3 top = float3(0.09, 0.06, 0.26);
    float3 mid = float3(0.89, 0.34, 0.18);
    float3 low = float3(1.0, 0.80, 0.36);
    float3 col = mix(top, mid, smoothstep(0.0, 0.72, t));
    col = mix(col, low, smoothstep(0.68, 1.0, t));

    float2 g = floor(fc / 7.0);
    float hsh = fract(sin(dot(g, float2(12.9898, 78.233))) * 43758.5453);
    float tw = 0.55 + 0.45 * sin(time * 2.2 + hsh * 40.0);
    float star = step(0.9962, hsh) * tw * (1.0 - smoothstep(0.0, 0.62, t));
    col += float3(star);

    float band = 0.5 + 0.5 * sin(fc.x / res.x * 6.0 + time * 0.35 + sin(time * 0.2) * 2.0);
    float mask = smoothstep(0.02, 0.28, t) * (1.0 - smoothstep(0.28, 0.62, t));
    col += float3(0.05, 0.30, 0.38) * band * mask * 0.55;

    return half4(half3(col), 1.0);
}
"""
    }
}
