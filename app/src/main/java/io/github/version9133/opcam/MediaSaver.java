package io.github.version9133.opcam;

import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.hardware.camera2.DngCreator;
import android.media.ExifInterface;
import android.media.Image;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.provider.MediaStore;
import android.util.Log;
import android.util.Size;

import java.io.File;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Writes photos / videos into DCIM/Camera through MediaStore (scoped storage on Android 10+,
 * direct files + media scanner on 8.x). All I/O runs on one low-priority background thread so the
 * camera thread is never blocked by flash storage.
 */
public final class MediaSaver {

    private static final String TAG = "MediaSaver";
    private static final String RELATIVE_DIR = Environment.DIRECTORY_DCIM + "/Camera";

    public interface Callback {
        void onSaved(Uri uri, String mime);

        void onError(String message);
    }

    public interface BitmapCallback {
        void onBitmap(Bitmap bitmap);
    }

    /** Handle for a video that is being recorded (MediaStore row is "pending" until finished). */
    public static final class PendingVideo {
        Uri uri;
        File file;
        ParcelFileDescriptor pfd;
        String mime;

        public FileDescriptor fileDescriptor() {
            return pfd != null ? pfd.getFileDescriptor() : null;
        }

        public String path() {
            return file != null ? file.getAbsolutePath() : null;
        }
    }

