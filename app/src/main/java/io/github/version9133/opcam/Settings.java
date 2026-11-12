package io.github.version9133.opcam;

import android.content.Context;
import android.content.SharedPreferences;

/** Thin, allocation-free wrapper around SharedPreferences. All reads happen on the UI thread. */
public final class Settings {

    public static final String PREFS = "opcam";

    public static final String KEY_HEIC = "heic";
    public static final String KEY_FULL_RES = "full_res";
    public static final String KEY_RAW = "raw";
    public static final String KEY_JPEG_QUALITY = "jpeg_quality";
    public static final String KEY_SHUTTER_SOUND = "shutter_sound";
    public static final String KEY_GRID = "grid";
    public static final String KEY_TIMER = "timer";
    public static final String KEY_FLASH = "flash";
    public static final String KEY_VIDEO_RES = "video_res";
    public static final String KEY_VIDEO_FPS = "video_fps";
    public static final String KEY_HEVC = "hevc";
    public static final String KEY_STABILIZATION = "stabilization";
    public static final String KEY_POWER_SAVER = "power_saver";
    public static final String KEY_ZSL = "zsl";
    public static final String KEY_PROBE_LENSES = "probe_lenses";
    public static final String KEY_LAST_BACK = "last_back_camera";
    public static final String KEY_LAST_FRONT = "last_front_camera";
    public static final String KEY_LAST_FACING_FRONT = "last_facing_front";

    public static final String VIDEO_RES_2160P = "2160p";
    public static final String VIDEO_RES_1080P = "1080p";
    public static final String VIDEO_RES_720P = "720p";

    private final SharedPreferences mPrefs;

    public Settings(Context context) {
        mPrefs = context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public boolean heic() {
        return mPrefs.getBoolean(KEY_HEIC, true);
    }

    public boolean fullRes() {
        return mPrefs.getBoolean(KEY_FULL_RES, false);
    }

    public boolean raw() {
        return mPrefs.getBoolean(KEY_RAW, false);
    }

    public int jpegQuality() {
        return mPrefs.getInt(KEY_JPEG_QUALITY, 95);
    }

    public boolean shutterSound() {
        return mPrefs.getBoolean(KEY_SHUTTER_SOUND, true);
    }

    public boolean grid() {
        return mPrefs.getBoolean(KEY_GRID, false);
    }

    public int timer() {
        return mPrefs.getInt(KEY_TIMER, 0);
    }

    public int flash() {
        return mPrefs.getInt(KEY_FLASH, CameraController.FLASH_AUTO);
    }

    public String videoRes() {
        return mPrefs.getString(KEY_VIDEO_RES, VIDEO_RES_1080P);
    }

    public int videoFps() {
        return mPrefs.getInt(KEY_VIDEO_FPS, 30);
    }

    public boolean hevc() {
        return mPrefs.getBoolean(KEY_HEVC, true);
    }

    public boolean stabilization() {
        return mPrefs.getBoolean(KEY_STABILIZATION, true);
    }

    public boolean powerSaver() {
        return mPrefs.getBoolean(KEY_POWER_SAVER, false);
    }

    public boolean zsl() {
        return mPrefs.getBoolean(KEY_ZSL, true);
    }

    public boolean probeLenses() {
        return mPrefs.getBoolean(KEY_PROBE_LENSES, true);
    }

    public String lastBackCamera() {
        return mPrefs.getString(KEY_LAST_BACK, null);
    }

    public String lastFrontCamera() {
        return mPrefs.getString(KEY_LAST_FRONT, null);
    }

    public boolean lastFacingFront() {
        return mPrefs.getBoolean(KEY_LAST_FACING_FRONT, false);
    }

    public void putBoolean(String key, boolean value) {
        mPrefs.edit().putBoolean(key, value).apply();
    }

    public void putInt(String key, int value) {
        mPrefs.edit().putInt(key, value).apply();
    }

    public void putString(String key, String value) {
        mPrefs.edit().putString(key, value).apply();
    }
}
