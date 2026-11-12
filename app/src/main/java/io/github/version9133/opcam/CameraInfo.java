package io.github.version9133.opcam;

import android.content.Context;
import android.graphics.ImageFormat;
import android.graphics.Rect;
import android.hardware.camera2.CameraAccessException;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraManager;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.media.MediaRecorder;
import android.os.Build;
import android.util.Log;
import android.util.Range;
import android.util.Rational;
import android.util.Size;
import android.util.SizeF;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Enumerates every camera the device will let us use.
 *
 * <p>Besides {@code getCameraIdList()} this also probes numeric ids 0..9. OnePlus / Oppo / Realme
 * HALs frequently keep the ultra-wide, telephoto and macro modules out of the public id list but
 * still answer {@code getCameraCharacteristics()} for them - the same trick GCam ports rely on.</p>
 */
public final class CameraInfo {

    private static final String TAG = "CameraInfo";
    private static final int PROBE_MAX_ID = 9;
    /** 35 mm full-frame diagonal in mm. */
    private static final double FULL_FRAME_DIAGONAL = 43.27d;

    private static List<LensInfo> sCache;
    private static boolean sCacheProbed;

    private CameraInfo() {
    }

    public static synchronized void invalidate() {
        sCache = null;
    }

    public static synchronized List<LensInfo> getLenses(Context context, boolean probeHidden) {
        if (sCache != null && sCacheProbed == probeHidden) {
            return sCache;
        }
        sCache = enumerate(context, probeHidden);
        sCacheProbed = probeHidden;
        return sCache;
    }

    public static LensInfo find(List<LensInfo> lenses, String id) {
        if (id == null) {
            return null;
        }
        for (LensInfo l : lenses) {
            if (l.id.equals(id)) {
                return l;
            }
        }
        return null;
    }

    public static LensInfo defaultBack(List<LensInfo> lenses) {
        LensInfo best = null;
        for (LensInfo l : lenses) {
            if (!l.isBack() || !l.listed) {
                continue;
            }
            // The 1x main camera; otherwise the first listed back camera.
            if (best == null || (Math.abs(l.zoomFactor - 1f) < Math.abs(best.zoomFactor - 1f))) {
                best = l;
            }
        }
        if (best == null) {
            for (LensInfo l : lenses) {
                if (l.isBack()) {
                    return l;
                }
            }
        }
        return best != null ? best : (lenses.isEmpty() ? null : lenses.get(0));
    }

    public static LensInfo defaultFront(List<LensInfo> lenses) {
        for (LensInfo l : lenses) {
            if (l.isFront() && l.listed) {
                return l;
            }
        }
        for (LensInfo l : lenses) {
            if (l.isFront()) {
                return l;
            }
        }
        return null;
    }

    /** Lenses of the same facing, sorted ultra-wide -> tele. */
    public static List<LensInfo> sameFacing(List<LensInfo> lenses, int facing) {
        List<LensInfo> out = new ArrayList<>();
        for (LensInfo l : lenses) {
            if (l.facing == facing) {
                out.add(l);
            }
        }
        Collections.sort(out, new Comparator<LensInfo>() {
            @Override
            public int compare(LensInfo a, LensInfo b) {
                return Float.compare(a.zoomFactor, b.zoomFactor);
            }
        });
        return out;
    }

