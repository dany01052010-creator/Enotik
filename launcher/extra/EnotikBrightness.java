package net.kdt.pojavlaunch.utils;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.widget.FrameLayout;

import androidx.preference.PreferenceManager;

/**
 * Enotik: яркость ниже минимума системы.
 * 100 % = как в системе (ничего не меняем). Меньше — ставим яркость окна и,
 * когда она уже на минимуме экрана, добавляем чёрную плёнку поверх (так можно затемнить сильнее,
 * чем позволяет ползунок системы). Работает только внутри лаунчера и игры, системные настройки не трогает.
 */
public final class EnotikBrightness {
    public static final String KEY = "enotikBrightness";
    private static final String TAG = "enotik_dim";

    private EnotikBrightness() {}

    public static int load(Activity a) {
        try {
            SharedPreferences p = PreferenceManager.getDefaultSharedPreferences(a);
            return p.getInt(KEY, 100);
        } catch (Throwable t) {
            return 100;
        }
    }

    public static void apply(Activity a) {
        apply(a, load(a));
    }

    public static void apply(Activity a, int percent) {
        if (a == null || a.isFinishing()) return;
        if (percent < 1) percent = 1;
        if (percent > 100) percent = 100;
        Window w = a.getWindow();
        WindowManager.LayoutParams lp = w.getAttributes();
        float overlayAlpha = 0f;
        if (percent >= 100) {
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE;
        } else {
            // 100..30 % — яркость окна от 1.0 до ~0.01; ниже 30 % окно уже на минимуме, дальше темнит плёнка
            if (percent > 30) {
                lp.screenBrightness = Math.max(0.01f, (percent - 30) / 70f);
            } else {
                lp.screenBrightness = 0.01f;
                overlayAlpha = 0.85f * (30 - percent) / 29f;
            }
        }
        w.setAttributes(lp);

        View decor = w.getDecorView();
        if (!(decor instanceof ViewGroup)) return;
        ViewGroup g = (ViewGroup) decor;
        View dim = g.findViewWithTag(TAG);
        if (overlayAlpha <= 0f) {
            if (dim != null) g.removeView(dim);
            return;
        }
        if (dim == null) {
            dim = new View(a);
            dim.setTag(TAG);
            dim.setBackgroundColor(Color.BLACK);
            dim.setClickable(false);
            dim.setFocusable(false);
            g.addView(dim, new FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        }
        dim.setAlpha(overlayAlpha);
        dim.bringToFront();
    }
}
