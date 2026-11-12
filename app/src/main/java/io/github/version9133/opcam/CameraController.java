package io.github.version9133.opcam;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCaptureSession;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.CaptureFailure;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.CaptureResult;
import android.hardware.camera2.DngCreator;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.MeteringRectangle;
import android.hardware.camera2.params.OutputConfiguration;
import android.hardware.camera2.params.SessionConfiguration;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.ExifInterface;
import android.media.Image;
import android.media.ImageReader;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.util.Log;
import android.util.LongSparseArray;
import android.util.Range;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Camera2 engine. One persistent capture session per mode (preview + still reader [+ RAW]
 * [+ encoder surface]) so taking a photo or starting a clip never reconfigures the pipeline.
 *
 * <p>Threading: every camera call runs on a dedicated {@code HandlerThread}; image buffers are
 * drained on a second thread; file I/O happens inside {@link MediaSaver}. Public methods may be
 * called from the UI thread - they post to the camera thread. Listener callbacks arrive on the
 * UI thread.</p>
 */
public final class CameraController implements SurfaceHolder.Callback {

    private static final String TAG = "CameraController";

    public static final int MODE_PHOTO = 0;
    public static final int MODE_VIDEO = 1;
    public static final int MODE_NIGHT = 2;
    public static final int MODE_HDR = 3;
    public static final int MODE_PORTRAIT = 4;
    public static final int MODE_BEAUTY = 5;
    public static final int MODE_AUTO = 6;

    public static final int FLASH_OFF = 0;
    public static final int FLASH_AUTO = 1;
    public static final int FLASH_ON = 2;
    public static final int FLASH_TORCH = 3;

    private static final int STATE_PREVIEW = 0;
    private static final int STATE_WAITING_PRECAPTURE = 1;
    private static final int STATE_WAITING_NON_PRECAPTURE = 2;
    private static final int STATE_CAPTURING = 3;

    private static final long PRECAPTURE_TIMEOUT_MS = 1500L;
    private static final long AF_RESET_DELAY_MS = 5000L;
    private static final long RAW_RESULT_TIMEOUT_MS = 4000L;
    private static final long STANDARD_STILL_MAX_PIXELS = 13_500_000L;
    private static final double ASPECT_TOLERANCE = 0.02d;
    private static final int RESULT_HISTORY = 8;

    public interface Listener {
        void onCameraReady(LensInfo lens, String formatSummary, String hardwareSummary, boolean flashSupported,
                           float minZoom, float maxZoom, float zoom, Range<Integer> evRange, float evStep);

        void onCameraError(String message, boolean fatal);

        void onModeChanged(int mode);

        void onShutter();

        void onCaptureBusy(boolean busy);

        /** -1 = indeterminate (vendor pipeline running), 0..100 = progress. */
        void onProcessingProgress(int percent);

        void onMediaSaved(Uri uri, String mime);

        void onFocusResult(boolean focused);

        void onRecordingStateChanged(boolean recording);

        void onZoomChanged(float ratio);
    }

    private final Context mContext;
    private final CameraManager mManager;
    private final Settings mSettings;
    private final MediaSaver mSaver;
    private final Listener mListener;
    private final Handler mMain = new Handler(Looper.getMainLooper());

    private HandlerThread mCamThread;
    private HandlerThread mImgThread;
    private volatile Handler mCamHandler;
    private Handler mImgHandler;
    private final Executor mCamExecutor = new Executor() {
        @Override
        public void execute(Runnable command) {
            Handler h = mCamHandler;
            if (h != null) {
                h.post(command);
            }
        }
    };

    // Preview surface (written on UI thread, read on camera thread)
    private final Object mSurfaceLock = new Object();
    private AutoFitSurfaceView mPreviewView;
    private SurfaceHolder mHolder;
    private Surface mPreviewSurface;
    private Size mSurfaceSize;

    // Camera thread state
    private LensInfo mLens;
    private CameraDevice mDevice;
    private CameraCaptureSession mSession;
    private ExtensionsHelper.ExtSession mExtSession;
    private CaptureRequest.Builder mRepeatingBuilder;
    private Surface mSessionPreviewSurface;
    private ImageReader mStillReader;
    private ImageReader mRawReader;
    private Surface mRecorderSurface;
    private final VideoRecorder mVideo;
    private VideoRecorder.Config mVideoConfig;
    private MediaSaver.PendingVideo mPendingVideo;

    private boolean mOpening;
    private boolean mStopped = true;
    private boolean mConfiguring;
    private boolean mRecording;
    private boolean mRawDisabled;
    private boolean mUhrDisabled;
    private long mRecordStart;

    private int mStillFormat = ImageFormat.JPEG;
    private Size mStillSize;
    private Size mPreviewSize;
    private boolean mUseMaxRes;
    private Range<Integer> mFpsRange;
    private int[] mNrModes;
    private int[] mEdgeModes;
    private int[] mAberrationModes;
    private int[] mDistortionModes;
    private boolean mZoomRatioSupported;
    private boolean mExtZoomSupported;
    private boolean mExtAfSupported;
    private Rect mActiveArray;

    private int mCaptureState = STATE_PREVIEW;
    private long mPrecaptureDeadline;
    private int mAeState = -1;
    private boolean mAfTapActive;
    private boolean mAfWaiting;
    private MeteringRectangle[] mAfRegions;
    private MeteringRectangle[] mAeRegions;
    private int mLastJpegOrientation;

    // Shared between threads
    private volatile int mMode = MODE_PHOTO;
    private volatile int mFlash = FLASH_AUTO;
    private volatile float mZoom = 1f;
    private volatile float mMinZoom = 1f;
    private volatile float mMaxZoom = 1f;
    private volatile int mEvIndex;
    private volatile int mDeviceRotation;
    private volatile boolean mAudioEnabled = true;
    private final AtomicInteger mPendingSaves = new AtomicInteger();
    private final Object mRawLock = new Object();
    private final LongSparseArray<TotalCaptureResult> mResults = new LongSparseArray<>();
    private final LongSparseArray<Image> mPendingRaw = new LongSparseArray<>();

    public CameraController(Context context, Settings settings, MediaSaver saver, Listener listener) {
        mContext = context.getApplicationContext();
        mManager = (CameraManager) mContext.getSystemService(Context.CAMERA_SERVICE);
        mSettings = settings;
        mSaver = saver;
        mListener = listener;
        mVideo = new VideoRecorder(mContext);
        mFlash = settings.flash();
    }

    // =========================================================================================
    // Public API (UI thread)
    // =========================================================================================

    public void attachPreview(AutoFitSurfaceView view) {
        mPreviewView = view;
        mHolder = view.getHolder();
        mHolder.addCallback(this);
    }

    /** Opens the camera; the preview starts as soon as the surface has the negotiated size. */
    public void start(final LensInfo lens) {
        ensureThreads();
        mCamHandler.post(new Runnable() {
            @Override
            public void run() {
                mStopped = false;
                if (mDevice != null && mLens != null && mLens.id.equals(lens.id)) {
                    if (mSession == null && mExtSession == null && !mConfiguring) {
                        tryConfigure();
                    }
                    return;
                }
                closeSession();
                closeDevice();
                initLens(lens);
                openCamera();
            }
        });
    }