    private static List<LensInfo> enumerate(Context context, boolean probeHidden) {
        CameraManager mgr = (CameraManager) context.getSystemService(Context.CAMERA_SERVICE);
        List<LensInfo> result = new ArrayList<>();
        if (mgr == null) {
            return result;
        }
        Set<String> listed = new LinkedHashSet<>();
        try {
            Collections.addAll(listed, mgr.getCameraIdList());
        } catch (CameraAccessException | RuntimeException e) {
            Log.w(TAG, "getCameraIdList failed", e);
        }
        Set<String> candidates = new LinkedHashSet<>(listed);
        if (probeHidden) {
            for (int i = 0; i <= PROBE_MAX_ID; i++) {
                candidates.add(String.valueOf(i));
            }
        }
        for (String id : candidates) {
            boolean isListed = listed.contains(id);
            try {
                LensInfo info = describe(mgr, id, isListed);
                if (info != null) {
                    result.add(info);
                }
            } catch (IllegalArgumentException e) {
                // Not a valid id on this device - expected for probed ids.
            } catch (CameraAccessException | RuntimeException e) {
                Log.w(TAG, "Cannot query camera " + id + ": " + e);
            }
        }

        // Reference: the listed back camera closest to a "normal" 24-27 mm equivalent lens.
        LensInfo main = null;
        for (LensInfo l : result) {
            if (l.isBack() && l.listed && l.eqFocalLength > 0) {
                if (main == null || Math.abs(l.eqFocalLength - 25f) < Math.abs(main.eqFocalLength - 25f)) {
                    main = l;
                }
            }
        }
        float mainEq = main != null ? main.eqFocalLength : 0f;
        for (LensInfo l : result) {
            if (mainEq > 0 && l.eqFocalLength > 0 && l.isBack()) {
                l.zoomFactor = l.eqFocalLength / mainEq;
            } else {
                l.zoomFactor = 1f;
            }
            l.label = formatZoom(l.zoomFactor);
        }

        // Drop hidden ids (and listed physical duplicates) that merely duplicate a listed camera.
        List<LensInfo> deduped = new ArrayList<>();
        for (LensInfo l : result) {
            if (!l.listed && !l.isBack()) {
                continue; // only probe for back-facing aux modules
            }
            boolean duplicate = false;
            for (LensInfo kept : deduped) {
                if (kept.facing == l.facing && Math.abs(kept.eqFocalLength - l.eqFocalLength) < 1.0f
                        && sameSensor(kept, l)) {
                    duplicate = true;
                    break;
                }
            }
            if (!duplicate) {
                deduped.add(l);
            }
        }
        Log.i(TAG, "Cameras: " + deduped);
        return deduped;
    }

    private static boolean sameSensor(LensInfo a, LensInfo b) {
        if (a.activeArray == null || b.activeArray == null) {
            return true;
        }
        return a.activeArray.width() == b.activeArray.width() && a.activeArray.height() == b.activeArray.height();
    }

