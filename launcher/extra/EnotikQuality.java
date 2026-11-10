package net.kdt.pojavlaunch.utils;

import android.content.Context;

import androidx.preference.PreferenceManager;

/**
 * Enotik: пресеты качества графики. Перед запуском игры записывает значения в options.txt.
 * 0 = не трогать настройки игры. Ключи, которых нет в какой-то версии, Minecraft просто игнорирует,
 * поэтому пресеты работают на всех версиях.
 */
public final class EnotikQuality {
    public static final String KEY = "enotikQuality";

    private EnotikQuality() {}

    //                         1 мин   2 низк  3 сред  4 выс   5 ультра
    private static final String[] RENDER = {"2", "4", "6", "10", "16"};
    private static final String[] SIM = {"5", "5", "6", "8", "12"};
    private static final String[] FPS = {"30", "40", "60", "90", "120"};
    private static final String[] FANCY = {"false", "false", "true", "true", "true"};
    private static final String[] GMODE = {"0", "0", "1", "1", "1"};
    private static final String[] PART = {"2", "2", "1", "0", "0"};
    private static final String[] AO = {"false", "false", "true", "true", "true"};
    private static final String[] SHADOW = {"false", "false", "false", "true", "true"};
    private static final String[] MIP = {"0", "0", "2", "4", "4"};
    private static final String[] BLEND = {"0", "0", "1", "3", "5"};
    private static final String[] CLOUD = {"false", "false", "fast", "true", "true"};
    private static final String[] ENT = {"0.5", "0.5", "0.75", "1.0", "1.5"};

    public static void apply(Context c) {
        int level;
        try {
            level = Integer.parseInt(PreferenceManager.getDefaultSharedPreferences(c).getString(KEY, "0"));
        } catch (Throwable t) {
            level = 0;
        }
        if (level < 1 || level > 5) return;
        int i = level - 1;
        MCOptionUtils.set("renderDistance", RENDER[i]);
        MCOptionUtils.set("simulationDistance", SIM[i]);
        MCOptionUtils.set("maxFps", FPS[i]);
        MCOptionUtils.set("fancyGraphics", FANCY[i]);
        MCOptionUtils.set("graphicsMode", GMODE[i]);
        MCOptionUtils.set("particles", PART[i]);
        MCOptionUtils.set("ao", AO[i]);
        MCOptionUtils.set("entityShadows", SHADOW[i]);
        MCOptionUtils.set("mipmapLevels", MIP[i]);
        MCOptionUtils.set("biomeBlendRadius", BLEND[i]);
        MCOptionUtils.set("renderClouds", CLOUD[i]);
        MCOptionUtils.set("entityDistanceScaling", ENT[i]);
        MCOptionUtils.save();
    }
}
