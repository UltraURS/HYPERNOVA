// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.feature.xiaoai;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;

@SuppressLint("ViewConstructor") // This view is created with the bitmap selected by the user.
final class XiaoAiAvatarCropView extends View {
    private final Bitmap photo;
    private final XiaoAiAvatarCrop crop;
    private final RectF frame = new RectF();
    private final RectF imageBounds = new RectF();
    private final Paint photoPaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint shade = new Paint();
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final ScaleGestureDetector pinch;
    private final float margin;
    private float lastX, lastY;

    XiaoAiAvatarCropView(Context context, Bitmap photo) {
        super(context);
        this.photo = photo;
        crop = new XiaoAiAvatarCrop(photo.getWidth(), photo.getHeight());
        float density = getResources().getDisplayMetrics().density;
        margin = 20 * density;
        shade.setColor(0x99000000);
        guide.setStyle(Paint.Style.STROKE);
        guide.setStrokeWidth(1.5f * density);
        setContentDescription("头像裁剪预览");
        pinch = new ScaleGestureDetector(context, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            private float focusX, focusY;

            @Override public boolean onScaleBegin(ScaleGestureDetector detector) {
                focusX = detector.getFocusX();
                focusY = detector.getFocusY();
                return true;
            }

            @Override public boolean onScale(ScaleGestureDetector detector) {
                float x = detector.getFocusX();
                float y = detector.getFocusY();
                float span = frame.width();
                crop.pan((x - focusX) / span, (y - focusY) / span);
                crop.zoom(detector.getScaleFactor(), (x - frame.left) / span, (y - frame.top) / span);
                focusX = x;
                focusY = y;
                invalidate();
                return true;
            }
        });
        pinch.setQuickScaleEnabled(false);
        pinch.setStylusScaleEnabled(false);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        float side = Math.max(0, Math.min(width, height) - margin * 2);
        float x = (width - side) / 2f;
        float y = (height - side) / 2f;
        frame.set(x, y, x + side, y + side);
    }

    @Override protected void onDraw(Canvas canvas) {
        if (frame.isEmpty()) return;
        drawPhoto(canvas, frame.left, frame.top, frame.width());
        canvas.drawRect(0, 0, getWidth(), frame.top, shade);
        canvas.drawRect(0, frame.bottom, getWidth(), getHeight(), shade);
        canvas.drawRect(0, frame.top, frame.left, frame.bottom, shade);
        canvas.drawRect(frame.right, frame.top, getWidth(), frame.bottom, shade);
        guide.setColor(0xfff5f5f5);
        canvas.drawRect(frame, guide);
        guide.setColor(0xcaffffff);
        canvas.drawCircle(frame.centerX(), frame.centerY(), frame.width() / 2f, guide);
    }

    @SuppressLint("ClickableViewAccessibility") // The preview handles drags and pinches, and the page offers zoom buttons.
    @Override public boolean onTouchEvent(MotionEvent event) {
        if (!isEnabled() || frame.isEmpty()) return false;
        pinch.onTouchEvent(event);
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                lastX = event.getX();
                lastY = event.getY();
            }
            case MotionEvent.ACTION_MOVE -> {
                if (event.getPointerCount() == 1) {
                    float x = event.getX();
                    float y = event.getY();
                    crop.pan((x - lastX) / frame.width(), (y - lastY) / frame.height());
                    lastX = x;
                    lastY = y;
                    invalidate();
                }
            }
            case MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerCount() == 2) {
                    int remaining = 1 - event.getActionIndex();
                    lastX = event.getX(remaining);
                    lastY = event.getY(remaining);
                }
            }
        }
        return true;
    }

    void zoomBy(float factor) {
        if (!isEnabled()) return;
        crop.zoom(factor, 0.5f, 0.5f);
        invalidate();
    }

    Bitmap croppedAvatar() {
        int size = XiaoAiAvatar.OUTPUT_SIZE;
        Bitmap result = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        drawPhoto(new Canvas(result), 0f, 0f, size);
        return result;
    }

    private void drawPhoto(Canvas canvas, float left, float top, float side) {
        float scale = side / crop.size();
        float x = left - crop.left() * scale;
        float y = top - crop.top() * scale;
        imageBounds.set(x, y, x + photo.getWidth() * scale, y + photo.getHeight() * scale);
        canvas.drawBitmap(photo, null, imageBounds, photoPaint);
    }
}