    private static LensInfo describe(CameraManager mgr, String id, boolean listed) throws CameraAccessException {
        CameraCharacteristics c = mgr.getCameraCharacteristics(id);
        StreamConfigurationMap map = c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP);
        if (map == null) {
            return null;
        }
        Size[] jpegSizes = map.getOutputSizes(ImageFormat.JPEG);
        if (jpegSizes == null || jpegSizes.length == 0) {
            return null;
        }
        Integer facingObj = c.get(CameraCharacteristics.LENS_FACING);
        int facing = facingObj != null ? facingObj : CameraMetadata.LENS_FACING_EXTERNAL;
        int[] caps = c.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES);
        boolean logical = hasCap(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA);
        boolean raw = hasCap(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_RAW);
        boolean uhr = Build.VERSION.SDK_INT >= 31
                && hasCap(caps, CameraMetadata.REQUEST_AVAILABLE_CAPABILITIES_ULTRA_HIGH_RESOLUTION_SENSOR);
        Integer level = c.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL);
        Rect active = c.get(CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE);
        Integer orientation = c.get(CameraCharacteristics.SENSOR_ORIENTATION);

        float focal = 0f;
        float[] focals = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS);
        if (focals != null && focals.length > 0) {
            focal = focals[0];
        }
        float eqFocal = 0f;
        SizeF physical = c.get(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE);
        Size pixelArray = c.get(CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE);
        if (focal > 0 && physical != null && physical.getWidth() > 0 && physical.getHeight() > 0) {
            double w = physical.getWidth();
            double h = physical.getHeight();
            if (active != null && pixelArray != null && pixelArray.getWidth() > 0 && pixelArray.getHeight() > 0) {
                w = w * active.width() / pixelArray.getWidth();
                h = h * active.height() / pixelArray.getHeight();
            }
            double diag = Math.sqrt(w * w + h * h);
            if (diag > 0) {
                eqFocal = (float) (focal * FULL_FRAME_DIAGONAL / diag);
            }
        }

        Size maxJpeg = largest(jpegSizes);
        Size maxHeic = null;
        if (Build.VERSION.SDK_INT >= 29) {
            try {
                if (map.isOutputSupportedFor(ImageFormat.HEIC)) {
                    maxHeic = largest(map.getOutputSizes(ImageFormat.HEIC));
                }
            } catch (IllegalArgumentException ignored) {
                // Format unknown to this HAL.
            }
        }
        Size maxRaw = null;
        if (raw) {
            try {
                maxRaw = largest(map.getOutputSizes(ImageFormat.RAW_SENSOR));
            } catch (IllegalArgumentException ignored) {
                // No RAW stream despite the capability flag.
            }
        }
        Size maxUhrJpeg = null;
        if (uhr) {
            StreamConfigurationMap maxResMap =
                    c.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION);
            if (maxResMap != null) {
                maxUhrJpeg = largest(maxResMap.getOutputSizes(ImageFormat.JPEG));
            }
            if (maxUhrJpeg == null) {
                uhr = false;
            }
        }

        Boolean flash = c.get(CameraCharacteristics.FLASH_INFO_AVAILABLE);
        int[] oisModes = c.get(CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION);
        boolean ois = contains(oisModes, CameraMetadata.LENS_OPTICAL_STABILIZATION_MODE_ON);
        int[] vsModes = c.get(CameraCharacteristics.CONTROL_AVAILABLE_VIDEO_STABILIZATION_MODES);
        boolean eis = contains(vsModes, CameraMetadata.CONTROL_VIDEO_STABILIZATION_MODE_ON);

        Range<Float> zoomRange = null;
        if (Build.VERSION.SDK_INT >= 30) {
            zoomRange = c.get(CameraCharacteristics.CONTROL_ZOOM_RATIO_RANGE);
        }
        Float maxDigital = c.get(CameraCharacteristics.SCALER_AVAILABLE_MAX_DIGITAL_ZOOM);
        Range<Integer> evRange = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE);
        Rational evStepR = c.get(CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP);
        float evStep = evStepR != null && evStepR.getDenominator() != 0 ? evStepR.floatValue() : 0f;
        Integer maxAf = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AF);
        Integer maxAe = c.get(CameraCharacteristics.CONTROL_MAX_REGIONS_AE);
        int[] afModes = c.get(CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES);
        Range<Integer>[] fpsRanges = c.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES);

        int[] extensions = new int[0];
        if (Build.VERSION.SDK_INT >= 31 && listed) {
            extensions = ExtensionsHelper.supportedExtensions(mgr, id);
        }

        return new LensInfo(id, c, map, facing, listed, logical,
                level != null ? level : CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY,
                active, orientation != null ? orientation : 90, focal, eqFocal, maxJpeg, maxHeic, maxRaw,
                uhr, maxUhrJpeg, flash != null && flash, ois, eis, zoomRange,
                maxDigital != null ? maxDigital : 1f,
                evRange != null ? evRange : new Range<>(0, 0), evStep,
                maxAf != null ? maxAf : 0, maxAe != null ? maxAe : 0, afModes, fpsRanges, extensions);
    }

    static boolean hasCap(int[] caps, int cap) {
        return contains(caps, cap);
    }

    static boolean contains(int[] values, int value) {
        if (values == null) {
            return false;
        }
        for (int v : values) {
            if (v == value) {
                return true;
            }
        }
        return false;
    }

    static Size largest(Size[] sizes) {
        if (sizes == null || sizes.length == 0) {
            return null;
        }
        Size best = sizes[0];
        long bestArea = (long) best.getWidth() * best.getHeight();
        for (Size s : sizes) {
            long area = (long) s.getWidth() * s.getHeight();
            if (area > bestArea) {
                best = s;
                bestArea = area;
            }
        }
        return best;
    }

    public static String formatZoom(float factor) {
        if (factor < 0.95f) {
            return String.format(Locale.US, "%.1fx", factor);
        }
        float rounded = Math.round(factor);
        if (Math.abs(factor - rounded) < 0.12f) {
            return String.format(Locale.US, "%dx", (int) rounded);
        }
        return String.format(Locale.US, "%.1fx", factor);
    }

    /** Human-readable capability report shown in Settings - handy for OnePlus users comparing firmware. */
    public static String describeAll(Context context, List<LensInfo> lenses) {
        StringBuilder sb = new StringBuilder();
        sb.append(Build.MANUFACTURER).append(' ').append(Build.MODEL)
                .append(" (").append(Build.DEVICE).append(")\n")
                .append("Android ").append(Build.VERSION.RELEASE).append(" / API ").append(Build.VERSION.SDK_INT)
                .append('\n');
        if (isOnePlusFamily()) {
            sb.append("OnePlus/Oppo HAL detected: aux-lens probing and extension modes enabled\n");
        }
        sb.append("HEVC encoder: ").append(VideoRecorder.hasHevcEncoder() ? "yes" : "no").append("\n\n");
        for (LensInfo l : lenses) {
            sb.append("Camera ").append(l.id).append(" - ").append(l.facingName());
            if (!l.listed) {
                sb.append(", hidden aux");
            }
            if (l.logical) {
                sb.append(", logical multi-camera");
                if (Build.VERSION.SDK_INT >= 28) {
                    sb.append(' ').append(l.chars.getPhysicalCameraIds());
                }
            }
            sb.append('\n');
            sb.append("  Level ").append(l.hardwareLevelName()).append(" · ").append(l.label);
            if (l.focalLength > 0) {
                sb.append(String.format(Locale.US, " · %.2f mm (%.0f mm eq.)", l.focalLength, l.eqFocalLength));
            }
            sb.append('\n');
            sb.append("  Max JPEG ").append(sizeStr(l.maxJpeg)).append(" (").append(LensInfo.megapixels(l.maxJpeg))
                    .append(")\n");
            sb.append("  HEIC: ").append(l.maxHeic != null ? sizeStr(l.maxHeic) : "no").append('\n');
            sb.append("  RAW: ").append(l.maxRaw != null ? sizeStr(l.maxRaw) : "no").append('\n');
            if (l.uhrSupported) {
                sb.append("  Full-resolution mode: ").append(sizeStr(l.maxUhrJpeg)).append(" (")
                        .append(LensInfo.megapixels(l.maxUhrJpeg)).append(")\n");
            }
            if (l.zoomRatioRange != null) {
                sb.append(String.format(Locale.US, "  Zoom ratio %.1fx - %.1fx", l.zoomRatioRange.getLower(),
                        l.zoomRatioRange.getUpper())).append('\n');
            } else {
                sb.append(String.format(Locale.US, "  Digital zoom up to %.1fx", l.maxDigitalZoom)).append('\n');
            }
            sb.append("  Flash: ").append(l.flashAvailable ? "yes" : "no").append(" · OIS: ")
                    .append(l.ois ? "yes" : "no").append(" · EIS: ").append(l.videoStabilization ? "yes" : "no")
                    .append('\n');
            sb.append("  Video: ").append(VideoRecorder.describeVideoOptions(l)).append('\n');
            if (l.extensions.length > 0) {
                sb.append("  Extensions:");
                for (int e : l.extensions) {
                    sb.append(' ').append(ExtensionsHelper.name(e));
                }
                sb.append('\n');
            }
            sb.append('\n');
        }
        return sb.toString();
    }

    public static boolean isOnePlusFamily() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.US);
        return m.contains("oneplus") || m.contains("oppo") || m.contains("realme");
    }

    static String sizeStr(Size s) {
        return s == null ? "-" : s.getWidth() + "x" + s.getHeight();
    }

    /** Sizes usable by MediaRecorder for a lens (used by Settings + VideoRecorder). */
    static Size[] recorderSizes(LensInfo l) {
        Size[] sizes = l.map.getOutputSizes(MediaRecorder.class);
        return sizes != null ? sizes : new Size[0];
    }
}
