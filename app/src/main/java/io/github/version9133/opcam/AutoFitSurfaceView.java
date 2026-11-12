package io.github.version9133.opcam;

import android.content.Context;
import android.util.AttributeSet;
import android.view.SurfaceView;

/**
 * A SurfaceView that sizes itself to a fixed aspect ratio using a centre-inside fit, so the full
 * sensor frame is visible (what you see is exactly what gets captured).
 *
 * <p>SurfaceView is used instead of TextureView on purpose: the camera HAL renders straight into
 * a buffer that SurfaceFlinger composites with a hardware overlay. No per-frame GPU copy means
 * lower power draw and less heat during long sessions - important on 90/120 Hz OnePlus panels.</p>
 */
public class AutoFitSurfaceView extends SurfaceView {

    /** width / height of the camera buffer (sensor orientation, usually landscape). */
    private float mAspectRatio = 0f;

    public AutoFitSurfaceView(Context context) {
        super(context);
    }

    public AutoFitSurfaceView(Context context, AttributeSet attrs) {
        super(context, attrs);
    }

    public AutoFitSurfaceView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);
    }

    /** Sets the buffer aspect ratio (e.g. 1440x1080). Pass 0,0 to fill the parent. */
    public void setAspectRatio(int width, int height) {
        float ratio = (width <= 0 || height <= 0) ? 0f : (float) width / height;
        if (ratio != mAspectRatio) {
            mAspectRatio = ratio;
            requestLayout();
        }
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
        int width = MeasureSpec.getSize(widthMeasureSpec);
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (mAspectRatio == 0f || width == 0 || height == 0) {
            setMeasuredDimension(width, height);
            return;
        }
        // In portrait the buffer is rotated by the compositor, so the on-screen ratio is inverted.
        float actualRatio = width > height ? mAspectRatio : 1f / mAspectRatio;
        int newWidth;
        int newHeight;
        if ((float) width / height > actualRatio) {
            newHeight = height;
            newWidth = Math.round(height * actualRatio);
        } else {
            newWidth = width;
            newHeight = Math.round(width / actualRatio);
        }
        setMeasuredDimension(newWidth, newHeight);
    }
}
