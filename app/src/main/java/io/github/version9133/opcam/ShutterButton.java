package io.github.version9133.opcam;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

/**
 * Shutter / record button drawn with Canvas - a single view instead of a stack of drawables.
 * Modes: photo (white disc), video idle (red disc), recording (red rounded square).
 */
public class ShutterButton extends View {

    public static final int STATE_PHOTO = 0;
    public static final int STATE_VIDEO = 1;
    public static final int STATE_RECORDING = 2;

    private final Paint mRingPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint mFillPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF mRect = new RectF();

    private int mState = STATE_PHOTO;
    private float mPressScale = 1f;
    /** 0 = full circle, 1 = rounded square (animated when recording starts/stops). */
    private float mSquareness = 0f;
    private ValueAnimator mAnimator;
    private final int mRecColor;

    public ShutterButton(Context context) {
        this(context, null);
    }

    public ShutterButton(Context context, AttributeSet attrs) {
        super(context, attrs);
        float density = getResources().getDisplayMetrics().density;
        mRingPaint.setStyle(Paint.Style.STROKE);
        mRingPaint.setStrokeWidth(density * 4f);
        mRingPaint.setColor(Color.WHITE);
        mFillPaint.setStyle(Paint.Style.FILL);
        mFillPaint.setColor(Color.WHITE);
        mRecColor = getResources().getColor(R.color.rec_red, null);
        setClickable(true);
        setFocusable(true);
    }

    public void setState(int state) {
        if (mState == state) {
            return;
        }
        mState = state;
        float target = state == STATE_RECORDING ? 1f : 0f;
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        mAnimator = ValueAnimator.ofFloat(mSquareness, target);
        mAnimator.setDuration(180L);
        mAnimator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            @Override
            public void onAnimationUpdate(ValueAnimator animation) {
                mSquareness = (Float) animation.getAnimatedValue();
                invalidate();
            }
        });
        mAnimator.start();
        invalidate();
    }

    public int getState() {
        return mState;
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                mPressScale = 0.9f;
                invalidate();
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                mPressScale = 1f;
                invalidate();
                break;
            default:
                break;
        }
        return super.onTouchEvent(event);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        float cx = getWidth() / 2f;
        float cy = getHeight() / 2f;
        float outer = Math.min(cx, cy) - mRingPaint.getStrokeWidth() / 2f;
        canvas.drawCircle(cx, cy, outer, mRingPaint);

        float innerCircle = (outer - mRingPaint.getStrokeWidth() * 1.6f) * mPressScale;
        float squareHalf = outer * 0.42f;
        float half = innerCircle + (squareHalf - innerCircle) * mSquareness;
        float corner = half * (1f - mSquareness) + half * 0.3f * mSquareness;
        mFillPaint.setColor(mState == STATE_PHOTO ? Color.WHITE : mRecColor);
        mRect.set(cx - half, cy - half, cx + half, cy + half);
        canvas.drawRoundRect(mRect, corner, corner, mFillPaint);
    }

    @Override
    protected void onDetachedFromWindow() {
        if (mAnimator != null) {
            mAnimator.cancel();
        }
        super.onDetachedFromWindow();
    }
}
