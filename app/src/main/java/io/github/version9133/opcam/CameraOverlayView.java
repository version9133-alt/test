package io.github.version9133.opcam;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;

/**
 * Transparent overlay drawn above the preview: rule-of-thirds grid and the tap-to-focus ring.
 * Allocation-free in onDraw (paints are created once) so it never contributes to jank.
 */
public class CameraOverlayView extends View {

    private static final long FOCUS_RING_VISIBLE_MS = 1800L;

    private final Paint mGridPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mGridShadowPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private boolean mGridEnabled;
    private boolean mRingVisible;
    private float mRingX;
    private float mRingY;
    private float mRingScale = 1f;
    private int mRingColor = Color.WHITE;
    private ValueAnimator mRingAnimator;
    private final Runnable mHideRing = new Runnable() {
        @Override
        public void run() {
            mRingVisible = false;
            invalidate();
        }
    };

    public CameraOverlayView(Context context) {
        this(context, null);
    }

    public CameraOverlayView(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        mGridPaint.setColor(0x99FFFFFF);
        mGridPaint.setStrokeWidth(density);
        mGridPaint.setStyle(Paint.Style.STROKE);
        mGridShadowPaint.setColor(0x44000000);
        mGridShadowPaint.setStrokeWidth(density * 3f);
        mGridShadowPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(density * 2f);
        mRingPaint.setColor(mRingColor);
    }

    public void setGridEnabled(boolean enabled) {
        if (mGridEnabled != enabled) {
            mGridEnabled = enabled;
            invalidate();
        }
    }

    public boolean isGridEnabled() {
        return mGridEnabled;
    }

    /** Shows the focus ring at view coordinates and starts the shrink animation. */
    public void showFocusRing(float x, float y) {
        mRingX = x;
        mRingY = y;
        mRingColor = Color.WHITE;
        mRingVisible = true;
        removeCallbacks(mHideRing);
        if (mRingAnimator != null) {
            mRingAnimator.cancel();
        }
        mRingAnimator = ValueAnimator.ofFloat(1.6f, 1f);
        mRingAnimator.setDuration(220L);
        mRingAnimator.setInterpolator(new DecelerateInterpolator());
        mRingAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mRingScale = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        mRingAnimator.start();
        postDelayed(mHideRing, FOCUS_RING_VISIBLE_MS * 2);
        invalidate();
    }

    /** Colours the ring according to the AF result and schedules it to disappear. */
    public void setFocusResult(boolean focused) {
        if (!mRingVisible) {
            return;
        }
        mRingColor = focused ? getResources().getColor(R.color.focus_ok, null)
                : getResources().getColor(R.color.focus_fail, null);
        removeCallbacks(mHideRing);
        postDelayed(mHideRing, FOCUS_RING_VISIBLE_MS);
        invalidate();
    }

    public void hideFocusRing() {
        removeCallbacks(mHideRing);
        mRingVisible = false;
        invalidate();
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        int w = getWidth();
        int h = getHeight();
        if (mGridEnabled && w > 0 && h > 0) {
            float x1 = w / 3f;
            float x2 = 2f * w / 3f;
            float y1 = h / 3f;
            float y2 = 2f * h / 3f;
            canvas.drawLine(x1, 0, x1, h, mGridShadowPaint);
            canvas.drawLine(x2, 0, x2, h, mGridShadowPaint);
            canvas.drawLine(0, y1, w, y1, mGridShadowPaint);
            canvas.drawLine(0, y2, w, y2, mGridShadowPaint);
            canvas.drawLine(x1, 0, x1, h, mGridPaint);
            canvas.drawLine(x2, 0, x2, h, mGridPaint);
            canvas.drawLine(0, y1, w, y1, mGridPaint);
            canvas.drawLine(0, y2, w, y2, mGridPaint);
        }
        if (mRingVisible) {
            float radius = getResources().getDisplayMetrics().density * 36f * mRingScale;
            mRingPaint.setColor(mRingColor);
            canvas.drawCircle(mRingX, mRingY, radius, mRingPaint);
            canvas.drawCircle(mRingX, mRingY, radius * 0.08f, mRingPaint);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(mHideRing);
        if (mRingAnimator != null) {
            mRingAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }
}
