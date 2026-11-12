package io.github.version9133.opcam;

import android.graphics.Rect;
import android.hardware.camera2.CameraCharacteristics;
import android.hardware.camera2.CameraMetadata;
import android.hardware.camera2.params.StreamConfigurationMap;
import android.util.Range;
import android.util.Size;

import java.util.Locale;

/** Immutable snapshot of what one camera (logical, physical or hidden aux) can do. */
public final class LensInfo {

    public final String id;
    public final CameraCharacteristics chars;
    public final StreamConfigurationMap map;
    /** {@link CameraCharacteristics#LENS_FACING} value. */
    public final int facing;
    /** True if the id was returned by {@code getCameraIdList()}; false for probed hidden ids. */
    public final boolean listed;
    public final boolean logical;
    public final int hardwareLevel;
    public final Rect activeArray;
    public final int sensorOrientation;
    /** Native focal length in mm (0 if unknown). */
    public final float focalLength;
    /** 35 mm equivalent focal length (0 if unknown). */
    public final float eqFocalLength;
    public final Size maxJpeg;
    /** Largest HEIC size or null when the HAL has no hardware HEIC path. */
    public final Size maxHeic;
    public final Size maxRaw;
    /** Ultra-high-resolution (quad-bayer remosaic) capability, Android 12+. */
    public final boolean uhrSupported;
    public final Size maxUhrJpeg;
    public final boolean flashAvailable;
    public final boolean ois;
    public final boolean videoStabilization;
    /** Zoom-ratio range (Android 11+) or null. */
    public final Range<Float> zoomRatioRange;
    public final float maxDigitalZoom;
    public final Range<Integer> evRange;
    public final float evStep;
    public final int maxAfRegions;
    public final int maxAeRegions;
    public final int[] afModes;
    public final Range<Integer>[] fpsRanges;
    /** Supported Camera2 extension ids ({@code CameraExtensionCharacteristics.EXTENSION_*}), Android 12+. */
    public final int[] extensions;

    /** Filled in by {@link CameraInfo}: focal length relative to the main back camera. */
    public float zoomFactor = 1f;
    public String label = "1x";

    LensInfo(String id, CameraCharacteristics chars, StreamConfigurationMap map, int facing, boolean listed,
             boolean logical, int hardwareLevel, Rect activeArray, int sensorOrientation, float focalLength,
             float eqFocalLength, Size maxJpeg, Size maxHeic, Size maxRaw, boolean uhrSupported, Size maxUhrJpeg,
             boolean flashAvailable, boolean ois, boolean videoStabilization, Range<Float> zoomRatioRange,
             float maxDigitalZoom, Range<Integer> evRange, float evStep, int maxAfRegions, int maxAeRegions,
             int[] afModes, Range<Integer>[] fpsRanges, int[] extensions) {
        this.id = id;
        this.chars = chars;
        this.map = map;
        this.facing = facing;
        this.listed = listed;
        this.logical = logical;
        this.hardwareLevel = hardwareLevel;
        this.activeArray = activeArray;
        this.sensorOrientation = sensorOrientation;
        this.focalLength = focalLength;
        this.eqFocalLength = eqFocalLength;
        this.maxJpeg = maxJpeg;
        this.maxHeic = maxHeic;
        this.maxRaw = maxRaw;
        this.uhrSupported = uhrSupported;
        this.maxUhrJpeg = maxUhrJpeg;
        this.flashAvailable = flashAvailable;
        this.ois = ois;
        this.videoStabilization = videoStabilization;
        this.zoomRatioRange = zoomRatioRange;
        this.maxDigitalZoom = maxDigitalZoom;
        this.evRange = evRange;
        this.evStep = evStep;
        this.maxAfRegions = maxAfRegions;
        this.maxAeRegions = maxAeRegions;
        this.afModes = afModes;
        this.fpsRanges = fpsRanges;
        this.extensions = extensions;
    }

    public boolean isFront() {
        return facing == CameraMetadata.LENS_FACING_FRONT;
    }

    public boolean isBack() {
        return facing == CameraMetadata.LENS_FACING_BACK;
    }

    public boolean rawSupported() {
        return maxRaw != null;
    }

    public boolean heicSupported() {
        return maxHeic != null;
    }

    public boolean hasExtension(int extension) {
        for (int e : extensions) {
            if (e == extension) {
                return true;
            }
        }
        return false;
    }

    public boolean supportsAfMode(int mode) {
        if (afModes == null) {
            return false;
        }
        for (int m : afModes) {
            if (m == mode) {
                return true;
            }
        }
        return false;
    }

    /** Largest size the sensor can deliver as a still, taking the UHR mode into account. */
    public Size largestStill() {
        if (uhrSupported && maxUhrJpeg != null) {
            return maxUhrJpeg;
        }
        return maxJpeg;
    }

    public String hardwareLevelName() {
        switch (hardwareLevel) {
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY:
                return "LEGACY";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED:
                return "LIMITED";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_FULL:
                return "FULL";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_3:
                return "LEVEL_3";
            case CameraMetadata.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL:
                return "EXTERNAL";
            default:
                return "UNKNOWN(" + hardwareLevel + ")";
        }
    }

    public String facingName() {
        switch (facing) {
            case CameraMetadata.LENS_FACING_BACK:
                return "back";
            case CameraMetadata.LENS_FACING_FRONT:
                return "front";
            default:
                return "external";
        }
    }

    public static String megapixels(Size s) {
        if (s == null) {
            return "-";
        }
        double mp = (double) s.getWidth() * s.getHeight() / 1_000_000d;
        return String.format(Locale.US, mp >= 10 ? "%.0f MP" : "%.1f MP", mp);
    }

    @Override
    public String toString() {
        return "Camera " + id + " (" + facingName() + (listed ? "" : ", hidden") + ", " + label + ")";
    }
}
