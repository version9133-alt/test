package io.github.version9133.opcam;

import android.content.Context;
import android.media.CamcorderProfile;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaRecorder;
import android.util.Log;
import android.util.Range;
import android.util.Size;
import android.view.Surface;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * MediaRecorder wrapper built around a persistent encoder input surface. The camera session is
 * configured once with that surface, so starting / stopping a clip never tears the session down -
 * recording begins the instant the button is pressed instead of after a 300-600 ms reconfigure.
 */
final class VideoRecorder {

    private static final String TAG = "VideoRecorder";
    private static final long NS_60FPS = 1_000_000_000L / 60L;

    /** Resolved recording parameters. */
    static final class Config {
        Size size;
        int fps;
        int videoBitrate;
        boolean hevc;
        boolean audio;
        int orientationHint;

        String mime() {
            return "video/mp4";
        }

        @Override
        public String toString() {
            return size.getWidth() + "x" + size.getHeight() + "@" + fps + (hevc ? " HEVC " : " H.264 ")
                    + (videoBitrate / 1_000_000) + " Mbps";
        }
    }

    /** One selectable video resolution with the frame rates the HAL can sustain for it. */
    static final class Option {
        final String key;
        final String label;
        final Size size;
        final List<Integer> fpsChoices;

        Option(String key, String label, Size size, List<Integer> fpsChoices) {
            this.key = key;
            this.label = label;
            this.size = size;
            this.fpsChoices = fpsChoices;
        }
    }

    private static Boolean sHevc;

    private final Context mContext;
    private Surface mSurface;
    private Size mSurfaceSize;
    private MediaRecorder mRecorder;

    VideoRecorder(Context context) {
        mContext = context.getApplicationContext();
    }

    /** Returns the persistent input surface sized for {@code cfg}, creating it on first use. */
    Surface obtainSurface(Config cfg) throws IOException {
        if (mSurface != null && cfg.size.equals(mSurfaceSize)) {
            return mSurface;
        }
        releaseSurface();
        Surface surface = MediaCodec.createPersistentInputSurface();
        // A throw-away recorder fixes the surface's buffer size; the real one is created per clip.
        File tmp = new File(mContext.getCacheDir(), "opcam_probe.mp4");
        MediaRecorder probe = new MediaRecorder();
        try {
            Config probeCfg = new Config();
            probeCfg.size = cfg.size;
            probeCfg.fps = cfg.fps;
            probeCfg.videoBitrate = cfg.videoBitrate;
            probeCfg.hevc = cfg.hevc;
            probeCfg.audio = false;
            configure(probe, probeCfg, surface, null, tmp.getAbsolutePath());
            probe.prepare();
        } finally {
            probe.release();
            if (tmp.exists() && !tmp.delete()) {
                Log.w(TAG, "Could not delete probe file");
            }
        }
        mSurface = surface;
        mSurfaceSize = cfg.size;
        return mSurface;
    }

    boolean isRecording() {
        return mRecorder != null;
    }

    /** Prepares and starts a clip into the given destination (fd on Android 10+, path on 8.x). */
    void start(Config cfg, MediaSaver.PendingVideo dest) throws IOException {
        if (mSurface == null) {
            throw new IOException("Recorder surface not initialised");
        }
        MediaRecorder r = new MediaRecorder();
        try {
            configure(r, cfg, mSurface, dest.fileDescriptor(), dest.path());
            r.prepare();
            r.start();
        } catch (IOException | RuntimeException e) {
            r.release();
            throw e instanceof IOException ? (IOException) e : new IOException("MediaRecorder start failed", e);
        }
        mRecorder = r;
    }

    /** Stops the current clip. Returns false if nothing usable was written (e.g. too short). */
    boolean stop() {
        MediaRecorder r = mRecorder;
        mRecorder = null;
        if (r == null) {
            return false;
        }
        boolean ok = true;
        try {
            r.stop();
        } catch (RuntimeException e) {
            Log.w(TAG, "stop failed (clip too short?): " + e);
            ok = false;
        }
        try {
            r.reset();
        } catch (RuntimeException ignored) {
            // nothing to do
        }
        r.release();
        return ok;
    }

    void releaseSurface() {
        if (mSurface != null) {
            mSurface.release();
            mSurface = null;
            mSurfaceSize = null;
        }
    }

    void release() {
        if (mRecorder != null) {
            stop();
        }
        releaseSurface();
    }

    private static void configure(MediaRecorder r, Config cfg, Surface surface, java.io.FileDescriptor fd,
                                  String path) {
        if (cfg.audio) {
            r.setAudioSource(MediaRecorder.AudioSource.CAMCORDER);
        }
        r.setVideoSource(MediaRecorder.VideoSource.SURFACE);
        r.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
        if (fd != null) {
            r.setOutputFile(fd);
        } else {
            r.setOutputFile(path);
        }
        r.setVideoEncodingBitRate(cfg.videoBitrate);
        r.setVideoFrameRate(cfg.fps);
        r.setVideoSize(cfg.size.getWidth(), cfg.size.getHeight());
        r.setVideoEncoder(cfg.hevc ? MediaRecorder.VideoEncoder.HEVC : MediaRecorder.VideoEncoder.H264);
        if (cfg.audio) {
            r.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            r.setAudioEncodingBitRate(192_000);
            r.setAudioSamplingRate(48_000);
            r.setAudioChannels(2);
        }
        r.setOrientationHint(cfg.orientationHint);
        r.setInputSurface(surface);
    }

    // ------------------------------------------------------------------ capability helpers