    private final Context mContext;
    private final ContentResolver mResolver;
    private final Handler mMain = new Handler(Looper.getMainLooper());
    private final ExecutorService mIo = Executors.newSingleThreadExecutor(new ThreadFactory() {
        @Override
        public Thread newThread(Runnable r) {
            Thread t = new Thread(r, "OPCam-IO");
            t.setPriority(Thread.NORM_PRIORITY - 1);
            return t;
        }
    });
    private final SimpleDateFormat mNameFormat = new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US);

    public MediaSaver(Context context) {
        mContext = context.getApplicationContext();
        mResolver = mContext.getContentResolver();
    }

    /**
     * Persists an encoded still (JPEG or HEIC).
     *
     * @param expectedRotation EXIF rotation (0/90/180/270) that the file should carry, or -1 to trust
     *                         the HAL. Used for extension captures where some vendors omit the tag.
     */
    public void saveImage(final byte[] data, final String mime, final String extension,
                          final int expectedRotation, final Callback cb) {
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                String name = "IMG_" + timestamp() + extension;
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        Uri uri = insertPending(name, mime, false);
                        try (OutputStream out = mResolver.openOutputStream(uri)) {
                            if (out == null) {
                                throw new IOException("openOutputStream returned null");
                            }
                            out.write(data);
                        }
                        if (expectedRotation >= 0 && "image/jpeg".equals(mime)) {
                            fixExifRotation(uri, null, data, expectedRotation);
                        }
                        publish(uri);
                        notifySaved(cb, uri, mime);
                    } else {
                        File file = legacyFile(name);
                        try (FileOutputStream out = new FileOutputStream(file)) {
                            out.write(data);
                        }
                        if (expectedRotation >= 0 && "image/jpeg".equals(mime)) {
                            fixExifRotation(null, file, data, expectedRotation);
                        }
                        scan(file, mime, cb);
                    }
                } catch (IOException | RuntimeException e) {
                    Log.e(TAG, "saveImage failed", e);
                    notifyError(cb, e.getMessage());
                }
            }
        });
    }

    /** Writes a DNG and closes both the creator and the image when done. */
    public void saveDng(final DngCreator creator, final Image image, final Callback cb) {
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                String name = "IMG_" + timestamp() + ".dng";
                String mime = "image/x-adobe-dng";
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        Uri uri = insertPending(name, mime, false);
                        try (OutputStream out = mResolver.openOutputStream(uri)) {
                            if (out == null) {
                                throw new IOException("openOutputStream returned null");
                            }
                            creator.writeImage(out, image);
                        }
                        publish(uri);
                        notifySaved(cb, uri, mime);
                    } else {
                        File file = legacyFile(name);
                        try (FileOutputStream out = new FileOutputStream(file)) {
                            creator.writeImage(out, image);
                        }
                        scan(file, mime, cb);
                    }
                } catch (IOException | RuntimeException e) {
                    Log.e(TAG, "saveDng failed", e);
                    notifyError(cb, e.getMessage());
                } finally {
                    try {
                        image.close();
                    } catch (RuntimeException ignored) {
                        // already closed
                    }
                    creator.close();
                }
            }
        });
    }

    /** Creates the destination for a new recording. Called on the camera thread (fast metadata op). */
    public PendingVideo createVideo(String mime) throws IOException {
        PendingVideo v = new PendingVideo();
        v.mime = mime;
        String name = "VID_" + timestamp() + ".mp4";
        if (Build.VERSION.SDK_INT >= 29) {
            v.uri = insertPending(name, mime, true);
            v.pfd = mResolver.openFileDescriptor(v.uri, "rw");
            if (v.pfd == null) {
                throw new IOException("Cannot open video output");
            }
        } else {
            v.file = legacyFile(name);
        }
        return v;
    }

    /** Finalises (or deletes) a recording; cb receives the public Uri. */
    public void finishVideo(final PendingVideo v, final boolean keep, final Callback cb) {
        if (v == null) {
            return;
        }
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    if (v.pfd != null) {
                        try {
                            v.pfd.close();
                        } catch (IOException ignored) {
                            // nothing to do
                        }
                    }
                    if (Build.VERSION.SDK_INT >= 29 && v.uri != null) {
                        if (keep) {
                            publish(v.uri);
                            notifySaved(cb, v.uri, v.mime);
                        } else {
                            mResolver.delete(v.uri, null, null);
                        }
                    } else if (v.file != null) {
                        if (keep) {
                            scan(v.file, v.mime, cb);
                        } else if (!v.file.delete()) {
                            Log.w(TAG, "Could not delete " + v.file);
                        }
                    }
                } catch (RuntimeException e) {
                    Log.e(TAG, "finishVideo failed", e);
                    notifyError(cb, e.getMessage());
                }
            }
        });
    }

    /** Loads a small thumbnail for the gallery button. */
    public void loadThumbnail(final Uri uri, final int sizePx, final BitmapCallback cb) {
        mIo.execute(new Runnable() {
            @Override
            public void run() {
                Bitmap bmp = null;
                try {
                    if (Build.VERSION.SDK_INT >= 29) {
                        bmp = mResolver.loadThumbnail(uri, new Size(sizePx, sizePx), null);
                    } else {
                        bmp = legacyThumbnail(uri, sizePx);
                    }
                } catch (IOException | RuntimeException e) {
                    Log.w(TAG, "thumbnail failed: " + e);
                }
                final Bitmap result = bmp;
                mMain.post(new Runnable() {
                    @Override
                    public void run() {
                        cb.onBitmap(result);
                    }
                });
            }
        });
    }

    /** Most recent photo/video in DCIM/Camera, or null. Runs synchronously - call off the UI thread. */
    public Uri queryLatestMedia() {
        Uri best = null;
        long bestDate = -1;
        Uri[] tables = {MediaStore.Images.Media.EXTERNAL_CONTENT_URI, MediaStore.Video.Media.EXTERNAL_CONTENT_URI};
        for (Uri table : tables) {
            String[] projection = {MediaStore.MediaColumns._ID, MediaStore.MediaColumns.DATE_ADDED};
            try (android.database.Cursor c = mResolver.query(table, projection, null, null,
                    MediaStore.MediaColumns.DATE_ADDED + " DESC")) {
                if (c != null && c.moveToFirst()) {
                    long date = c.getLong(1);
                    if (date > bestDate) {
                        bestDate = date;
                        best = ContentUris.withAppendedId(table, c.getLong(0));
                    }
                }
            } catch (RuntimeException e) {
                Log.w(TAG, "queryLatestMedia: " + e);
            }
        }
        return best;
    }

    public void shutdown() {
        mIo.shutdown();
    }

    // ---------------------------------------------------------------------------------------------

    private synchronized String timestamp() {
        return mNameFormat.format(new Date());
    }

    private Uri insertPending(String name, String mime, boolean video) throws IOException {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, mime);
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, RELATIVE_DIR);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri table = video ? MediaStore.Video.Media.EXTERNAL_CONTENT_URI : MediaStore.Images.Media.EXTERNAL_CONTENT_URI;
        Uri uri = mResolver.insert(table, values);
        if (uri == null) {
            throw new IOException("MediaStore insert failed for " + name);
        }
        return uri;
    }

    private void publish(Uri uri) {
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.IS_PENDING, 0);
        mResolver.update(uri, values, null, null);
    }

    private File legacyFile(String name) throws IOException {
        File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "Camera");
        if (!dir.isDirectory() && !dir.mkdirs()) {
            throw new IOException("Cannot create " + dir);
        }
        return new File(dir, name);
    }

    private void scan(File file, final String mime, final Callback cb) {
        MediaScannerConnection.scanFile(mContext, new String[]{file.getAbsolutePath()}, new String[]{mime},
                new MediaScannerConnection.OnScanCompletedListener() {
                    @Override
                    public void onScanCompleted(String path, Uri uri) {
                        notifySaved(cb, uri != null ? uri : Uri.fromFile(new File(path)), mime);
                    }
                });
    }

    private Bitmap legacyThumbnail(Uri uri, int sizePx) throws IOException {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        try (java.io.InputStream in = mResolver.openInputStream(uri)) {
            BitmapFactory.decodeStream(in, null, opts);
        }
        int sample = 1;
        while (opts.outWidth / (sample * 2) >= sizePx && opts.outHeight / (sample * 2) >= sizePx) {
            sample *= 2;
        }
        BitmapFactory.Options decode = new BitmapFactory.Options();
        decode.inSampleSize = sample;
        try (java.io.InputStream in = mResolver.openInputStream(uri)) {
            return BitmapFactory.decodeStream(in, null, decode);
        }
    }

    /**
     * Some vendor extension pipelines return the JPEG without an orientation tag. If the image is
     * still in sensor orientation (landscape) while a 90/270 rotation was requested, add the tag so
     * galleries display it upright. Never touches files that already carry orientation metadata.
     */
    private void fixExifRotation(Uri uri, File file, byte[] data, int expectedRotation) {
        if (expectedRotation != 90 && expectedRotation != 270) {
            return;
        }
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, opts);
        if (opts.outWidth <= opts.outHeight) {
            return; // already portrait: the pipeline rotated the pixels
        }
        ParcelFileDescriptor pfd = null;
        try {
            ExifInterface exif;
            if (uri != null) {
                pfd = mResolver.openFileDescriptor(uri, "rw");
                if (pfd == null) {
                    return;
                }
                exif = new ExifInterface(pfd.getFileDescriptor());
            } else {
                exif = new ExifInterface(file.getAbsolutePath());
            }
            int current = exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_UNDEFINED);
            if (current != ExifInterface.ORIENTATION_UNDEFINED && current != ExifInterface.ORIENTATION_NORMAL) {
                return;
            }
            int tag = expectedRotation == 90 ? ExifInterface.ORIENTATION_ROTATE_90 : ExifInterface.ORIENTATION_ROTATE_270;
            exif.setAttribute(ExifInterface.TAG_ORIENTATION, String.valueOf(tag));
            exif.saveAttributes();
        } catch (IOException | RuntimeException e) {
            Log.w(TAG, "EXIF fix skipped: " + e);
        } finally {
            if (pfd != null) {
                try {
                    pfd.close();
                } catch (IOException ignored) {
                    // nothing to do
                }
            }
        }
    }

    private void notifySaved(final Callback cb, final Uri uri, final String mime) {
        if (cb == null) {
            return;
        }
        mMain.post(new Runnable() {
            @Override
            public void run() {
                cb.onSaved(uri, mime);
            }
        });
    }

    private void notifyError(final Callback cb, final String message) {
        if (cb == null) {
            return;
        }
        mMain.post(new Runnable() {
            @Override
            public void run() {
                cb.onError(message != null ? message : "I/O error");
            }
        });
    }
}
