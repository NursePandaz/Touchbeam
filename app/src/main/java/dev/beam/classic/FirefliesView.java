/*
 * Copyright (C) 2011 The Android Open Source Project
 * Copyright 2026 Touchbeam contributors
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 * Canvas adaptation of AOSP FireflyRenderer.java (Android 4.2):
 * 200 additive star quads, depth speed .5/s, clipping planes 50 and 100.
 */
package dev.beam.classic;

import android.content.Context;
import android.graphics.*;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.View;

public final class FirefliesView extends View {
    private final float[][] particles = new float[200][3];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
    private final Bitmap star;
    private final RectF rect = new RectF();
    private long previous;
    private boolean running;
    private final boolean reduced;

    public FirefliesView(Context context) {
        super(context);
        setLayerType(View.LAYER_TYPE_HARDWARE, null);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        star = BitmapFactory.decodeResource(getResources(), R.drawable.beam_star);
        paint.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.ADD));
        reduced = Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0;
        for (float[] p : particles) reset(p);
    }
    private void reset(float[] p) {
        p[0] = (float)(Math.random() * 2880 - 1440);
        p[1] = (float)(Math.random() * 5120 - 2560);
        p[2] = (float)(Math.random() * 2 - 1);
    }
    public void start() { running = true; previous = 0; invalidate(); }
    public void stop() { running = false; invalidate(); }
    @Override protected void onDetachedFromWindow() { running = false; super.onDetachedFromWindow(); }
    @Override protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        if (!running || star == null) return;
        long now = SystemClock.uptimeMillis();
        float dt = previous == 0 ? 30 : Math.min(60, now - previous);
        previous = now;
        canvas.save();
        canvas.scale(getWidth() / 720f, getHeight() / 1280f);
        for (float[] p : particles) {
            if (!reduced) p[2] += dt * .0005f;
            if (p[2] > 1) reset(p);
            if (p[2] < 0) continue;
            float projection = 25f / (50f + p[2] * 50f);
            float size = 48 * projection;
            float x = 360 + p[0] * projection, y = 640 + p[1] * projection;
            rect.set(x - size / 2, y - size / 2, x + size / 2, y + size / 2);
            paint.setAlpha((int)(255 * (1 - p[2])));
            canvas.drawBitmap(star, null, rect, paint);
        }
        canvas.restore();
        if (!reduced && getWindowVisibility() == VISIBLE) postInvalidateOnAnimation();
    }
}
