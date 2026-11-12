package io.github.version9133.opcam;

import android.annotation.TargetApi;
import android.graphics.ImageFormat;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraDevice;
import android.hardware.camera2.CameraExtensionCharacteristics;
import android.hardware.camera2.CameraExtensionSession;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CaptureRequest;
import android.hardware.camera2.TotalCaptureResult;
import android.hardware.camera2.params.ExtensionSessionConfiguration;
import android.hardware.camera2.params.OutputConfiguration;
import android.os.Build;
import android.util.Log;
import android.util.Size;
import android.view.Surface;
import android.view.SurfaceHolder;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;
import java.util.concurrent.Executor;

/**
 * Camera2 Extensions (Android 12+): runs the vendor's own Night / HDR / Portrait / Face-retouch
 * pipelines - on OnePlus this is the same processing the stock camera uses, exposed through the
 * public API. Everything that references {@code android.hardware.camera2.CameraExtension*} lives
 * here so the class is only loaded on supported OS versions.
 */
@TargetApi(31)
final class ExtensionsHelper {

    private static final String TAG = "Extensions";

    /** Minimal session facade so the controller never touches the API-31 types directly. */
    interface ExtSession {
        void setRepeating(CaptureRequest request) throws CameraAccessException;

        void capture(CaptureRequest request) throws CameraAccessException;

        void stopRepeating();

        void close();
    }

    interface Callback {
        void onConfigured(ExtSession session);

        void onConfigureFailed();

        void onClosed();

        void onProcessStarted();

        void onProgress(int percent);

        void onCaptureFailed();

        void onSequenceCompleted();
    }

    private ExtensionsHelper() {
    }