    static synchronized boolean hasHevcEncoder() {
        if (sHevc == null) {
            boolean found = false;
            try {
                for (MediaCodecInfo info : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                    if (!info.isEncoder()) {
                        continue;
                    }
                    for (String type : info.getSupportedTypes()) {
                        if ("video/hevc".equalsIgnoreCase(type)) {
                            found = true;
                            break;
                        }
                    }
                    if (found) {
                        break;
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "codec query failed: " + e);
            }
            sHevc = found;
        }
        return sHevc;
    }

    /** Resolutions the lens can record, highest first (4K, 1080p, 720p). */
    static List<Option> options(LensInfo lens) {
        List<Option> out = new ArrayList<>();
        Size[] sizes = CameraInfo.recorderSizes(lens);
        addOption(out, lens, sizes, Settings.VIDEO_RES_2160P, "4K UHD (3840x2160)", 3840, 2160);
        addOption(out, lens, sizes, Settings.VIDEO_RES_1080P, "Full HD (1920x1080)", 1920, 1080);
        addOption(out, lens, sizes, Settings.VIDEO_RES_720P, "HD (1280x720)", 1280, 720);
        if (out.isEmpty() && sizes.length > 0) {
            Size s = CameraInfo.largest(sizes);
            out.add(new Option(Settings.VIDEO_RES_1080P, s.getWidth() + "x" + s.getHeight(), s, fpsChoices(lens, s)));
        }
        return out;
    }

    private static void addOption(List<Option> out, LensInfo lens, Size[] sizes, String key, String label,
                                  int w, int h) {
        for (Size s : sizes) {
            if (s.getWidth() == w && s.getHeight() == h) {
                out.add(new Option(key, label, s, fpsChoices(lens, s)));
                return;
            }
        }
    }

    private static List<Integer> fpsChoices(LensInfo lens, Size size) {
        List<Integer> fps = new ArrayList<>();
        fps.add(30);
        long minFrame;
        try {
            minFrame = lens.map.getOutputMinFrameDuration(MediaRecorder.class, size);
        } catch (IllegalArgumentException e) {
            minFrame = Long.MAX_VALUE;
        }
        if (minFrame > 0 && minFrame <= NS_60FPS && hasFpsRange(lens, 60)) {
            fps.add(60);
        }
        return fps;
    }

    private static boolean hasFpsRange(LensInfo lens, int fps) {
        if (lens.fpsRanges == null) {
            return false;
        }
        for (Range<Integer> r : lens.fpsRanges) {
            if (r.getUpper() == fps && r.getLower() <= fps) {
                return true;
            }
        }
        return false;
    }

    /** Builds the recording config from settings, falling back to the best supported option. */
    static Config resolve(LensInfo lens, Settings settings) {
        List<Option> options = options(lens);
        if (options.isEmpty()) {
            return null;
        }
        Option chosen = options.get(0);
        for (Option o : options) {
            if (o.key.equals(settings.videoRes())) {
                chosen = o;
                break;
            }
        }
        // Prefer 1080p when the stored preference is unavailable (e.g. after switching lenses).
        if (!chosen.key.equals(settings.videoRes())) {
            for (Option o : options) {
                if (Settings.VIDEO_RES_1080P.equals(o.key)) {
                    chosen = o;
                }
            }
        }
        Config cfg = new Config();
        cfg.size = chosen.size;
        cfg.fps = chosen.fpsChoices.contains(settings.videoFps()) ? settings.videoFps() : 30;
        cfg.hevc = settings.hevc() && hasHevcEncoder();
        cfg.videoBitrate = bitrateFor(lens, chosen, cfg.fps, cfg.hevc);
        return cfg;
    }

    private static int bitrateFor(LensInfo lens, Option option, int fps, boolean hevc) {
        int quality;
        if (Settings.VIDEO_RES_2160P.equals(option.key)) {
            quality = CamcorderProfile.QUALITY_2160P;
        } else if (Settings.VIDEO_RES_1080P.equals(option.key)) {
            quality = CamcorderProfile.QUALITY_1080P;
        } else {
            quality = CamcorderProfile.QUALITY_720P;
        }
        int bitrate = 0;
        try {
            int camId = Integer.parseInt(lens.id);
            if (CamcorderProfile.hasProfile(camId, quality)) {
                CamcorderProfile p = CamcorderProfile.get(camId, quality);
                if (p != null) {
                    bitrate = p.videoBitRate;
                }
            }
        } catch (RuntimeException ignored) {
            // non-numeric id or no profile
        }
        if (bitrate <= 0) {
            long pixels = (long) option.size.getWidth() * option.size.getHeight();
            // ~0.09 bits per pixel per frame for H.264 at 30 fps - matches typical OEM profiles.
            bitrate = (int) Math.min(Integer.MAX_VALUE, pixels * 30L * 9L / 100L);
        }
        if (fps > 30) {
            bitrate = (int) Math.min(Integer.MAX_VALUE, (long) bitrate * fps / 30L * 85L / 100L);
        }
        if (hevc) {
            bitrate = (int) ((long) bitrate * 65L / 100L); // HEVC: same quality at ~65% of H.264 bits
        }
        return Math.max(bitrate, 2_000_000);
    }

    static String describeVideoOptions(LensInfo lens) {
        StringBuilder sb = new StringBuilder();
        for (Option o : options(lens)) {
            if (sb.length() > 0) {
                sb.append(", ");
            }
            sb.append(o.key);
            for (int fps : o.fpsChoices) {
                sb.append(fps == 30 ? "" : String.format(Locale.US, "/%d", fps));
            }
        }
        return sb.length() == 0 ? "none" : sb.toString();
    }
}
