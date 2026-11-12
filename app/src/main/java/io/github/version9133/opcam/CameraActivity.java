package io.github.version9133.opcam;

import android.Manifest;
import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.Typeface;
import android.hardware.SensorManager;
import android.hardware.camera2.CameraMetadata;
import android.media.MediaActionSound;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.util.Range;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.OrientationEventListener;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowInsetsController;
import android.view.WindowManager;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Full-screen viewfinder. All heavy lifting is delegated to {@link CameraController}. */
public final class CameraActivity extends Activity implements CameraController.Listener {

    private static final int REQ_PERMISSIONS = 1;
    private static final int[] TIMER_STEPS = {0, 3, 10};

    private Settings mSettings;
    private MediaSaver mSaver;
    private CameraController mCam;
    private List<LensInfo> mLenses = new ArrayList<>();
    private LensInfo mCurrentLens;
    private float mPendingZoom;
    private int mPendingMode = -1;
    private boolean mPermissionRequested;
    private int mFatalErrors;

    private AutoFitSurfaceView mPreview;
    private CameraOverlayView mOverlay;
    private View mCaptureFlash;
    private ImageButton mBtnFlash;
    private ImageButton mBtnTimer;
    private ImageButton mBtnGrid;
    private ImageButton mBtnSettings;
    private ImageButton mBtnSwitch;
    private ImageButton mBtnSnapshot;
    private ImageView mGallery;
    private TextView mFormatChip;
    private TextView mInfoText;
    private TextView mRecTimer;
    private TextView mCountdown;
    private TextView mProcessing;
    private LinearLayout mZoomRow;
    private View mEvRow;
    private SeekBar mEvBar;
    private TextView mEvValue;
    private LinearLayout mModeRow;
    private ShutterButton mShutter;
    private View mPermissionPanel;

    private final Handler mUi = new Handler(Looper.getMainLooper());
    private MediaActionSound mSound;
    private OrientationEventListener mOrientationListener;
    private int mUiRotation;
    private float mCurrentIconRotation;
    private ScaleGestureDetector mScaleDetector;
    private GestureDetector mTapDetector;
    private int mTimerSeconds;
    private boolean mCountingDown;
    private int mCountdownLeft;
    private Uri mLastMedia;
    private String mLastMime;
    private int mEvLower;
    private float mEvStep;
    private final List<ZoomOption> mZoomOptions = new ArrayList<>();
    private final List<TextView> mModeChips = new ArrayList<>();
    private final List<Integer> mModeChipModes = new ArrayList<>();

    private static final class ZoomOption {
        final LensInfo lens;
        final float ratio;
        final String label;
        TextView view;

        ZoomOption(LensInfo lens, float ratio, String label) {
            this.lens = lens;
            this.ratio = ratio;
            this.label = label;
        }

        float effective() {
            return lens.zoomFactor * ratio;
        }
    }

    // =========================================================================================
    // Lifecycle
    // =========================================================================================

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (Build.VERSION.SDK_INT >= 28) {
            WindowManager.LayoutParams lp = w.getAttributes();
            lp.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
            w.setAttributes(lp);
        }
        setContentView(R.layout.activity_camera);

        mSettings = new Settings(this);
        mSaver = new MediaSaver(this);
        mTimerSeconds = mSettings.timer();

        bindViews();
        mCam = new CameraController(this, mSettings, mSaver, this);
        mCam.attachPreview(mPreview);
        setupGestures();
        handleIntent(getIntent());
        updateFlashIcon();
        updateTimerIcon();
        mOverlay.setGridEnabled(mSettings.grid());
        updateGridIcon();

