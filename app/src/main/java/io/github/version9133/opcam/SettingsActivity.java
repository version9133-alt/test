package io.github.version9133.opcam;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.DialogInterface;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.text.method.ScrollingMovementMethod;
import android.util.TypedValue;
import android.view.MenuItem;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.CompoundButton;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Plain-framework settings screen (no AppCompat / Preference library). */
public final class SettingsActivity extends Activity {

    private static final int[] QUALITY_VALUES = {100, 95, 90, 85, 80};
    private static final String[] QUALITY_LABELS = {"100 (largest)", "95 (recommended)", "90", "85", "80 (smallest)"};
    private static final String[] RES_KEYS = {Settings.VIDEO_RES_2160P, Settings.VIDEO_RES_1080P, Settings.VIDEO_RES_720P};
    private static final String[] RES_LABELS = {"4K UHD (3840×2160)", "Full HD (1920×1080)", "HD (1280×720)"};
    private static final int[] FPS_VALUES = {24, 30, 60};
    private static final String[] FPS_LABELS = {"24 fps", "30 fps", "60 fps"};

    private Settings mSettings;
    private List<LensInfo> mLenses;
    private LensInfo mLens;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_settings);
        if (getActionBar() != null) {
            getActionBar().setDisplayHomeAsUpEnabled(true);
            getActionBar().setTitle(R.string.settings);
        }
        mSettings = new Settings(this);
        mLenses = CameraInfo.getLenses(this, mSettings.probeLenses());
        mLens = CameraInfo.defaultBack(mLenses);
        if (mLens == null && !mLenses.isEmpty()) {
            mLens = mLenses.get(0);
        }

        bindPhoto();
        bindVideo();
        bindEfficiency();
        bindAbout();
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    // ------------------------------------------------------------------------------------------

    private void bindPhoto() {
        boolean heicOk = mLens != null && mLens.heicSupported();
        Switch heic = findViewById(R.id.sw_heic);
        TextView heicSummary = findViewById(R.id.tv_heic_summary);
        heic.setChecked(heicOk && mSettings.heic());
        heic.setEnabled(heicOk);
        heicSummary.setText(heicOk ? R.string.pref_heic_summary : R.string.pref_heic_unsupported);
        bindSwitch(heic, Settings.KEY_HEIC);

        Switch fullRes = findViewById(R.id.sw_full_res);
        TextView fullResSummary = findViewById(R.id.tv_full_res_summary);
        fullRes.setChecked(mSettings.fullRes());
        String fullResText = getString(R.string.pref_full_res_summary);
        if (mLens != null) {
            String max = LensInfo.megapixels(mLens.uhrSupported && mLens.maxUhrJpeg != null
                    ? mLens.maxUhrJpeg : mLens.largestStill());
            fullResText = fullResText + " (" + max + " on " + mLens.label + ")";
        }
        fullResSummary.setText(fullResText);
        bindSwitch(fullRes, Settings.KEY_FULL_RES);

        boolean rawOk = mLens != null && mLens.rawSupported();
        Switch raw = findViewById(R.id.sw_raw);
        TextView rawSummary = findViewById(R.id.tv_raw_summary);
        raw.setChecked(rawOk && mSettings.raw());
        raw.setEnabled(rawOk);
        rawSummary.setText(rawOk ? R.string.pref_raw_summary : R.string.pref_raw_unsupported);
        bindSwitch(raw, Settings.KEY_RAW);

        Spinner quality = findViewById(R.id.sp_quality);
        bindSpinner(quality, QUALITY_LABELS, indexOf(QUALITY_VALUES, mSettings.jpegQuality(), 1),
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        mSettings.putInt(Settings.KEY_JPEG_QUALITY, QUALITY_VALUES[position]);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });

        Switch sound = findViewById(R.id.sw_shutter_sound);
        sound.setChecked(mSettings.shutterSound());
        bindSwitch(sound, Settings.KEY_SHUTTER_SOUND);
    }

    private void bindVideo() {
        Spinner res = findViewById(R.id.sp_video_res);
        final List<String> resKeys = new ArrayList<>();
        List<String> resLabels = new ArrayList<>();
        for (int i = 0; i < RES_KEYS.length; i++) {
            if (mLens == null || supportsRes(RES_KEYS[i])) {
                resKeys.add(RES_KEYS[i]);
                resLabels.add(RES_LABELS[i]);
            }
        }
        if (resKeys.isEmpty()) {
            resKeys.add(Settings.VIDEO_RES_1080P);
            resLabels.add(RES_LABELS[1]);
        }
        int resIdx = Math.max(0, resKeys.indexOf(mSettings.videoRes()));
        bindSpinner(res, resLabels.toArray(new String[0]), resIdx, new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                mSettings.putString(Settings.KEY_VIDEO_RES, resKeys.get(position));
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {
            }
        });

        Spinner fps = findViewById(R.id.sp_video_fps);
        bindSpinner(fps, FPS_LABELS, indexOf(FPS_VALUES, mSettings.videoFps(), 1),
                new AdapterView.OnItemSelectedListener() {
                    @Override
                    public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                        mSettings.putInt(Settings.KEY_VIDEO_FPS, FPS_VALUES[position]);
                    }

                    @Override
                    public void onNothingSelected(AdapterView<?> parent) {
                    }
                });

        boolean hevcOk = VideoRecorder.hasHevcEncoder();
        Switch hevc = findViewById(R.id.sw_hevc);
        TextView hevcSummary = findViewById(R.id.tv_hevc_summary);
        hevc.setChecked(hevcOk && mSettings.hevc());
        hevc.setEnabled(hevcOk);
        hevcSummary.setText(hevcOk ? R.string.pref_hevc_summary : R.string.pref_hevc_unsupported);
        bindSwitch(hevc, Settings.KEY_HEVC);

        Switch stab = findViewById(R.id.sw_stabilization);
        stab.setChecked(mSettings.stabilization());
        bindSwitch(stab, Settings.KEY_STABILIZATION);
    }

    private void bindEfficiency() {
        Switch power = findViewById(R.id.sw_power_saver);
        power.setChecked(mSettings.powerSaver());
        bindSwitch(power, Settings.KEY_POWER_SAVER);

        Switch zsl = findViewById(R.id.sw_zsl);
        zsl.setChecked(mSettings.zsl());
        bindSwitch(zsl, Settings.KEY_ZSL);

        Switch probe = findViewById(R.id.sw_probe_lenses);
        probe.setChecked(mSettings.probeLenses());
        probe.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mSettings.putBoolean(Settings.KEY_PROBE_LENSES, isChecked);
                CameraInfo.invalidate();
            }
        });
    }

    private void bindAbout() {
        findViewById(R.id.row_device_info).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                showDeviceInfo();
            }
        });
        TextView version = findViewById(R.id.tv_version);
        String name = "?";
        try {
            PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0);
            name = pi.versionName;
        } catch (PackageManager.NameNotFoundException ignored) {
            // unreachable for our own package
        }
        version.setText(getString(R.string.version_format, name));
    }

    // ------------------------------------------------------------------------------------------

    private void showDeviceInfo() {
        final String report = CameraInfo.describeAll(this, mLenses);
        TextView tv = new TextView(this);
        tv.setText(report);
        tv.setTypeface(android.graphics.Typeface.MONOSPACE);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f);
        tv.setTextIsSelectable(true);
        tv.setMovementMethod(new ScrollingMovementMethod());
        int pad = Math.round(16 * getResources().getDisplayMetrics().density);
        tv.setPadding(pad, pad, pad, pad);
        ScrollView scroll = new ScrollView(this);
        scroll.addView(tv);
        new AlertDialog.Builder(this)
                .setTitle(Build.MANUFACTURER + " " + Build.MODEL)
                .setView(scroll)
                .setPositiveButton(R.string.ok, null)
                .setNeutralButton(R.string.copy, new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(DialogInterface dialog, int which) {
                        ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
                        if (cm != null) {
                            cm.setPrimaryClip(ClipData.newPlainText("OPCam device info", report));
                            Toast.makeText(SettingsActivity.this, R.string.copied, Toast.LENGTH_SHORT).show();
                        }
                    }
                })
                .show();
    }

    private boolean supportsRes(String key) {
        for (VideoRecorder.Option o : VideoRecorder.options(mLens)) {
            if (o.key.equals(key)) {
                return true;
            }
        }
        return false;
    }

    private void bindSwitch(Switch sw, final String key) {
        sw.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
            @Override
            public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                mSettings.putBoolean(key, isChecked);
            }
        });
    }

    private void bindSpinner(Spinner spinner, String[] labels, int selected,
                             final AdapterView.OnItemSelectedListener listener) {
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, labels);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        spinner.setAdapter(adapter);
        spinner.setSelection(Math.max(0, Math.min(labels.length - 1, selected)), false);
        spinner.setOnItemSelectedListener(listener);
    }

    private static int indexOf(int[] values, int value, int fallback) {
        for (int i = 0; i < values.length; i++) {
            if (values[i] == value) {
                return i;
            }
        }
        return fallback;
    }
}
