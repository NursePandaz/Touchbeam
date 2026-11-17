package dev.beam.classic;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.view.*;
import android.view.animation.DecelerateInterpolator;
import android.widget.*;

/** Original Beam layout: black, a centered 60% app snapshot, hint at height/8. */
@android.annotation.SuppressLint("ViewConstructor") // Requires a captured bitmap; this view is never inflated from XML.
public final class BeamOverlay extends FrameLayout {
    private final FirefliesView particles;
    private final ImageView snapshot;
    private final TextView hint;
    private final TextView progress;
    private boolean sending;
    private boolean disposed;

    public BeamOverlay(Context context, Bitmap image, Runnable onTap) {
        super(context);
        setBackgroundColor(Color.BLACK);
        setClickable(true);
        setFocusable(true);
        setContentDescription("Touch to beam");
        particles = new FirefliesView(context);
        addView(particles, new LayoutParams(-1, -1));
        hint = new TextView(context);
        hint.setText(R.string.beam_touch_hint);
        hint.setTextColor(0xff33b5e5);
        hint.setTextSize(22);
        hint.setGravity(Gravity.CENTER);
        hint.setAlpha(0);
        addView(hint, new LayoutParams(-1, 0));
        snapshot = new ImageView(context);
        snapshot.setImageBitmap(image);
        snapshot.setScaleType(ImageView.ScaleType.FIT_XY);
        snapshot.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
        addView(snapshot, new LayoutParams(-1, -1));
        progress = new TextView(context);
        progress.setTextColor(0xffcccccc); progress.setTextSize(14); progress.setGravity(Gravity.CENTER);
        progress.setPadding(16, 12, 16, 24); progress.setVisibility(GONE);
        progress.setAccessibilityLiveRegion(ACCESSIBILITY_LIVE_REGION_POLITE);
        LayoutParams progressLayout = new LayoutParams(-1, -2, Gravity.BOTTOM);
        addView(progress, progressLayout);
        setOnClickListener(v -> {
            if (sending) return;
            sending = true;
            hint.animate().cancel();
            hint.setVisibility(GONE);
            setContentDescription("Beaming");
            progress.setText(R.string.beam_waiting_hint); progress.setVisibility(VISIBLE);
            snapshot.animate().cancel();
            snapshot.animate().scaleX(.2f).scaleY(.2f).setStartDelay(0).setDuration(8000).setInterpolator(new DecelerateInterpolator()).withEndAction(null).start();
            onTap.run();
        });
        post(() -> {
            if (!isAttachedToWindow() || disposed || sending) return;
            hint.getLayoutParams().height = getHeight() / 4;
            hint.requestLayout();
            snapshot.animate().scaleX(.6f).scaleY(.6f).setDuration(350)
                .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> { if (!disposed && !sending) particles.start(); }).start();
            hint.animate().alpha(1).setStartDelay(300).setDuration(500).start();
        });
    }
    public void progress(String value) { progress.setText(value); }
    public void finish(Runnable done) {
        if (disposed) return;
        particles.stop();
        hint.setVisibility(GONE);
        progress.setText(R.string.beam_verified_hint);
        setClickable(false);
        snapshot.animate().cancel();
        snapshot.animate().scaleX(0).scaleY(0).alpha(0).setStartDelay(0).setDuration(350)
            .setInterpolator(new DecelerateInterpolator()).withEndAction(() -> {
                if (disposed) return;
                snapshot.setScaleX(1); snapshot.setScaleY(1);
                snapshot.animate().alpha(1).setStartDelay(350).setDuration(250).withEndAction(done).start();
            }).start();
    }
    public void dispose() {
        disposed = true;
        particles.stop();
        hint.animate().cancel();
        snapshot.animate().cancel();
        snapshot.setImageDrawable(null);
    }
}