        mOrientationListener = new OrientationEventListener(this, SensorManager.SENSOR_DELAY_NORMAL) {
            @Override
            public void onOrientationChanged(int orientation) {
                if (orientation == ORIENTATION_UNKNOWN) {
                    return;
                }
                mCam.setDeviceRotation(orientation);
                int snapped = ((orientation + 45) / 90 * 90) % 360;
                if (snapped != mUiRotation) {
                    mUiRotation = snapped;
                    rotateIcons(snapped);
                }
            }
        };
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        handleIntent(intent);
    }

    private void handleIntent(Intent intent) {
        if (intent != null && MediaStore.INTENT_ACTION_VIDEO_CAMERA.equals(intent.getAction())) {
            mPendingMode = CameraController.MODE_VIDEO;
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        applyImmersive();
        mSound = new MediaActionSound();
        mSound.load(MediaActionSound.SHUTTER_CLICK);
        mSound.load(MediaActionSound.START_VIDEO_RECORDING);
        mSound.load(MediaActionSound.STOP_VIDEO_RECORDING);
        if (mOrientationListener.canDetectOrientation()) {
            mOrientationListener.enable();
        }
        mFatalErrors = 0;
        if (hasPermission(Manifest.permission.CAMERA)) {
            showPermissionPanel(false);
            startCamera();
        } else if (!mPermissionRequested) {
            mPermissionRequested = true;
            requestPermissions(requiredPermissions(), REQ_PERMISSIONS);
        } else {
            showPermissionPanel(true);
        }
    }

    @Override
    protected void onPause() {
        cancelCountdown();
        mUi.removeCallbacks(mRecTick);
        mOrientationListener.disable();
        mCam.stop();
        if (mSound != null) {
            mSound.release();
            mSound = null;
        }
        super.onPause();
    }

    @Override
    protected void onDestroy() {
        mCam.release();
        mSaver.shutdown();
        super.onDestroy();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) {
            applyImmersive();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_PERMISSIONS) {
            return;
        }
        if (hasPermission(Manifest.permission.CAMERA)) {
            showPermissionPanel(false);
            startCamera();
        } else {
            showPermissionPanel(true);
        }
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        switch (keyCode) {
            case KeyEvent.KEYCODE_VOLUME_DOWN:
            case KeyEvent.KEYCODE_VOLUME_UP:
            case KeyEvent.KEYCODE_CAMERA:
                if (event.getRepeatCount() == 0) {
                    onShutterClicked();
                }
                return true;
            default:
                return super.onKeyDown(keyCode, event);
        }
    }

    @Override
    public boolean onKeyUp(int keyCode, KeyEvent event) {
        if (keyCode == KeyEvent.KEYCODE_VOLUME_DOWN || keyCode == KeyEvent.KEYCODE_VOLUME_UP
                || keyCode == KeyEvent.KEYCODE_CAMERA) {
            return true;
        }
        return super.onKeyUp(keyCode, event);
    }

    @Override
    public void onBackPressed() {
        if (mCam.isRecording()) {
            mCam.toggleRecording();
            return;
        }
        super.onBackPressed();
    }

    // =========================================================================================
    // Setup
    // =========================================================================================

    private void bindViews() {
        mPreview = findViewById(R.id.preview);
        mOverlay = findViewById(R.id.overlay);
        mCaptureFlash = findViewById(R.id.capture_flash);
        mBtnFlash = findViewById(R.id.btn_flash);
        mBtnTimer = findViewById(R.id.btn_timer);
        mBtnGrid = findViewById(R.id.btn_grid);
        mBtnSettings = findViewById(R.id.btn_settings);
        mBtnSwitch = findViewById(R.id.btn_switch);
        mBtnSnapshot = findViewById(R.id.btn_snapshot);
        mGallery = findViewById(R.id.btn_gallery);
        mFormatChip = findViewById(R.id.format_chip);
        mInfoText = findViewById(R.id.info_text);
        mRecTimer = findViewById(R.id.rec_timer);
        mCountdown = findViewById(R.id.countdown);
        mProcessing = findViewById(R.id.processing);
        mZoomRow = findViewById(R.id.zoom_row);
        mEvRow = findViewById(R.id.ev_row);
        mEvBar = findViewById(R.id.ev_bar);
        mEvValue = findViewById(R.id.ev_value);
        mModeRow = findViewById(R.id.mode_row);
        mShutter = findViewById(R.id.btn_shutter);
        mPermissionPanel = findViewById(R.id.permission_panel);

        mGallery.setClipToOutline(true);
        mGallery.setOutlineProvider(new ViewOutlineProvider() {
            @Override
            public void getOutline(View view, Outline outline) {
                outline.setOval(0, 0, view.getWidth(), view.getHeight());
            }
        });

        mShutter.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                onShutterClicked();
            }
        });
        mBtnSnapshot.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                mCam.capturePhoto();
            }
        });
        mBtnSwitch.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                switchFacing();
            }
        });
        mBtnFlash.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleFlash();
            }
        });
        mBtnTimer.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                cycleTimer();
            }
        });
        mBtnGrid.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                boolean on = !mOverlay.isGridEnabled();
                mOverlay.setGridEnabled(on);
                mSettings.putBoolean(Settings.KEY_GRID, on);
                updateGridIcon();
            }
        });
        View.OnClickListener openSettings = new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                if (!mCam.isRecording()) {
                    startActivity(new Intent(CameraActivity.this, SettingsActivity.class));
                }
            }
        };
        mBtnSettings.setOnClickListener(openSettings);
        mFormatChip.setOnClickListener(openSettings);
        mGallery.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                openGallery();
            }
        });
        findViewById(R.id.btn_grant).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestPermissions(requiredPermissions(), REQ_PERMISSIONS);
            }
        });
        findViewById(R.id.btn_app_settings).setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                Intent i = new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + getPackageName()));
                startActivity(i);
            }
        });
        mEvBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                int index = mEvLower + progress;
                mEvValue.setText(String.format(Locale.US, "%+.1f", index * mEvStep));
                if (fromUser) {
                    mCam.setExposureIndex(index);
                }
            }

            @Override
            public void onStartTrackingTouch(SeekBar seekBar) {
            }

            @Override
            public void onStopTrackingTouch(SeekBar seekBar) {
            }
        });

        // Keep the overlay exactly on top of the (aspect-fitted) preview: both views are centred in
        // the root FrameLayout, so giving the overlay the preview's exact size makes them coincide.
        mPreview.addOnLayoutChangeListener(new View.OnLayoutChangeListener() {
            @Override
            public void onLayoutChange(View v, int left, int top, int right, int bottom, int oldLeft, int oldTop,
                                       int oldRight, int oldBottom) {
                int w = right - left;
                int h = bottom - top;
                android.view.ViewGroup.LayoutParams lp = mOverlay.getLayoutParams();
                if (w > 0 && h > 0 && lp != null && (lp.width != w || lp.height != h)) {
                    lp.width = w;
                    lp.height = h;
                    mOverlay.setLayoutParams(lp);
                }
            }
        });
    }

    private void setupGestures() {
        mScaleDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                mCam.setZoom(mCam.getZoom() * detector.getScaleFactor());
                return true;
            }
        });
        mTapDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapUp(MotionEvent e) {
                float x = e.getX();
                float y = e.getY();
                int w = mOverlay.getWidth();
                int h = mOverlay.getHeight();
                if (w > 0 && h > 0) {
                    mOverlay.showFocusRing(x, y);
                    mCam.focusAt(x / w, y / h);
                }
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                float z = mCam.getZoom();
                mCam.setZoom(Math.abs(z - 1f) < 0.05f ? Math.min(2f, mCam.getMaxZoom()) : 1f);
                return true;
            }
        });
        mOverlay.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View v, MotionEvent event) {
                mScaleDetector.onTouchEvent(event);
                if (!mScaleDetector.isInProgress()) {
                    mTapDetector.onTouchEvent(event);
                }
                return true;
            }
        });
    }

    private void applyImmersive() {
        Window w = getWindow();
        if (Build.VERSION.SDK_INT >= 30) {
            w.setDecorFitsSystemWindows(false);
            WindowInsetsController c = w.getInsetsController();
            if (c != null) {
                c.hide(WindowInsets.Type.systemBars());
                c.setSystemBarsBehavior(WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
            }
        } else {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                    | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_FULLSCREEN
                    | View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY);
        }
    }

    private String[] requiredPermissions() {
        if (Build.VERSION.SDK_INT <= 28) {
            return new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE};
        }
        return new String[]{Manifest.permission.CAMERA, Manifest.permission.RECORD_AUDIO};
    }

    private boolean hasPermission(String permission) {
        return checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED;
    }

    private void showPermissionPanel(boolean show) {
        mPermissionPanel.setVisibility(show ? View.VISIBLE : View.GONE);
    }

    // =========================================================================================
    // Camera start / lens handling
    // =========================================================================================

    private void startCamera() {
        mLenses = CameraInfo.getLenses(this, mSettings.probeLenses());
        if (mLenses.isEmpty()) {
            Toast.makeText(this, "No camera found", Toast.LENGTH_LONG).show();
            return;
        }
        LensInfo lens = null;
        if (mSettings.lastFacingFront()) {
            lens = CameraInfo.find(mLenses, mSettings.lastFrontCamera());
            if (lens == null) {
                lens = CameraInfo.defaultFront(mLenses);
            }
        } else {
            lens = CameraInfo.find(mLenses, mSettings.lastBackCamera());
        }
        if (lens == null) {
            lens = CameraInfo.defaultBack(mLenses);
        }
        mCurrentLens = lens;
        mCam.setAudioEnabled(hasPermission(Manifest.permission.RECORD_AUDIO));
        if (mPendingMode >= 0) {
            mCam.setMode(mPendingMode);
            mPendingMode = -1;
        }
        buildZoomChips();
        buildModeChips();
        mCam.start(lens);
        if (mLastMedia == null) {
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final Uri latest = mSaver.queryLatestMedia();
                    if (latest != null) {
                        mUi.post(new Runnable() {
                            @Override
                            public void run() {
                                if (mLastMedia == null) {
                                    mLastMedia = latest;
                                    loadGalleryThumb(latest);
                                }
                            }
                        });
                    }
                }
            }, "OPCam-LatestMedia").start();
        }
    }

    private void switchFacing() {
        if (mCam.isRecording() || mLenses.isEmpty()) {
            return;
        }
        boolean toFront = mCurrentLens == null || !mCurrentLens.isFront();
        LensInfo target;
        if (toFront) {
            target = CameraInfo.find(mLenses, mSettings.lastFrontCamera());
            if (target == null) {
                target = CameraInfo.defaultFront(mLenses);
            }
        } else {
            target = CameraInfo.find(mLenses, mSettings.lastBackCamera());
            if (target == null) {
                target = CameraInfo.defaultBack(mLenses);
            }
        }
        if (target == null) {
            return;
        }
        mSettings.putBoolean(Settings.KEY_LAST_FACING_FRONT, target.isFront());
        switchToLens(target, 0f);
        mBtnSwitch.animate().rotationBy(180f).setDuration(300L).start();
    }

    private void switchToLens(LensInfo lens, float zoomAfterSwitch) {
        mCurrentLens = lens;
        mPendingZoom = zoomAfterSwitch;
        mSettings.putString(lens.isFront() ? Settings.KEY_LAST_FRONT : Settings.KEY_LAST_BACK, lens.id);
        mOverlay.hideFocusRing();
        buildZoomChips();
        buildModeChips();
        mCam.switchLens(lens);
    }

    // =========================================================================================
    // Chips
    // =========================================================================================

    private void buildZoomChips() {
        mZoomRow.removeAllViews();
        mZoomOptions.clear();
        LensInfo current = mCurrentLens;
        if (current == null || current.isFront()) {
            mZoomRow.setVisibility(View.GONE);
            return;
        }
        List<LensInfo> backs = CameraInfo.sameFacing(mLenses, CameraMetadata.LENS_FACING_BACK);
        LensInfo main = CameraInfo.defaultBack(mLenses);
        boolean hasUltraWide = false;
        boolean hasTele = false;
        for (LensInfo l : backs) {
            if (l.zoomFactor < 0.95f) {
                hasUltraWide = true;
            }
            if (l.zoomFactor > 1.8f) {
                hasTele = true;
            }
        }
        if (main != null && !hasUltraWide && main.zoomRatioRange != null && main.zoomRatioRange.getLower() < 0.95f) {
            float lower = main.zoomRatioRange.getLower();
            mZoomOptions.add(new ZoomOption(main, lower, CameraInfo.formatZoom(lower)));
        }
        for (LensInfo l : backs) {
            mZoomOptions.add(new ZoomOption(l, 1f, l.label));
        }
        if (main != null && !hasTele) {
            float max = main.zoomRatioRange != null ? main.zoomRatioRange.getUpper() : main.maxDigitalZoom;
            if (max >= 2f) {
                mZoomOptions.add(new ZoomOption(main, 2f, "2x"));
            }
        }
        // Sort by effective magnification: 0.6x, 1x, 2x, 3x ...
        for (int i = 1; i < mZoomOptions.size(); i++) {
            ZoomOption o = mZoomOptions.get(i);
            int j = i - 1;
            while (j >= 0 && mZoomOptions.get(j).effective() > o.effective()) {
                mZoomOptions.set(j + 1, mZoomOptions.get(j));
                j--;
            }
            mZoomOptions.set(j + 1, o);
        }
        if (mZoomOptions.size() <= 1) {
            mZoomRow.setVisibility(View.GONE);
            return;
        }
        for (final ZoomOption option : mZoomOptions) {
            TextView chip = makeChip(option.label, dp(40));
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (option.lens != mCurrentLens) {
                        switchToLens(option.lens, option.ratio);
                    } else {
                        mCam.setZoom(option.ratio);
                    }
                }
            });
            option.view = chip;
            mZoomRow.addView(chip);
        }
        mZoomRow.setVisibility(View.VISIBLE);
        updateZoomChips(mCam.getZoom());
    }

    private void updateZoomChips(float zoom) {
        if (mZoomOptions.isEmpty()) {
            return;
        }
        ZoomOption nearest = null;
        for (ZoomOption o : mZoomOptions) {
            if (o.lens != mCurrentLens) {
                continue;
            }
            if (nearest == null || Math.abs(o.ratio - zoom) < Math.abs(nearest.ratio - zoom)) {
                nearest = o;
            }
        }
        for (ZoomOption o : mZoomOptions) {
            boolean active = o == nearest;
            if (o.view == null) {
                continue;
            }
            if (active && Math.abs(o.ratio - zoom) > 0.05f) {
                o.view.setText(CameraController.zoomLabel(o.lens.zoomFactor * zoom));
            } else {
                o.view.setText(o.label);
            }
            o.view.setBackgroundResource(active ? R.drawable.bg_chip_selected : R.drawable.bg_chip);
            o.view.setTextColor(active ? Color.BLACK : Color.WHITE);
        }
    }

    private void buildModeChips() {
        mModeRow.removeAllViews();
        mModeChips.clear();
        mModeChipModes.clear();
        List<Integer> modes = new ArrayList<>();
        modes.add(CameraController.MODE_PHOTO);
        modes.add(CameraController.MODE_VIDEO);
        if (mCurrentLens != null) {
            for (int ext : mCurrentLens.extensions) {
                int mode = CameraController.modeForExtension(ext);
                if (mode >= 0) {
                    modes.add(mode);
                }
            }
        }
        for (final int mode : modes) {
            TextView tv = new TextView(this);
            tv.setText(CameraController.modeLabel(this, mode));
            tv.setTextSize(13f);
            tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
            tv.setGravity(Gravity.CENTER);
            tv.setPadding(dp(12), dp(8), dp(12), dp(8));
            tv.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    if (!mCam.isRecording()) {
                        mCam.setMode(mode);
                    }
                }
            });
            mModeRow.addView(tv);
            mModeChips.add(tv);
            mModeChipModes.add(mode);
        }
        updateModeChips(mCam.getMode());
    }

    private void updateModeChips(int mode) {
        for (int i = 0; i < mModeChips.size(); i++) {
            boolean active = mModeChipModes.get(i) == mode;
            TextView tv = mModeChips.get(i);
            tv.setTextColor(active ? getColor(R.color.accent) : getColor(R.color.white_70));
            tv.setAlpha(active ? 1f : 0.9f);
        }
        boolean video = mode == CameraController.MODE_VIDEO;
        mShutter.setState(video ? ShutterButton.STATE_VIDEO : ShutterButton.STATE_PHOTO);
        mEvRow.setVisibility(CameraController.isExtensionMode(mode) ? View.INVISIBLE : View.VISIBLE);
        mBtnTimer.setVisibility(video ? View.INVISIBLE : View.VISIBLE);
        if (video && !hasPermission(Manifest.permission.RECORD_AUDIO)) {
            Toast.makeText(this, R.string.no_audio_permission, Toast.LENGTH_SHORT).show();
        }
    }

    private TextView makeChip(String text, int minWidth) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(Color.WHITE);
        tv.setTextSize(12f);
        tv.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        tv.setGravity(Gravity.CENTER);
        tv.setMinWidth(minWidth);
        tv.setBackgroundResource(R.drawable.bg_chip);
        tv.setPadding(dp(10), 0, dp(10), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, dp(30));
        lp.setMarginEnd(dp(8));
        lp.setMarginStart(dp(8));
        tv.setLayoutParams(lp);
        return tv;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    // =========================================================================================
    // Controls
    // =========================================================================================

    private void onShutterClicked() {
        if (mPermissionPanel.getVisibility() == View.VISIBLE) {
            return;
        }
        if (mCountingDown) {
            cancelCountdown();
            return;
        }
        mShutter.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        if (mCam.getMode() == CameraController.MODE_VIDEO) {
            mCam.toggleRecording();
            return;
        }
        if (mTimerSeconds > 0) {
            startCountdown(mTimerSeconds);
        } else {
            mCam.capturePhoto();
        }
    }

    private void cycleFlash() {
        int next = (mCam.getFlash() + 1) % 4;
        mCam.setFlash(next);
        updateFlashIcon();
        int[] names = {R.string.flash_off, R.string.flash_auto, R.string.flash_on, R.string.flash_torch};
        Toast.makeText(this, names[next], Toast.LENGTH_SHORT).show();
    }

    private void updateFlashIcon() {
        int[] icons = {R.drawable.ic_flash_off, R.drawable.ic_flash_auto, R.drawable.ic_flash_on, R.drawable.ic_torch};
        int flash = mCam != null ? mCam.getFlash() : mSettings.flash();
        mBtnFlash.setImageResource(icons[Math.max(0, Math.min(3, flash))]);
    }

    private void cycleTimer() {
        int idx = 0;
        for (int i = 0; i < TIMER_STEPS.length; i++) {
            if (TIMER_STEPS[i] == mTimerSeconds) {
                idx = i;
            }
        }
        mTimerSeconds = TIMER_STEPS[(idx + 1) % TIMER_STEPS.length];
        mSettings.putInt(Settings.KEY_TIMER, mTimerSeconds);
        updateTimerIcon();
        Toast.makeText(this, mTimerSeconds == 0 ? getString(R.string.timer_off)
                : getString(R.string.timer_seconds, mTimerSeconds), Toast.LENGTH_SHORT).show();
    }

    private void updateTimerIcon() {
        mBtnTimer.setAlpha(mTimerSeconds > 0 ? 1f : 0.55f);
        mBtnTimer.setImageTintList(android.content.res.ColorStateList.valueOf(
                mTimerSeconds > 0 ? getColor(R.color.accent) : Color.WHITE));
    }

    private void updateGridIcon() {
        boolean on = mOverlay.isGridEnabled();
        mBtnGrid.setAlpha(on ? 1f : 0.55f);
        mBtnGrid.setImageTintList(android.content.res.ColorStateList.valueOf(on ? getColor(R.color.accent) : Color.WHITE));
    }

    private void startCountdown(int seconds) {
        mCountingDown = true;
        mCountdownLeft = seconds;
        mCountdown.setVisibility(View.VISIBLE);
        mCountdown.setText(String.valueOf(seconds));
        mUi.postDelayed(mCountdownTick, 1000L);
    }

    private final Runnable mCountdownTick = new Runnable() {
        @Override
        public void run() {
            if (!mCountingDown) {
                return;
            }
            mCountdownLeft--;
            if (mCountdownLeft <= 0) {
                mCountingDown = false;
                mCountdown.setVisibility(View.GONE);
                mCam.capturePhoto();
                return;
            }
            mCountdown.setText(String.valueOf(mCountdownLeft));
            mUi.postDelayed(this, 1000L);
        }
    };

    private void cancelCountdown() {
        mCountingDown = false;
        mUi.removeCallbacks(mCountdownTick);
        mCountdown.setVisibility(View.GONE);
    }

    private final Runnable mRecTick = new Runnable() {
        @Override
        public void run() {
            if (!mCam.isRecording()) {
                return;
            }
            long s = mCam.getRecordingMillis() / 1000L;
            mRecTimer.setText(String.format(Locale.US, "● %02d:%02d", s / 60, s % 60));
            mUi.postDelayed(this, 500L);
        }
    };

    private void openGallery() {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        if (mLastMedia != null) {
            intent.setDataAndType(mLastMedia, mLastMime != null ? mLastMime : "image/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } else {
            intent.setDataAndType(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, "image/*");
        }
        try {
            startActivity(intent);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "No gallery app found", Toast.LENGTH_SHORT).show();
        }
    }

    private void loadGalleryThumb(Uri uri) {
        mSaver.loadThumbnail(uri, dp(52), new MediaSaver.BitmapCallback() {
            @Override
            public void onBitmap(Bitmap bitmap) {
                if (bitmap != null) {
                    mGallery.setPadding(0, 0, 0, 0);
                    mGallery.setImageBitmap(bitmap);
                }
            }
        });
    }

    private void rotateIcons(int deviceRotation) {
        float target = -deviceRotation;
        float delta = target - mCurrentIconRotation;
        while (delta > 180f) {
            delta -= 360f;
        }
        while (delta < -180f) {
            delta += 360f;
        }
        mCurrentIconRotation += delta;
        View[] icons = {mBtnFlash, mBtnTimer, mBtnGrid, mBtnSettings, mBtnSwitch, mBtnSnapshot, mGallery};
        for (View v : icons) {
            v.animate().rotation(mCurrentIconRotation).setDuration(250L).start();
        }
    }

    // =========================================================================================
    // CameraController.Listener (UI thread)
    // =========================================================================================

    @Override
    public void onCameraReady(LensInfo lens, String formatSummary, String hardwareSummary, boolean flashSupported,
                              float minZoom, float maxZoom, float zoom, Range<Integer> evRange, float evStep) {
        mFormatChip.setText(formatSummary);
        mInfoText.setText(hardwareSummary);
        mInfoText.setVisibility(View.VISIBLE);
        mBtnFlash.setVisibility(flashSupported ? View.VISIBLE : View.INVISIBLE);
        mEvLower = evRange.getLower();
        mEvStep = evStep;
        int span = evRange.getUpper() - evRange.getLower();
        mEvBar.setEnabled(span > 0 && evStep > 0);
        mEvBar.setMax(Math.max(1, span));
        mEvBar.setProgress(-mEvLower);
        mEvValue.setText("0.0");
        if (mPendingZoom > 0f) {
            float z = mPendingZoom;
            mPendingZoom = 0f;
            mCam.setZoom(z);
        } else {
            updateZoomChips(zoom);
        }
        updateModeChips(mCam.getMode());
        mBtnSnapshot.setVisibility(View.GONE);
        mGallery.setVisibility(View.VISIBLE);
    }

    @Override
    public void onCameraError(String message, boolean fatal) {
        Toast.makeText(this, getString(R.string.error_prefix, message), Toast.LENGTH_LONG).show();
        if (!fatal) {
            return;
        }
        mFatalErrors++;
        LensInfo fallback = CameraInfo.defaultBack(mLenses);
        if (mCurrentLens != null && fallback != null && mCurrentLens != fallback && mFatalErrors <= 2) {
            Toast.makeText(this, getString(R.string.lens_unavailable, mCurrentLens.id), Toast.LENGTH_LONG).show();
            mSettings.putBoolean(Settings.KEY_LAST_FACING_FRONT, false);
            switchToLens(fallback, 0f);
        } else if (mFatalErrors <= 2 && mCurrentLens != null) {
            final LensInfo retry = mCurrentLens;
            mUi.postDelayed(new Runnable() {
                @Override
                public void run() {
                    if (!isFinishing()) {
                        mCam.start(retry);
                    }
                }
            }, 800L);
        } else {
            mInfoText.setText(message);
            mInfoText.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onModeChanged(int mode) {
        updateModeChips(mode);
        mOverlay.hideFocusRing();
    }

    @Override
    public void onShutter() {
        if (mSound != null && mSettings.shutterSound() && mCam.getMode() != CameraController.MODE_VIDEO) {
            mSound.play(MediaActionSound.SHUTTER_CLICK);
        }
        mCaptureFlash.setVisibility(View.VISIBLE);
        mCaptureFlash.setAlpha(0.7f);
        mCaptureFlash.animate().alpha(0f).setDuration(160L).withEndAction(new Runnable() {
            @Override
            public void run() {
                mCaptureFlash.setVisibility(View.GONE);
            }
        }).start();
    }

    @Override
    public void onCaptureBusy(boolean busy) {
        if (!busy) {
            mProcessing.setVisibility(View.GONE);
        } else if (CameraController.isExtensionMode(mCam.getMode())) {
            mProcessing.setText(R.string.processing);
            mProcessing.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onProcessingProgress(int percent) {
        mProcessing.setText(percent < 0 ? getString(R.string.processing)
                : getString(R.string.processing_percent, percent));
        mProcessing.setVisibility(View.VISIBLE);
    }

    @Override
    public void onMediaSaved(Uri uri, String mime) {
        mLastMedia = uri;
        mLastMime = mime;
        loadGalleryThumb(uri);
        if (mime != null && mime.startsWith("video/")) {
            Toast.makeText(this, R.string.saved_video, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onFocusResult(boolean focused) {
        mOverlay.setFocusResult(focused);
    }

    @Override
    public void onRecordingStateChanged(boolean recording) {
        mShutter.setState(recording ? ShutterButton.STATE_RECORDING : ShutterButton.STATE_VIDEO);
        mRecTimer.setVisibility(recording ? View.VISIBLE : View.GONE);
        mInfoText.setVisibility(recording ? View.GONE : View.VISIBLE);
        mModeRow.setVisibility(recording ? View.INVISIBLE : View.VISIBLE);
        mBtnSwitch.setVisibility(recording ? View.INVISIBLE : View.VISIBLE);
        mBtnSettings.setVisibility(recording ? View.INVISIBLE : View.VISIBLE);
        mFormatChip.setVisibility(recording ? View.INVISIBLE : View.VISIBLE);
        mGallery.setVisibility(recording ? View.GONE : View.VISIBLE);
        mBtnSnapshot.setVisibility(recording ? View.VISIBLE : View.GONE);
        if (mSound != null && mSettings.shutterSound()) {
            mSound.play(recording ? MediaActionSound.START_VIDEO_RECORDING : MediaActionSound.STOP_VIDEO_RECORDING);
        }
        mUi.removeCallbacks(mRecTick);
        if (recording) {
            mRecTimer.setText("● 00:00");
            mUi.post(mRecTick);
        }
    }

    @Override
    public void onZoomChanged(float ratio) {
        updateZoomChips(ratio);
    }
}
