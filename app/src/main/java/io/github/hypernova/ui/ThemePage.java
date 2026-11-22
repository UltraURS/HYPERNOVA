// SPDX-License-Identifier: GPL-3.0-only
package io.github.hypernova.ui;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.RectF;
import android.graphics.Shader;
import android.os.SystemClock;
import android.widget.LinearLayout;
import io.github.hypernova.R;

/** The icon's blue-to-cyan orbit and a silver glow over a cool, moving background. */
final class ThemePage extends LinearLayout {
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Matrix placement = new Matrix();
    private final RectF orbit = new RectF();
    private final RadialGradient blueGlow, cyanGlow, starlight;
    private final int skyTop, skyBottom, orbitBlue, orbitCyan;
    private final float orbitStroke;
    private LinearGradient sky, orbitColor;

    ThemePage(Context context) {
        super(context);
        setOrientation(VERTICAL);
        setWillNotDraw(false);
        skyTop = context.getColor(R.color.nova_sky_top);
        skyBottom = context.getColor(R.color.nova_sky_bottom);
        orbitBlue = context.getColor(R.color.nova_orbit_blue);
        orbitCyan = context.getColor(R.color.nova_orbit_cyan);
        blueGlow = glow(context.getColor(R.color.nova_glow_blue));
        cyanGlow = glow(context.getColor(R.color.nova_glow_cyan));
        starlight = glow(context.getColor(R.color.nova_starlight));
        orbitStroke = 1.25f * getResources().getDisplayMetrics().density;
    }

    private static RadialGradient glow(int color) {
        return new RadialGradient(0, 0, 1, color, color & 0x00ffffff, Shader.TileMode.CLAMP);
    }

    @Override protected void onSizeChanged(int width, int height, int oldWidth, int oldHeight) {
        super.onSizeChanged(width, height, oldWidth, oldHeight);
        sky = new LinearGradient(0, 0, width, height, skyTop, skyBottom, Shader.TileMode.CLAMP);
        float majorRadius = width * 0.76f;
        float minorRadius = Math.min(width, height) * 0.34f;
        orbit.set(-majorRadius, -minorRadius, majorRadius, minorRadius);
        orbitColor = new LinearGradient(-majorRadius, 0, majorRadius, 0, orbitBlue, orbitCyan, Shader.TileMode.CLAMP);
    }

    @Override protected void onDraw(Canvas canvas) {
        paint.setShader(sky);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
        boolean animate = ValueAnimator.areAnimatorsEnabled() && isShown() && getWindowVisibility() == VISIBLE;
        double phase = animate ? SystemClock.uptimeMillis() % 22_000 * (2 * Math.PI / 22_000) : 0;
        float driftX = (float) Math.cos(phase) * 0.10f;
        float driftY = (float) Math.sin(phase) * 0.09f;
        paintGlow(canvas, blueGlow, 0.10f + driftX, 0.75f + driftY);
        paintGlow(canvas, cyanGlow, 0.94f - driftX, 0.28f - driftY);
        paintGlow(canvas, starlight, 0.46f + driftX * 0.3f, 0.24f + driftY * 0.35f);

        canvas.save();
        canvas.translate(getWidth() * (0.57f + driftX * 0.2f), getHeight() * (0.48f + driftY * 0.2f));
        canvas.rotate(-26f);
        paint.setShader(orbitColor);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(orbitStroke);
        canvas.drawOval(orbit, paint);
        paint.setStyle(Paint.Style.FILL);
        canvas.restore();

        if (animate) postInvalidateDelayed(42);
    }

    private void paintGlow(Canvas canvas, RadialGradient color, float x, float y) {
        placement.setScale(getWidth() * 0.9f, getHeight() * 0.63f);
        placement.postTranslate(getWidth() * x, getHeight() * y);
        color.setLocalMatrix(placement);
        paint.setShader(color);
        canvas.drawRect(0, 0, getWidth(), getHeight(), paint);
    }
}