    /** Releases the camera (call from onPause). Blocks briefly so the HAL is free for other apps. */
    public void stop() {
        final Handler h = mCamHandler;
        if (h == null) {
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    mStopped = true;
                    if (mRecording) {
                        stopRecordingInternal();
                    }
                    closeSession();
                    closeDevice();
                    releaseReaders();
                    mVideo.release();
                    mRecorderSurface = null;
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            if (!latch.await(2500, TimeUnit.MILLISECONDS)) {
                Log.w(TAG, "Timed out waiting for camera to close");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /** Stops worker threads. Call from onDestroy. */
    public void release() {
        stop();
        if (mCamThread != null) {
            mCamThread.quitSafely();
            mCamThread = null;
            mCamHandler = null;
        }
        if (mImgThread != null) {
            mImgThread.quitSafely();
            mImgThread = null;
            mImgHandler = null;
        }
    }

    public LensInfo getLens() {
        return mLens;
    }

    public int getMode() {
        return mMode;
    }

    public boolean isRecording() {
        return mRecording;
    }

    public long getRecordingMillis() {
        return mRecording ? SystemClock.elapsedRealtime() - mRecordStart : 0L;
    }

    public int getFlash() {
        return mFlash;
    }

    public float getZoom() {
        return mZoom;
    }

    public float getMinZoom() {
        return mMinZoom;
    }

    public float getMaxZoom() {
        return mMaxZoom;
    }

    public void setDeviceRotation(int degrees) {
        mDeviceRotation = degrees;
    }

    public void setAudioEnabled(boolean enabled) {
        mAudioEnabled = enabled;
    }

    public boolean supportsMode(int mode) {
        if (mode == MODE_PHOTO || mode == MODE_VIDEO) {
            return true;
        }
        LensInfo lens = mLens;
        return lens != null && Build.VERSION.SDK_INT >= 31 && lens.hasExtension(extensionForMode(mode));
    }

    public void setMode(final int mode) {
        post(new Runnable() {
            @Override
            public void run() {
                if (mRecording || mode == mMode || !supportsMode(mode)) {
                    return;
                }
                mMode = mode;
                mRawDisabled = false;
                mUhrDisabled = false;
                closeSession();
                tryConfigure();
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        mListener.onModeChanged(mode);
                    }
                });
            }
        });
    }

    public void setFlash(int flash) {
        mFlash = flash;
        mSettings.putInt(Settings.KEY_FLASH, flash);
        scheduleRepeatingUpdate();
    }

    public void setZoom(float ratio) {
        float z = clamp(ratio, mMinZoom, mMaxZoom);
        if (z == mZoom) {
            return;
        }
        mZoom = z;
        mListener.onZoomChanged(z);
        scheduleRepeatingUpdate();
    }

    public void setExposureIndex(int index) {
        LensInfo lens = mLens;
        if (lens != null) {
            index = Math.max(lens.evRange.getLower(), Math.min(lens.evRange.getUpper(), index));
        }
        if (index == mEvIndex) {
            return;
        }
        mEvIndex = index;
        scheduleRepeatingUpdate();
    }

    /** Tap-to-focus / metering at normalised preview coordinates (0..1). */
    public void focusAt(final float nx, final float ny) {
        post(new Runnable() {
            @Override
            public void run() {
                focusAtInternal(nx, ny);
            }
        });
    }

    public void capturePhoto() {
        post(new Runnable() {
            @Override
            public void run() {
                captureInternal();
            }
        });
    }

    public void toggleRecording() {
        post(new Runnable() {
            @Override
            public void run() {
                if (mRecording) {
                    stopRecordingInternal();
                } else {
                    startRecordingInternal();
                }
            }
        });
    }

    public void switchLens(final LensInfo lens) {
        post(new Runnable() {
            @Override
            public void run() {
                if (mRecording) {
                    return;
                }
                closeSession();
                closeDevice();
                releaseReaders();
                initLens(lens);
                if (!supportsMode(mMode)) {
                    mMode = MODE_PHOTO;
                    mMain.post(new Runnable() {
                        @Override
                        public void run() {
                            mListener.onModeChanged(MODE_PHOTO);
                        }
                    });
                }
                openCamera();
            }
        });
    }

    /** Re-creates the session after a settings change. */
    public void reconfigure() {
        post(new Runnable() {
            @Override
            public void run() {
                if (mRecording) {
                    return;
                }
                mRawDisabled = false;
                mUhrDisabled = false;
                closeSession();
                tryConfigure();
            }
        });
    }

    public static boolean isExtensionMode(int mode) {
        return mode >= MODE_NIGHT;
    }

    public static int extensionForMode(int mode) {
        switch (mode) {
            case MODE_NIGHT:
                return CameraExtensionCharacteristics.EXTENSION_NIGHT;
            case MODE_HDR:
                return CameraExtensionCharacteristics.EXTENSION_HDR;
            case MODE_PORTRAIT:
                return CameraExtensionCharacteristics.EXTENSION_BOKEH;
            case MODE_BEAUTY:
                return CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH;
            case MODE_AUTO:
                return CameraExtensionCharacteristics.EXTENSION_AUTOMATIC;
            default:
                return -1;
        }
    }

    public static int modeForExtension(int extension) {
        switch (extension) {
            case CameraExtensionCharacteristics.EXTENSION_NIGHT:
                return MODE_NIGHT;
            case CameraExtensionCharacteristics.EXTENSION_HDR:
                return MODE_HDR;
            case CameraExtensionCharacteristics.EXTENSION_BOKEH:
                return MODE_PORTRAIT;
            case CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH:
                return MODE_BEAUTY;
            case CameraExtensionCharacteristics.EXTENSION_AUTOMATIC:
                return MODE_AUTO;
            default:
                return -1;
        }
    }