    /** Extensions that have both a JPEG still size and a SurfaceHolder preview size. */
    static int[] supportedExtensions(CameraManager mgr, String cameraId) {
        try {
            CameraExtensionCharacteristics ec = mgr.getCameraExtensionCharacteristics(cameraId);
            List<Integer> all = ec.getSupportedExtensions();
            List<Integer> usable = new ArrayList<>();
            // Preferred UI order.
            int[] order = {
                    CameraExtensionCharacteristics.EXTENSION_NIGHT,
                    CameraExtensionCharacteristics.EXTENSION_HDR,
                    CameraExtensionCharacteristics.EXTENSION_BOKEH,
                    CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH,
                    CameraExtensionCharacteristics.EXTENSION_AUTOMATIC,
            };
            for (int ext : order) {
                if (!all.contains(ext)) {
                    continue;
                }
                List<Size> stills = ec.getExtensionSupportedSizes(ext, ImageFormat.JPEG);
                List<Size> previews = ec.getExtensionSupportedSizes(ext, SurfaceHolder.class);
                if (stills != null && !stills.isEmpty() && previews != null && !previews.isEmpty()) {
                    usable.add(ext);
                }
            }
            int[] out = new int[usable.size()];
            for (int i = 0; i < out.length; i++) {
                out[i] = usable.get(i);
            }
            return out;
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "No extension characteristics for camera " + cameraId + ": " + e);
            return new int[0];
        }
    }

    static List<Size> stillSizes(CameraManager mgr, String cameraId, int extension) {
        try {
            return mgr.getCameraExtensionCharacteristics(cameraId)
                    .getExtensionSupportedSizes(extension, ImageFormat.JPEG);
        } catch (CameraAccessException | RuntimeException e) {
            return new ArrayList<>();
        }
    }

    static List<Size> previewSizes(CameraManager mgr, String cameraId, int extension) {
        try {
            return mgr.getCameraExtensionCharacteristics(cameraId)
                    .getExtensionSupportedSizes(extension, SurfaceHolder.class);
        } catch (CameraAccessException | RuntimeException e) {
            return new ArrayList<>();
        }
    }

    /**
     * Whether a request key may be set in an extension session. Android 13 exposes the exact list;
     * on Android 12 zoom and AF controls are the documented supported subset.
     */
    static boolean supportsRequestKey(CameraManager mgr, String cameraId, int extension, CaptureRequest.Key<?> key) {
        if (Build.VERSION.SDK_INT >= 33) {
            try {
                Set<CaptureRequest.Key> keys =
                        mgr.getCameraExtensionCharacteristics(cameraId).getAvailableCaptureRequestKeys(extension);
                return keys.contains(key);
            } catch (CameraAccessException | RuntimeException e) {
                return false;
            }
        }
        return key.equals(CaptureRequest.CONTROL_ZOOM_RATIO)
                || key.equals(CaptureRequest.CONTROL_AF_MODE)
                || key.equals(CaptureRequest.CONTROL_AF_REGIONS)
                || key.equals(CaptureRequest.CONTROL_AF_TRIGGER)
                || key.equals(CaptureRequest.JPEG_ORIENTATION)
                || key.equals(CaptureRequest.JPEG_QUALITY);
    }

    static void createSession(CameraDevice device, int extension, Surface preview, Surface still,
                              final Executor executor, final Callback cb) throws CameraAccessException {
        List<OutputConfiguration> outputs = Arrays.asList(
                new OutputConfiguration(preview), new OutputConfiguration(still));
        ExtensionSessionConfiguration config = new ExtensionSessionConfiguration(extension, outputs, executor,
                new CameraExtensionSession.StateCallback() {
                    @Override
                    public void onConfigured(CameraExtensionSession session) {
                        cb.onConfigured(new SessionImpl(session, executor, cb));
                    }

                    @Override
                    public void onConfigureFailed(CameraExtensionSession session) {
                        cb.onConfigureFailed();
                    }

                    @Override
                    public void onClosed(CameraExtensionSession session) {
                        cb.onClosed();
                    }
                });
        device.createExtensionSession(config);
    }

    static String name(int extension) {
        switch (extension) {
            case CameraExtensionCharacteristics.EXTENSION_NIGHT:
                return "NIGHT";
            case CameraExtensionCharacteristics.EXTENSION_HDR:
                return "HDR";
            case CameraExtensionCharacteristics.EXTENSION_BOKEH:
                return "PORTRAIT";
            case CameraExtensionCharacteristics.EXTENSION_FACE_RETOUCH:
                return "BEAUTY";
            case CameraExtensionCharacteristics.EXTENSION_AUTOMATIC:
                return "AUTO";
            default:
                return "EXT" + extension;
        }
    }

    private static final class SessionImpl implements ExtSession {
        private final CameraExtensionSession mSession;
        private final Executor mExecutor;
        private final CameraExtensionSession.ExtensionCaptureCallback mCaptureCallback;

        SessionImpl(CameraExtensionSession session, Executor executor, final Callback cb) {
            mSession = session;
            mExecutor = executor;
            mCaptureCallback = new CameraExtensionSession.ExtensionCaptureCallback() {
                @Override
                public void onCaptureProcessStarted(CameraExtensionSession s, CaptureRequest request) {
                    if (isStill(request)) {
                        cb.onProcessStarted();
                    }
                }

                @Override
                public void onCaptureFailed(CameraExtensionSession s, CaptureRequest request) {
                    if (isStill(request)) {
                        cb.onCaptureFailed();
                    }
                }

                @Override
                public void onCaptureSequenceCompleted(CameraExtensionSession s, int sequenceId) {
                    cb.onSequenceCompleted();
                }

                @Override
                public void onCaptureSequenceAborted(CameraExtensionSession s, int sequenceId) {
                    cb.onCaptureFailed();
                }

                @Override
                public void onCaptureResultAvailable(CameraExtensionSession s, CaptureRequest request,
                                                     TotalCaptureResult result) {
                    // Metadata only; the image arrives through the ImageReader.
                }

                @Override
                public void onCaptureProcessProgressed(CameraExtensionSession s, CaptureRequest request,
                                                       int progress) {
                    cb.onProgress(progress);
                }
            };
        }

        private static boolean isStill(CaptureRequest request) {
            Object tag = request.getTag();
            return tag != null && "still".equals(tag.toString());
        }

        @Override
        public void setRepeating(CaptureRequest request) throws CameraAccessException {
            mSession.setRepeatingRequest(request, mExecutor, mCaptureCallback);
        }

        @Override
        public void capture(CaptureRequest request) throws CameraAccessException {
            mSession.capture(request, mExecutor, mCaptureCallback);
        }

        @Override
        public void stopRepeating() {
            try {
                mSession.stopRepeating();
            } catch (CameraAccessException | IllegalStateException e) {
                Log.w(TAG, "stopRepeating: " + e);
            }
        }

        @Override
        public void close() {
            try {
                mSession.close();
            } catch (CameraAccessException | IllegalStateException e) {
                Log.w(TAG, "close: " + e);
            }
        }
    }
}