    // =========================================================================================
    // SurfaceHolder.Callback (UI thread)
    // =========================================================================================

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        // Wait for surfaceChanged: only then is the buffer size known.
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        synchronized (mSurfaceLock) {
            mPreviewSurface = holder.getSurface();
            mSurfaceSize = new Size(width, height);
        }
        post(new Runnable() {
            @Override
            public void run() {
                tryConfigure();
            }
        });
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        synchronized (mSurfaceLock) {
            mPreviewSurface = null;
            mSurfaceSize = null;
        }
        Handler h = mCamHandler;
        if (h == null) {
            return;
        }
        final CountDownLatch latch = new CountDownLatch(1);
        h.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (mRecording) {
                        stopRecordingInternal();
                    }
                    closeSession();
                } finally {
                    latch.countDown();
                }
            }
        });
        try {
            latch.await(1000, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // =========================================================================================
    // Camera thread: open / configure
    // =========================================================================================

    private void ensureThreads() {
        if (mCamThread == null) {
            mCamThread = new HandlerThread("OPCam-Camera", Process.THREAD_PRIORITY_DISPLAY);
            mCamThread.start();
            mCamHandler = new Handler(mCamThread.getLooper());
        }
        if (mImgThread == null) {
            mImgThread = new HandlerThread("OPCam-Images", Process.THREAD_PRIORITY_BACKGROUND);
            mImgThread.start();
            mImgHandler = new Handler(mImgThread.getLooper());
        }
    }

    private void post(Runnable r) {
        Handler h = mCamHandler;
        if (h != null) {
            h.post(r);
        }
    }

    private void initLens(LensInfo lens) {
        mLens = lens;
        mActiveArray = lens.activeArray;
        mZoomRatioSupported = Build.VERSION.SDK_INT >= 30 && lens.zoomRatioRange != null;
        if (mZoomRatioSupported) {
            mMinZoom = lens.zoomRatioRange.getLower();
            mMaxZoom = lens.zoomRatioRange.getUpper();
        } else {
            mMinZoom = 1f;
            mMaxZoom = Math.max(1f, lens.maxDigitalZoom);
        }
        mZoom = clamp(1f, mMinZoom, mMaxZoom);
        mEvIndex = 0;
        mAeState = -1;
        mAfTapActive = false;
        mAfWaiting = false;
        mAfRegions = null;
        mAeRegions = null;
        mRawDisabled = false;
        mUhrDisabled = false;
        CameraCharacteristics c = lens.chars;
        mNrModes = c.get(CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES);
        mEdgeModes = c.get(CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES);
        mAberrationModes = c.get(CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES);
        mDistortionModes = Build.VERSION.SDK_INT >= 28
                ? c.get(CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES) : null;
        final float zoom = mZoom;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                mListener.onZoomChanged(zoom);
            }
        });
    }

    private void openCamera() {
        if (mDevice != null || mOpening || mStopped) {
            return;
        }
        if (mContext.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            postError("Camera permission not granted", true);
            return;
        }
        mOpening = true;
        try {
            mManager.openCamera(mLens.id, mDeviceCallback, mCamHandler);
        } catch (CameraAccessException | IllegalArgumentException | SecurityException | IllegalStateException e) {
            mOpening = false;
            postError("Cannot open " + mLens + ": " + e.getMessage(), true);
        }
    }

    private final CameraDevice.StateCallback mDeviceCallback = new CameraDevice.StateCallback() {
        @Override
        public void onOpened(CameraDevice camera) {
            mOpening = false;
            if (mStopped) {
                camera.close();
                return;
            }
            mDevice = camera;
            tryConfigure();
        }

        @Override
        public void onDisconnected(CameraDevice camera) {
            mOpening = false;
            camera.close();
            if (mDevice == camera) {
                mDevice = null;
                mSession = null;
                mExtSession = null;
            }
            if (!mStopped) {
                postError("Camera disconnected (another app took it over)", true);
            }
        }

        @Override
        public void onError(CameraDevice camera, int error) {
            mOpening = false;
            camera.close();
            if (mDevice == camera) {
                mDevice = null;
                mSession = null;
                mExtSession = null;
            }
            postError("Camera device error " + error, true);
        }
    };

    private void tryConfigure() {
        if (mDevice == null || mStopped || mConfiguring) {
            return;
        }
        Surface surface;
        Size surfaceSize;
        synchronized (mSurfaceLock) {
            surface = mPreviewSurface;
            surfaceSize = mSurfaceSize;
        }
        if (surface == null || surfaceSize == null) {
            return;
        }
        if (!chooseSizes()) {
            postError("No usable stream configuration for " + mLens, true);
            return;
        }
        if (!mPreviewSize.equals(surfaceSize)) {
            requestSurfaceSize(mPreviewSize);
            return;
        }
        if ((mSession != null || mExtSession != null) && surface.equals(mSessionPreviewSurface)) {
            return; // already streaming to this surface with this configuration
        }
        createSession(surface);
    }

    private void requestSurfaceSize(final Size size) {
        mMain.post(new Runnable() {
            @Override
            public void run() {
                if (mHolder != null && mPreviewView != null) {
                    mPreviewView.setAspectRatio(size.getWidth(), size.getHeight());
                    mHolder.setFixedSize(size.getWidth(), size.getHeight());
                }
            }
        });
    }

    /** Picks still / preview / video sizes for the current mode. */
    private boolean chooseSizes() {
        StreamConfigurationMap map = mLens.map;
        Size[] previewCandidates = map.getOutputSizes(SurfaceHolder.class);
        if (previewCandidates == null || previewCandidates.length == 0) {
            return false;
        }
        mUseMaxRes = false;
        mVideoConfig = null;
        mExtZoomSupported = false;
        mExtAfSupported = false;

        if (isExtensionMode(mMode)) {
            int ext = extensionForMode(mMode);
            Size[] stills = toArray(ExtensionsHelper.stillSizes(mManager, mLens.id, ext));
            Size[] previews = toArray(ExtensionsHelper.previewSizes(mManager, mLens.id, ext));
            if (stills.length == 0 || previews.length == 0) {
                return false;
            }
            Size still = largestWithAspect(stills, 4d / 3d, Long.MAX_VALUE);
            if (still == null) {
                still = CameraInfo.largest(stills);
            }
            mStillFormat = ImageFormat.JPEG;
            mStillSize = still;
            mPreviewSize = choosePreviewSize(previews, aspect(still));
            mExtZoomSupported = mZoomRatioSupported
                    && ExtensionsHelper.supportsRequestKey(mManager, mLens.id, ext, CaptureRequest.CONTROL_ZOOM_RATIO);
            mExtAfSupported = mLens.maxAfRegions > 0
                    && ExtensionsHelper.supportsRequestKey(mManager, mLens.id, ext, CaptureRequest.CONTROL_AF_MODE)
                    && ExtensionsHelper.supportsRequestKey(mManager, mLens.id, ext, CaptureRequest.CONTROL_AF_TRIGGER)
                    && ExtensionsHelper.supportsRequestKey(mManager, mLens.id, ext, CaptureRequest.CONTROL_AF_REGIONS);
            mFpsRange = null;
            return true;
        }

        if (mMode == MODE_VIDEO) {
            mVideoConfig = VideoRecorder.resolve(mLens, mSettings);
            if (mVideoConfig == null) {
                return false;
            }
            double aspect = aspect(mVideoConfig.size);
            mPreviewSize = choosePreviewSize(previewCandidates, aspect);
            Size[] jpeg = map.getOutputSizes(ImageFormat.JPEG);
            long videoArea = (long) mVideoConfig.size.getWidth() * mVideoConfig.size.getHeight();
            Size snap = largestWithAspect(jpeg, aspect, videoArea);
            mStillFormat = ImageFormat.JPEG;
            mStillSize = snap; // may be null -> no snapshot-while-recording
            mFpsRange = pickFpsRange(mVideoConfig.fps);
            return true;
        }

        // ---- Photo
        int format = (mSettings.heic() && mLens.heicSupported()) ? ImageFormat.HEIC : ImageFormat.JPEG;
        Size[] sizes = map.getOutputSizes(format);
        if (sizes == null || sizes.length == 0) {
            format = ImageFormat.JPEG;
            sizes = map.getOutputSizes(ImageFormat.JPEG);
        }
        Size largest = CameraInfo.largest(sizes);
        double aspect = 4d / 3d;
        if (largestWithAspect(sizes, aspect, Long.MAX_VALUE) == null) {
            aspect = aspect(largest);
        }
        boolean fullRes = mSettings.fullRes();
        Size still = null;
        if (fullRes && mLens.uhrSupported && !mUhrDisabled && Build.VERSION.SDK_INT >= 31) {
            StreamConfigurationMap maxRes =
                    mLens.chars.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
            if (maxRes != null) {
                Size[] maxSizes = null;
                try {
                    maxSizes = maxRes.getOutputSizes(format);
                } catch (IllegalArgumentException ignored) {
                    // format not offered in max-res mode
                }
                if ((maxSizes == null || maxSizes.length == 0) && format != ImageFormat.JPEG) {
                    maxSizes = maxRes.getOutputSizes(ImageFormat.JPEG);
                    if (maxSizes != null && maxSizes.length > 0) {
                        format = ImageFormat.JPEG;
                    }
                }
                if (maxSizes != null && maxSizes.length > 0) {
                    still = largestWithAspect(maxSizes, aspect, Long.MAX_VALUE);
                    if (still == null) {
                        still = CameraInfo.largest(maxSizes);
                    }
                    mUseMaxRes = true;
                }
            }
        }
        if (still == null) {
            long limit = fullRes ? Long.MAX_VALUE : STANDARD_STILL_MAX_PIXELS;
            still = largestWithAspect(sizes, aspect, limit);
            if (still == null) {
                still = largestWithAspect(sizes, aspect, Long.MAX_VALUE);
            }
            if (still == null) {
                still = largest;
            }
        }
        mStillFormat = format;
        mStillSize = still;
        mPreviewSize = choosePreviewSize(previewCandidates, aspect(still));
        mFpsRange = pickFpsRange(0);
        return true;
    }

    private Size choosePreviewSize(Size[] candidates, double aspect) {
        boolean saver = mSettings.powerSaver();
        int maxLong = saver ? 1280 : 1920;
        int maxShort = saver ? 720 : 1080;
        Size best = null;
        for (Size s : candidates) {
            if (!aspectMatches(s, aspect) || s.getWidth() > maxLong || s.getHeight() > maxShort) {
                continue;
            }
            if (best == null || area(s) > area(best)) {
                best = s;
            }
        }
        if (best == null) {
            for (Size s : candidates) {
                if (s.getWidth() > maxLong || s.getHeight() > maxShort) {
                    continue;
                }
                if (best == null || Math.abs(aspect(s) - aspect) < Math.abs(aspect(best) - aspect)
                        || (Math.abs(aspect(s) - aspect) == Math.abs(aspect(best) - aspect) && area(s) > area(best))) {
                    best = s;
                }
            }
        }
        if (best == null) {
            best = candidates[0];
            for (Size s : candidates) {
                if (area(s) < area(best)) {
                    best = s;
                }
            }
        }
        return best;
    }

    private Range<Integer> pickFpsRange(int exactFps) {
        Range<Integer>[] ranges = mLens.fpsRanges;
        if (ranges == null || ranges.length == 0) {
            return null;
        }
        Range<Integer> best = null;
        if (exactFps > 0) {
            for (Range<Integer> r : ranges) {
                if (r.getUpper() == exactFps && r.getLower() == exactFps) {
                    return r;
                }
            }
            for (Range<Integer> r : ranges) {
                if (r.getUpper() == exactFps && (best == null || r.getLower() > best.getLower())) {
                    best = r;
                }
            }
            if (best != null) {
                return best;
            }
        }
        // Photo: cap at 30 fps (panel refresh is irrelevant for a viewfinder) and let AE lengthen
        // exposure in low light. Power saver allows the lowest floor the HAL offers.
        boolean saver = mSettings.powerSaver();
        for (Range<Integer> r : ranges) {
            if (r.getUpper() != 30) {
                continue;
            }
            if (best == null) {
                best = r;
            } else if (saver ? r.getLower() < best.getLower()
                    : Math.abs(r.getLower() - 15) < Math.abs(best.getLower() - 15)) {
                best = r;
            }
        }
        if (best == null) {
            for (Range<Integer> r : ranges) {
                if (best == null || r.getUpper() > best.getUpper()) {
                    best = r;
                }
            }
        }
        return best;
    }

    private void createSession(Surface previewSurface) {
        if (mSession != null || mExtSession != null) {
            closeSession();
        }
        mConfiguring = true;
        releaseReaders();
        mSessionPreviewSurface = previewSurface;
        List<Surface> outputs = new ArrayList<>();
        outputs.add(previewSurface);
        if (mStillSize != null) {
            mStillReader = ImageReader.newInstance(mStillSize.getWidth(), mStillSize.getHeight(), mStillFormat, 3);
            mStillReader.setOnImageAvailableListener(mStillListener, mImgHandler);
            outputs.add(mStillReader.getSurface());
        }
        boolean extension = isExtensionMode(mMode);
        if (mMode == MODE_PHOTO && mSettings.raw() && mLens.rawSupported() && !mRawDisabled && !mUseMaxRes) {
            Size raw = mLens.maxRaw;
            mRawReader = ImageReader.newInstance(raw.getWidth(), raw.getHeight(), ImageFormat.RAW_SENSOR, 2);
            mRawReader.setOnImageAvailableListener(mRawListener, mImgHandler);
            outputs.add(mRawReader.getSurface());
        }
        if (mMode == MODE_VIDEO) {
            try {
                mRecorderSurface = mVideo.obtainSurface(mVideoConfig);
            } catch (IOException | RuntimeException e) {
                mConfiguring = false;
                mRecorderSurface = null;
                postError("Video encoder setup failed: " + e.getMessage(), false);
                return;
            }
            outputs.add(mRecorderSurface);
        }
        try {
            if (extension) {
                if (mStillReader == null) {
                    throw new IllegalStateException("No still output for extension");
                }
                ExtensionsHelper.createSession(mDevice, extensionForMode(mMode), previewSurface,
                        mStillReader.getSurface(), mCamExecutor, mExtCallback);
            } else if (Build.VERSION.SDK_INT >= 28) {
                List<OutputConfiguration> configs = new ArrayList<>();
                for (Surface s : outputs) {
                    configs.add(new OutputConfiguration(s));
                }
                SessionConfiguration sc = new SessionConfiguration(SessionConfiguration.SESSION_REGULAR, configs,
                        mCamExecutor, mSessionCallback);
                if (mMode == MODE_VIDEO && mFpsRange != null) {
                    // Session parameters let the HAL pick the right sensor mode for 60 fps / 4K up front.
                    CaptureRequest.Builder params = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
                    params.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mFpsRange);
                    sc.setSessionParameters(params.build());
                }
                mDevice.createCaptureSession(sc);
            } else {
                mDevice.createCaptureSession(outputs, mSessionCallback, mCamHandler);
            }
        } catch (CameraAccessException | IllegalArgumentException | IllegalStateException e) {
            mConfiguring = false;
            postError("Session setup failed: " + e.getMessage(), true);
        }
    }

    private final CameraCaptureSession.StateCallback mSessionCallback = new CameraCaptureSession.StateCallback() {
        @Override
        public void onConfigured(CameraCaptureSession session) {
            mConfiguring = false;
            if (mStopped || mDevice == null) {
                session.close();
                return;
            }
            mSession = session;
            startPreview();
        }

        @Override
        public void onConfigureFailed(CameraCaptureSession session) {
            mConfiguring = false;
            session.close();
            if (mRawReader != null) {
                mRawDisabled = true;
                postError("RAW + " + (mStillFormat == ImageFormat.HEIC ? "HEIC" : "JPEG")
                        + " not supported together; RAW disabled", false);
                tryConfigure();
                return;
            }
            if (mUseMaxRes) {
                mUhrDisabled = true;
                postError("Full-resolution mode rejected by HAL; using default size", false);
                tryConfigure();
                return;
            }
            postError("Camera session configuration failed", true);
        }

        @Override
        public void onClosed(CameraCaptureSession session) {
            if (mSession == session) {
                mSession = null;
            }
        }
    };

    private final ExtensionsHelper.Callback mExtCallback = new ExtensionsHelper.Callback() {
        @Override
        public void onConfigured(ExtensionsHelper.ExtSession session) {
            mConfiguring = false;
            if (mStopped || mDevice == null) {
                session.close();
                return;
            }
            mExtSession = session;
            startPreview();
        }

        @Override
        public void onConfigureFailed() {
            mConfiguring = false;
            postError("This mode is unavailable right now; back to Photo", false);
            mMode = MODE_PHOTO;
            mMain.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onModeChanged(MODE_PHOTO);
                }
            });
            tryConfigure();
        }

        @Override
        public void onClosed() {
            // nothing: mExtSession already nulled in closeSession()
        }

        @Override
        public void onProcessStarted() {
            postProgress(-1);
        }

        @Override
        public void onProgress(int percent) {
            postProgress(percent);
        }

        @Override
        public void onCaptureFailed() {
            mCaptureState = STATE_PREVIEW;
            captureDone();
            postError("Capture failed", false);
        }

        @Override
        public void onSequenceCompleted() {
            mCaptureState = STATE_PREVIEW;
        }
    };

    private void startPreview() {
        if (mDevice == null || mSessionPreviewSurface == null) {
            return;
        }
        try {
            CaptureRequest.Builder b;
            if (!isExtensionMode(mMode) && mMode == MODE_PHOTO && mSettings.zsl()) {
                try {
                    b = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_ZERO_SHUTTER_LAG);
                } catch (IllegalArgumentException e) {
                    b = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
                }
            } else {
                b = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            }
            b.addTarget(mSessionPreviewSurface);
            mRepeatingBuilder = b;
            applyCurrentSettings(b, false);
            setRepeating();
            mCaptureState = STATE_PREVIEW;
            notifyReady();
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            postError("Preview failed: " + e.getMessage(), true);
        }
    }

    private void setRepeating() {
        if (mRepeatingBuilder == null) {
            return;
        }
        try {
            if (mExtSession != null) {
                mExtSession.setRepeating(mRepeatingBuilder.build());
            } else if (mSession != null) {
                mSession.setRepeatingRequest(mRepeatingBuilder.build(), mPreviewCallback, mCamHandler);
            }
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "setRepeatingRequest failed: " + e);
        }
    }

    private final Runnable mUpdateRepeating = new Runnable() {
        @Override
        public void run() {
            if (mRepeatingBuilder == null) {
                return;
            }
            applyCurrentSettings(mRepeatingBuilder, false);
            setRepeating();
        }
    };

    private void scheduleRepeatingUpdate() {
        Handler h = mCamHandler;
        if (h == null) {
            return;
        }
        h.removeCallbacks(mUpdateRepeating);
        h.postDelayed(mUpdateRepeating, 8L);
    }

    private void notifyReady() {
        final LensInfo lens = mLens;
        final String format = formatSummary();
        final String hardware = hardwareSummary();
        final float minZ = mMinZoom;
        final float maxZ = mMaxZoom;
        final float zoom = mZoom;
        mMain.post(new Runnable() {
            @Override
            public void run() {
                mListener.onCameraReady(lens, format, hardware, lens.flashAvailable, minZ, maxZ, zoom,
                        lens.evRange, lens.evStep);
            }
        });
    }

    private String formatSummary() {
        if (mMode == MODE_VIDEO && mVideoConfig != null) {
            String res = mVideoConfig.size.getHeight() >= 2160 ? "4K" : mVideoConfig.size.getHeight() + "p";
            return res + mVideoConfig.fps + " · " + (mVideoConfig.hevc ? "HEVC" : "H.264");
        }
        StringBuilder sb = new StringBuilder();
        sb.append(mStillFormat == ImageFormat.HEIC ? "HEIC" : "JPEG");
        if (mStillSize != null) {
            sb.append(" · ").append(LensInfo.megapixels(mStillSize));
        }
        if (mUseMaxRes) {
            sb.append(" full");
        }
        if (mRawReader != null) {
            sb.append(" + RAW");
        }
        return sb.toString();
    }

    private String hardwareSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append("Cam ").append(mLens.id).append(" · ").append(mLens.hardwareLevelName());
        if (mMode == MODE_PHOTO && mSettings.zsl()) {
            sb.append(" · ZSL");
        }
        if (mLens.ois) {
            sb.append(" · OIS");
        }
        if (mPreviewSize != null) {
            sb.append(" · ").append(mPreviewSize.getWidth()).append('x').append(mPreviewSize.getHeight());
        }
        if (mFpsRange != null) {
            sb.append(" @").append(mFpsRange.getLower()).append('-').append(mFpsRange.getUpper());
        }
        return sb.toString();
    }

    // =========================================================================================
    // Request settings
    // =========================================================================================

    private void applyCurrentSettings(CaptureRequest.Builder b, boolean still) {
        if (isExtensionMode(mMode)) {
            applyExtensionSettings(b, still);
        } else {
            applySettings(b, still);
        }
    }

    private void applySettings(CaptureRequest.Builder b, boolean still) {
        b.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO);

        int af = currentAfMode();
        if (af >= 0) {
            b.set(CaptureRequest.CONTROL_AF_MODE, af);
        }
        if (mAfRegions != null) {
            b.set(CaptureRequest.CONTROL_AF_REGIONS, mAfRegions);
        }
        if (mAeRegions != null) {
            b.set(CaptureRequest.CONTROL_AE_REGIONS, mAeRegions);
        }

        int flash = mFlash;
        if (!mLens.flashAvailable) {
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
        } else if (mMode == MODE_VIDEO) {
            boolean torch = flash == FLASH_TORCH || (flash == FLASH_ON && mRecording);
            b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
            b.set(CaptureRequest.FLASH_MODE, torch ? CameraMetadata.FLASH_MODE_TORCH : CameraMetadata.FLASH_MODE_OFF);
        } else {
            switch (flash) {
                case FLASH_AUTO:
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_AUTO_FLASH);
                    b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
                    break;
                case FLASH_ON:
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON_ALWAYS_FLASH);
                    b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
                    break;
                case FLASH_TORCH:
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                    b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_TORCH);
                    break;
                default:
                    b.set(CaptureRequest.CONTROL_AE_MODE, CameraMetadata.CONTROL_AE_MODE_ON);
                    b.set(CaptureRequest.FLASH_MODE, CameraMetadata.FLASH_MODE_OFF);
                    break;
            }
        }
        b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, mEvIndex);
        if (!still && mFpsRange != null) {
            b.set(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, mFpsRange);
        }
        b.set(CaptureRequest.CONTROL_AWB_MODE, CameraMetadata.CONTROL_AWB_MODE_AUTO);

        applyZoom(b);

        if (mLens.ois) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        }
        if (mMode == MODE_VIDEO && mLens.videoStabilization) {
            b.set(CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE, mSettings.stabilization()
                    ? CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON
                    : CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_OFF);
        }

        // Stills get the HAL's high-quality processing; the viewfinder uses the fast (low power) paths.
        setIfAvailable(b, CaptureRequest.NOISE_REDUCTION_MODE, mNrModes,
                still ? CameraMetadata.NOISE_REDUCTION_MODE_HIGH_QUALITY : CameraMetadata.NOISE_REDUCTION_MODE_FAST);
        setIfAvailable(b, CaptureRequest.EDGE_MODE, mEdgeModes,
                still ? CameraMetadata.EDGE_MODE_HIGH_QUALITY : CameraMetadata.EDGE_MODE_FAST);
        setIfAvailable(b, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, mAberrationModes,
                still ? CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_HIGH_QUALITY
                        : CameraMetadata.COLOR_CORRECTION_ABERRATION_MODE_FAST);
        if (Build.VERSION.SDK_INT >= 28 && mDistortionModes != null) {
            setIfAvailable(b, CaptureRequest.DISTORTION_CORRECTION_MODE, mDistortionModes,
                    still ? CameraMetadata.DISTORTION_CORRECTION_MODE_HIGH_QUALITY
                            : CameraMetadata.DISTORTION_CORRECTION_MODE_FAST);
        }
        b.set(CaptureRequest.STATISTICS_FACE_DETECT_MODE, CameraMetadata.STATISTICS_FACE_DETECT_MODE_OFF);

        if (still) {
            b.set(CaptureRequest.JPEG_QUALITY, (byte) mSettings.jpegQuality());
            mLastJpegOrientation = jpegOrientation();
            b.set(CaptureRequest.JPEG_ORIENTATION, mLastJpegOrientation);
            if (mMode == MODE_PHOTO && mSettings.zsl()) {
                b.set(CaptureRequest.CONTROL_ENABLE_ZSL, true);
            }
            if (mUseMaxRes && Build.VERSION.SDK_INT >= 31) {
                b.set(CaptureRequest.SENSOR_PIXEL_MODE, CameraMetadata.SENSOR_PIXEL_MODE_MAXIMUM_RESOLUTION);
            }
            b.setTag("still");
        }
    }

    private void applyExtensionSettings(CaptureRequest.Builder b, boolean still) {
        if (mExtZoomSupported) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, mZoom);
        }
        if (mExtAfSupported) {
            int af = currentAfMode();
            if (af >= 0) {
                b.set(CaptureRequest.CONTROL_AF_MODE, af);
            }
            if (mAfRegions != null) {
                b.set(CaptureRequest.CONTROL_AF_REGIONS, mAfRegions);
            }
        }
        if (still) {
            b.set(CaptureRequest.JPEG_QUALITY, (byte) mSettings.jpegQuality());
            mLastJpegOrientation = jpegOrientation();
            b.set(CaptureRequest.JPEG_ORIENTATION, mLastJpegOrientation);
            b.setTag("still");
        }
    }

    private int currentAfMode() {
        if (mLens.afModes == null || mLens.afModes.length == 0) {
            return -1;
        }
        if (mAfTapActive && mLens.supportsAfMode(CameraMetadata.CONTROL_AF_MODE_AUTO)) {
            return CameraMetadata.CONTROL_AF_MODE_AUTO;
        }
        int preferred = mMode == MODE_VIDEO ? CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_VIDEO
                : CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        if (mLens.supportsAfMode(preferred)) {
            return preferred;
        }
        if (mLens.supportsAfMode(CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE)) {
            return CameraMetadata.CONTROL_AF_MODE_CONTINUOUS_PICTURE;
        }
        if (mLens.supportsAfMode(CameraMetadata.CONTROL_AF_MODE_AUTO)) {
            return CameraMetadata.CONTROL_AF_MODE_AUTO;
        }
        return CameraMetadata.CONTROL_AF_MODE_OFF;
    }

    private void applyZoom(CaptureRequest.Builder b) {
        if (mZoomRatioSupported) {
            b.set(CaptureRequest.CONTROL_ZOOM_RATIO, mZoom);
        } else {
            Rect crop = cropForZoom(mZoom);
            if (crop != null) {
                b.set(CaptureRequest.SCALER_CROP_REGION, crop);
            }
        }
    }

    private Rect cropForZoom(float zoom) {
        if (mActiveArray == null) {
            return null;
        }
        float z = Math.max(1f, zoom);
        int w = mActiveArray.width();
        int h = mActiveArray.height();
        int cw = Math.max(1, (int) (w / z));
        int ch = Math.max(1, (int) (h / z));
        int left = (w - cw) / 2;
        int top = (h - ch) / 2;
        return new Rect(left, top, left + cw, top + ch);
    }

    private static void setIfAvailable(CaptureRequest.Builder b, CaptureRequest.Key<Integer> key, int[] available,
                                       int desired) {
        if (available == null || available.length == 0) {
            return;
        }
        if (CameraInfo.contains(available, desired)) {
            b.set(key, desired);
        } else if (CameraInfo.contains(available, 1)) {
            b.set(key, 1); // every *_MODE enum uses 1 == FAST
        }
    }

    private int jpegOrientation() {
        int rotation = mDeviceRotation;
        if (rotation < 0) {
            rotation = 0;
        }
        rotation = ((rotation + 45) / 90 * 90) % 360;
        if (mLens.isFront()) {
            rotation = -rotation;
        }
        return (mLens.sensorOrientation + rotation + 360) % 360;
    }

    private static int exifOrientation(int degrees) {
        switch (degrees) {
            case 90:
                return ExifInterface.ORIENTATION_ROTATE_90;
            case 180:
                return ExifInterface.ORIENTATION_ROTATE_180;
            case 270:
                return ExifInterface.ORIENTATION_ROTATE_270;
            default:
                return ExifInterface.ORIENTATION_NORMAL;
        }
    }

    // =========================================================================================
    // Focus
    // =========================================================================================

    private void focusAtInternal(float nx, float ny) {
        if (mRepeatingBuilder == null || mDevice == null || (mSession == null && mExtSession == null)) {
            return;
        }
        if (mLens.maxAfRegions == 0 && mLens.maxAeRegions == 0) {
            return;
        }
        if (isExtensionMode(mMode) && !mExtAfSupported) {
            return;
        }
        MeteringRectangle region = meteringRect(nx, ny);
        mAfRegions = mLens.maxAfRegions > 0 ? new MeteringRectangle[]{region} : null;
        mAeRegions = mLens.maxAeRegions > 0 ? new MeteringRectangle[]{region} : null;
        mAfTapActive = mLens.supportsAfMode(CameraMetadata.CONTROL_AF_MODE_AUTO);
        mAfWaiting = mAfTapActive;
        Handler h = mCamHandler;
        if (h != null) {
            h.removeCallbacks(mAfReset);
            h.postDelayed(mAfReset, AF_RESET_DELAY_MS);
        }
        applyCurrentSettings(mRepeatingBuilder, false);
        if (!mAfTapActive) {
            setRepeating(); // metering only (fixed-focus module)
            return;
        }
        try {
            if (mExtSession != null) {
                mRepeatingBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
                setRepeating();
                mRepeatingBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                if (h != null) {
                    h.postDelayed(new Runnable() {
                        @Override
                        public void run() {
                            setRepeating();
                        }
                    }, 120L);
                }
                mAfWaiting = false; // extension sessions deliver no AF state
                return;
            }
            mRepeatingBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
            mSession.capture(mRepeatingBuilder.build(), mPreviewCallback, mCamHandler);
            mRepeatingBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_START);
            mSession.capture(mRepeatingBuilder.build(), mPreviewCallback, mCamHandler);
            mRepeatingBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
            setRepeating();
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "AF trigger failed: " + e);
        }
    }

    private final Runnable mAfReset = new Runnable() {
        @Override
        public void run() {
            mAfTapActive = false;
            mAfWaiting = false;
            mAfRegions = null;
            mAeRegions = null;
            if (mRepeatingBuilder == null) {
                return;
            }
            applyCurrentSettings(mRepeatingBuilder, false);
            try {
                if (mSession != null && !isExtensionMode(mMode)) {
                    mRepeatingBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER,
                            CameraMetadata.CONTROL_AF_TRIGGER_CANCEL);
                    mSession.capture(mRepeatingBuilder.build(), mPreviewCallback, mCamHandler);
                    mRepeatingBuilder.set(CaptureRequest.CONTROL_AF_TRIGGER, CameraMetadata.CONTROL_AF_TRIGGER_IDLE);
                }
            } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
                Log.w(TAG, "AF reset failed: " + e);
            }
            setRepeating();
        }
    };

    /** Maps a normalised tap on the (portrait) preview to the sensor's active-array coordinates. */
    private MeteringRectangle meteringRect(float nx, float ny) {
        nx = clamp(nx, 0f, 1f);
        ny = clamp(ny, 0f, 1f);
        if (mLens.isFront()) {
            nx = 1f - nx; // the compositor mirrors front previews horizontally (display space)
        }
        float sx;
        float sy;
        switch (mLens.sensorOrientation) {
            case 90:
                sx = ny;
                sy = 1f - nx;
                break;
            case 180:
                sx = 1f - nx;
                sy = 1f - ny;
                break;
            case 270:
                sx = 1f - ny;
                sy = nx;
                break;
            default:
                sx = nx;
                sy = ny;
                break;
        }
        Rect ref;
        if (mZoomRatioSupported || mActiveArray == null) {
            ref = mActiveArray != null ? new Rect(0, 0, mActiveArray.width(), mActiveArray.height())
                    : new Rect(0, 0, 1000, 1000);
        } else {
            ref = cropForZoom(mZoom);
        }
        int regionW = Math.max(1, ref.width() / 8);
        int regionH = Math.max(1, ref.height() / 8);
        int cx = ref.left + (int) (sx * ref.width());
        int cy = ref.top + (int) (sy * ref.height());
        int left = Math.max(ref.left, Math.min(cx - regionW / 2, ref.right - regionW));
        int top = Math.max(ref.top, Math.min(cy - regionH / 2, ref.bottom - regionH));
        return new MeteringRectangle(left, top, regionW, regionH, MeteringRectangle.METERING_WEIGHT_MAX - 1);
    }

    // =========================================================================================
    // Still capture
    // =========================================================================================

    private void captureInternal() {
        if (mDevice == null || mConfiguring || mCaptureState != STATE_PREVIEW) {
            return;
        }
        if (mExtSession != null) {
            extensionCapture();
            return;
        }
        if (mSession == null || mStillReader == null) {
            return;
        }
        boolean needPrecapture = mLens.flashAvailable && mMode == MODE_PHOTO
                && (mFlash == FLASH_ON
                || (mFlash == FLASH_AUTO && mAeState != CameraMetadata.CONTROL_AE_STATE_CONVERGED));
        if (needPrecapture) {
            runPrecapture();
        } else {
            captureStill();
        }
    }

    private void runPrecapture() {
        try {
            mRepeatingBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_START);
            mCaptureState = STATE_WAITING_PRECAPTURE;
            mPrecaptureDeadline = SystemClock.elapsedRealtime() + PRECAPTURE_TIMEOUT_MS;
            mSession.capture(mRepeatingBuilder.build(), mPreviewCallback, mCamHandler);
            mRepeatingBuilder.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER,
                    CameraMetadata.CONTROL_AE_PRECAPTURE_TRIGGER_IDLE);
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            mCaptureState = STATE_PREVIEW;
            captureStill();
        }
    }

    private void captureStill() {
        mCaptureState = STATE_CAPTURING;
        if (mSession == null || mStillReader == null || mDevice == null) {
            mCaptureState = STATE_PREVIEW;
            return;
        }
        boolean raw = mRawReader != null && mMode == MODE_PHOTO;
        int pending = raw ? 2 : 1;
        try {
            CaptureRequest.Builder b = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(mStillReader.getSurface());
            if (raw) {
                b.addTarget(mRawReader.getSurface());
            }
            if (mRecording && mRecorderSurface != null && mSessionPreviewSurface != null) {
                // Keep both streams flowing so the clip does not drop a frame for the snapshot.
                b.addTarget(mSessionPreviewSurface);
                b.addTarget(mRecorderSurface);
            }
            applySettings(b, true);
            mPendingSaves.addAndGet(pending);
            postBusy(true);
            mSession.capture(b.build(), mStillCallback, mCamHandler);
            mMain.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onShutter();
                }
            });
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            if (mPendingSaves.addAndGet(-pending) <= 0) {
                mPendingSaves.set(0);
                postBusy(false);
            }
            postError("Capture failed: " + e.getMessage(), false);
        } finally {
            mCaptureState = STATE_PREVIEW; // ZSL: ready for the next shot immediately
        }
    }

    private void extensionCapture() {
        if (mStillReader == null) {
            return;
        }
        try {
            CaptureRequest.Builder b = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE);
            b.addTarget(mStillReader.getSurface());
            applyExtensionSettings(b, true);
            mCaptureState = STATE_CAPTURING;
            mPendingSaves.incrementAndGet();
            postBusy(true);
            mExtSession.capture(b.build());
            mMain.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onShutter();
                }
            });
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            mCaptureState = STATE_PREVIEW;
            captureDone();
            postError("Capture failed: " + e.getMessage(), false);
        }
    }

    private final CameraCaptureSession.CaptureCallback mPreviewCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureProgressed(CameraCaptureSession session, CaptureRequest request, CaptureResult partial) {
            process(partial);
        }

        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            process(result);
        }

        private void process(CaptureResult result) {
            Integer ae = result.get(CaptureResult.CONTROL_AE_STATE);
            if (ae != null) {
                mAeState = ae;
            }
            Integer af = result.get(CaptureResult.CONTROL_AF_STATE);
            if (af != null && mAfWaiting && (af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED
                    || af == CameraMetadata.CONTROL_AF_STATE_NOT_FOCUSED_LOCKED)) {
                mAfWaiting = false;
                final boolean focused = af == CameraMetadata.CONTROL_AF_STATE_FOCUSED_LOCKED;
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        mListener.onFocusResult(focused);
                    }
                });
            }
            boolean timeout = SystemClock.elapsedRealtime() > mPrecaptureDeadline;
            switch (mCaptureState) {
                case STATE_WAITING_PRECAPTURE:
                    if (ae == null || ae == CameraMetadata.CONTROL_AE_STATE_PRECAPTURE
                            || ae == CameraMetadata.CONTROL_AE_STATE_FLASH_REQUIRED) {
                        mCaptureState = STATE_WAITING_NON_PRECAPTURE;
                    } else if (timeout) {
                        captureStill();
                    }
                    break;
                case STATE_WAITING_NON_PRECAPTURE:
                    if (ae == null || ae != CameraMetadata.CONTROL_AE_STATE_PRECAPTURE || timeout) {
                        captureStill();
                    }
                    break;
                default:
                    break;
            }
        }
    };

    private final CameraCaptureSession.CaptureCallback mStillCallback = new CameraCaptureSession.CaptureCallback() {
        @Override
        public void onCaptureCompleted(CameraCaptureSession session, CaptureRequest request, TotalCaptureResult result) {
            Long ts = result.get(CaptureResult.SENSOR_TIMESTAMP);
            if (ts == null) {
                return;
            }
            Image raw = null;
            synchronized (mRawLock) {
                mResults.put(ts, result);
                while (mResults.size() > RESULT_HISTORY) {
                    mResults.removeAt(0);
                }
                int idx = mPendingRaw.indexOfKey(ts);
                if (idx >= 0) {
                    raw = mPendingRaw.valueAt(idx);
                    mPendingRaw.removeAt(idx);
                }
            }
            if (raw != null) {
                writeDng(raw, result);
            }
        }

        @Override
        public void onCaptureFailed(CameraCaptureSession session, CaptureRequest request, CaptureFailure failure) {
            captureDone();
            if (mRawReader != null) {
                captureDone();
            }
            postError("Capture failed (reason " + failure.getReason() + ")", false);
        }
    };

    private final ImageReader.OnImageAvailableListener mStillListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image;
            try {
                image = reader.acquireNextImage();
            } catch (IllegalStateException e) {
                return;
            }
            if (image == null) {
                return;
            }
            byte[] bytes;
            int format;
            try {
                ByteBuffer buffer = image.getPlanes()[0].getBuffer();
                bytes = new byte[buffer.remaining()];
                buffer.get(bytes);
                format = image.getFormat();
            } finally {
                image.close();
            }
            boolean heic = format == ImageFormat.HEIC;
            int expectedRotation = isExtensionMode(mMode) ? mLastJpegOrientation : -1;
            mSaver.saveImage(bytes, heic ? "image/heic" : "image/jpeg", heic ? ".heic" : ".jpg",
                    expectedRotation, mImageSavedCallback);
        }
    };

    private final ImageReader.OnImageAvailableListener mRawListener = new ImageReader.OnImageAvailableListener() {
        @Override
        public void onImageAvailable(ImageReader reader) {
            Image image;
            try {
                image = reader.acquireNextImage();
            } catch (IllegalStateException e) {
                return;
            }
            if (image == null) {
                return;
            }
            final long ts = image.getTimestamp();
            TotalCaptureResult result;
            synchronized (mRawLock) {
                result = mResults.get(ts);
                if (result == null) {
                    mPendingRaw.put(ts, image);
                }
            }
            if (result != null) {
                writeDng(image, result);
            } else if (mImgHandler != null) {
                mImgHandler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        Image orphan = null;
                        synchronized (mRawLock) {
                            int idx = mPendingRaw.indexOfKey(ts);
                            if (idx >= 0) {
                                orphan = mPendingRaw.valueAt(idx);
                                mPendingRaw.removeAt(idx);
                            }
                        }
                        if (orphan != null) {
                            orphan.close();
                            captureDone();
                        }
                    }
                }, RAW_RESULT_TIMEOUT_MS);
            }
        }
    };

    private void writeDng(Image image, TotalCaptureResult result) {
        try {
            DngCreator creator = new DngCreator(mLens.chars, result);
            creator.setOrientation(exifOrientation(mLastJpegOrientation));
            mSaver.saveDng(creator, image, mImageSavedCallback);
        } catch (IllegalArgumentException | IllegalStateException e) {
            image.close();
            captureDone();
            postError("RAW save failed: " + e.getMessage(), false);
        }
    }

    private final MediaSaver.Callback mImageSavedCallback = new MediaSaver.Callback() {
        @Override
        public void onSaved(Uri uri, String mime) {
            captureDone();
            mListener.onMediaSaved(uri, mime);
        }

        @Override
        public void onError(String message) {
            captureDone();
            mListener.onCameraError("Save failed: " + message, false);
        }
    };

    private final MediaSaver.Callback mVideoSavedCallback = new MediaSaver.Callback() {
        @Override
        public void onSaved(Uri uri, String mime) {
            mListener.onMediaSaved(uri, mime);
        }

        @Override
        public void onError(String message) {
            mListener.onCameraError("Video save failed: " + message, false);
        }
    };

    private void captureDone() {
        int left = mPendingSaves.decrementAndGet();
        if (left < 0) {
            mPendingSaves.set(0);
            left = 0;
        }
        postBusy(left > 0);
    }

    // =========================================================================================
    // Video
    // =========================================================================================

    private void startRecordingInternal() {
        if (mMode != MODE_VIDEO || mRecording || mDevice == null || mSession == null || mRecorderSurface == null
                || mVideoConfig == null || mSessionPreviewSurface == null) {
            return;
        }
        try {
            mVideoConfig.audio = mAudioEnabled;
            mVideoConfig.orientationHint = jpegOrientation();
            mPendingVideo = mSaver.createVideo(mVideoConfig.mime());
            CaptureRequest.Builder b = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_RECORD);
            b.addTarget(mSessionPreviewSurface);
            b.addTarget(mRecorderSurface);
            mRepeatingBuilder = b;
            mRecording = true;
            applySettings(b, false);
            mSession.setRepeatingRequest(b.build(), mPreviewCallback, mCamHandler);
            mVideo.start(mVideoConfig, mPendingVideo);
            mRecordStart = SystemClock.elapsedRealtime();
            mMain.post(new Runnable() {
                @Override
                public void run() {
                    mListener.onRecordingStateChanged(true);
                }
            });
        } catch (IOException | CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.e(TAG, "startRecording failed", e);
            mRecording = false;
            mVideo.stop();
            if (mPendingVideo != null) {
                mSaver.finishVideo(mPendingVideo, false, null);
                mPendingVideo = null;
            }
            restorePreviewRequest();
            postError("Recording failed: " + e.getMessage(), false);
        }
    }

    private void stopRecordingInternal() {
        if (!mRecording) {
            return;
        }
        mRecording = false;
        boolean ok = mVideo.stop();
        restorePreviewRequest();
        MediaSaver.PendingVideo v = mPendingVideo;
        mPendingVideo = null;
        mSaver.finishVideo(v, ok, ok ? mVideoSavedCallback : null);
        mMain.post(new Runnable() {
            @Override
            public void run() {
                mListener.onRecordingStateChanged(false);
            }
        });
        if (!ok) {
            postError("Clip was too short to save", false);
        }
    }

    private void restorePreviewRequest() {
        if (mDevice == null || mSession == null || mSessionPreviewSurface == null) {
            return;
        }
        try {
            CaptureRequest.Builder b = mDevice.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW);
            b.addTarget(mSessionPreviewSurface);
            mRepeatingBuilder = b;
            applySettings(b, false);
            setRepeating();
        } catch (CameraAccessException | IllegalStateException | IllegalArgumentException e) {
            Log.w(TAG, "restorePreviewRequest: " + e);
        }
    }

    // =========================================================================================
    // Teardown
    // =========================================================================================

    private void closeSession() {
        Handler h = mCamHandler;
        if (h != null) {
            h.removeCallbacks(mAfReset);
            h.removeCallbacks(mUpdateRepeating);
        }
        mAfTapActive = false;
        mAfWaiting = false;
        mAfRegions = null;
        mAeRegions = null;
        mCaptureState = STATE_PREVIEW;
        mRepeatingBuilder = null;
        if (mExtSession != null) {
            mExtSession.close();
            mExtSession = null;
        }
        if (mSession != null) {
            try {
                mSession.close();
            } catch (IllegalStateException ignored) {
                // device already gone
            }
            mSession = null;
        }
    }

    private void closeDevice() {
        if (mDevice != null) {
            try {
                mDevice.close();
            } catch (IllegalStateException ignored) {
                // already closed
            }
            mDevice = null;
        }
    }

    private void releaseReaders() {
        if (mStillReader != null) {
            mStillReader.close();
            mStillReader = null;
        }
        if (mRawReader != null) {
            mRawReader.close();
            mRawReader = null;
        }
        synchronized (mRawLock) {
            for (int i = 0; i < mPendingRaw.size(); i++) {
                mPendingRaw.valueAt(i).close();
            }
            mPendingRaw.clear();
            mResults.clear();
        }
    }

    // =========================================================================================
    // Helpers
    // =========================================================================================

    private void postError(final String message, final boolean fatal) {
        Log.e(TAG, message);
        mMain.post(new Runnable() {
            @Override
            public void run() {
                mListener.onCameraError(message, fatal);
            }
        });
    }

    private void postBusy(final boolean busy) {
        mMain.post(new Runnable() {
            @Override
            public void run() {
                mListener.onCaptureBusy(busy);
            }
        });
    }

    private void postProgress(final int percent) {
        mMain.post(new Runnable() {
            @Override
            public void run() {
                mListener.onProcessingProgress(percent);
            }
        });
    }

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static long area(Size s) {
        return (long) s.getWidth() * s.getHeight();
    }

    private static double aspect(Size s) {
        return (double) s.getWidth() / s.getHeight();
    }

    private static boolean aspectMatches(Size s, double aspect) {
        return Math.abs(aspect(s) - aspect) < ASPECT_TOLERANCE;
    }

    private static Size largestWithAspect(Size[] sizes, double aspect, long maxPixels) {
        if (sizes == null) {
            return null;
        }
        Size best = null;
        for (Size s : sizes) {
            if (!aspectMatches(s, aspect) || area(s) > maxPixels) {
                continue;
            }
            if (best == null || area(s) > area(best)) {
                best = s;
            }
        }
        return best;
    }

    private static Size[] toArray(List<Size> list) {
        if (list == null) {
            return new Size[0];
        }
        return list.toArray(new Size[0]);
    }

    /** Short label used by the Activity for the mode chips. */
    public static String modeLabel(Context ctx, int mode) {
        switch (mode) {
            case MODE_VIDEO:
                return ctx.getString(R.string.mode_video);
            case MODE_NIGHT:
                return ctx.getString(R.string.mode_night);
            case MODE_HDR:
                return ctx.getString(R.string.mode_hdr);
            case MODE_PORTRAIT:
                return ctx.getString(R.string.mode_portrait);
            case MODE_BEAUTY:
                return ctx.getString(R.string.mode_beauty);
            case MODE_AUTO:
                return ctx.getString(R.string.mode_auto);
            default:
                return ctx.getString(R.string.mode_photo);
        }
    }

    static String zoomLabel(float zoom) {
        return String.format(Locale.US, zoom < 10f ? "%.1fx" : "%.0fx", zoom);
    }
}
