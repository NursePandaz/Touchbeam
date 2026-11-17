package dev.beam.classic;

import android.app.*;
import android.content.*;
import android.content.pm.PackageInfo;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.charset.StandardCharsets;

/** A quiet Holo-blue home for the project's story, links and AOSP credits. */
final class AboutDialog extends Dialog {
    private FirefliesView particles;
    private AlertDialog licenses;
    AboutDialog(Activity activity) { super(activity, android.R.style.Theme_Holo_NoActionBar); }
    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        FrameLayout root = new FrameLayout(getContext()); root.setBackgroundColor(Color.BLACK);
        particles = new FirefliesView(getContext()); particles.setAlpha(.48f);
        root.addView(particles, new FrameLayout.LayoutParams(-1, -1));
        ScrollView scroll = new ScrollView(getContext()); scroll.setFillViewport(true); scroll.setFitsSystemWindows(true);
        LinearLayout body = new LinearLayout(getContext()); body.setOrientation(LinearLayout.VERTICAL);
        body.setGravity(Gravity.CENTER_HORIZONTAL); body.setPadding(dp(28), dp(40), dp(28), dp(28));
        // A subtle scrim keeps the text legible over the original additive stars.
        body.setBackground(new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, new int[]{0x99000000, 0xcc000000}));
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2)); root.addView(scroll, new FrameLayout.LayoutParams(-1, -1));
        ImageView icon = new ImageView(getContext()); icon.setImageResource(R.drawable.app_icon); icon.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        LinearLayout.LayoutParams logo = new LinearLayout.LayoutParams(dp(76), dp(76)); logo.bottomMargin = dp(20); body.addView(icon, logo);
        label(body, getContext().getString(R.string.about_title), 34, Color.WHITE, 6);
        label(body, getContext().getString(R.string.about_tagline), 18, 0xff33b5e5, 22);
        TextView badge = label(body, getContext().getString(R.string.about_experimental), 12, 0xff33b5e5, 12);
        badge.setPadding(dp(12), dp(6), dp(12), dp(6));
        GradientDrawable border = new GradientDrawable(); border.setColor(0x99212b30); border.setCornerRadius(dp(3)); border.setStroke(dp(1), 0xff297b9c); badge.setBackground(border);
        String version = getContext().getString(R.string.about_version_unknown);
        try { PackageInfo info = getContext().getPackageManager().getPackageInfo(getContext().getPackageName(), 0); version = info.versionName; }
        catch (android.content.pm.PackageManager.NameNotFoundException ignored) {}
        label(body, getContext().getString(R.string.about_version, version), 13, 0xffaaaaaa, 28);
        label(body, getContext().getString(R.string.about_story), 17, 0xffeeeeee, 18);
        label(body, getContext().getString(R.string.about_creator), 16, Color.WHITE, 28);
        link(body, R.string.about_github, "https://github.com/NursePandaz/Touchbeam");
        link(body, R.string.about_report_issue, "https://github.com/NursePandaz/Touchbeam/issues/new");
        link(body, R.string.about_how_to, "https://github.com/NursePandaz/Touchbeam/blob/main/docs/TESTING.md");
        Button credits = button(body, R.string.about_credits); credits.setOnClickListener(v -> showLicenses());
        label(body, getContext().getString(R.string.about_attribution), 12, 0xffaaaaaa, 20);
        Button close = button(body, R.string.about_close); close.setOnClickListener(v -> dismiss());
        setContentView(root);
        Window window = getWindow();
        if (window != null) { window.setStatusBarColor(Color.BLACK); window.setNavigationBarColor(Color.BLACK); }
    }
    private int dp(int value) { return Math.round(value * getContext().getResources().getDisplayMetrics().density); }
    private TextView label(LinearLayout body, String value, int size, int color, int bottom) {
        TextView view = new TextView(getContext()); view.setText(value); view.setTextSize(size); view.setTextColor(color); view.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-2, -2); layout.bottomMargin = dp(bottom); body.addView(view, layout); return view;
    }
    private Button button(LinearLayout body, int title) {
        Button button = new Button(getContext()); button.setText(title); button.setAllCaps(false); button.setMinHeight(dp(48));
        LinearLayout.LayoutParams layout = new LinearLayout.LayoutParams(-1, -2); layout.bottomMargin = dp(8); body.addView(button, layout); return button;
    }
    private void link(LinearLayout body, int title, String url) {
        Button link = button(body, title); link.setOnClickListener(v -> {
            try { getContext().startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addCategory(Intent.CATEGORY_BROWSABLE)); }
            catch (ActivityNotFoundException | SecurityException ex) { Toast.makeText(getContext(), R.string.about_no_browser, Toast.LENGTH_LONG).show(); }
        });
    }
    private void showLicenses() {
        StringBuilder notice = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(getContext().getAssets().open("NOTICE.txt"), StandardCharsets.UTF_8))) {
            String line; while ((line = reader.readLine()) != null) notice.append(line).append('\n');
        } catch (IOException ex) { notice.append(getContext().getString(R.string.about_attribution)); }
        TextView text = new TextView(getContext()); text.setText(notice); text.setTextColor(Color.WHITE); text.setTextSize(14); text.setTextIsSelectable(true); text.setPadding(dp(20), dp(16), dp(20), dp(16));
        ScrollView scroll = new ScrollView(getContext()); scroll.addView(text);
        licenses = new AlertDialog.Builder(getContext()).setTitle(R.string.about_credits).setView(scroll).setPositiveButton(R.string.about_close, null).show();
    }
    void resumeParticles() { if (particles != null && isShowing()) particles.start(); }
    void pauseParticles() { if (particles != null) particles.stop(); }
    @Override protected void onStart() { super.onStart(); Window window = getWindow(); if (window != null) window.setLayout(-1, -1); resumeParticles(); }
    @Override public void onWindowFocusChanged(boolean focus) { super.onWindowFocusChanged(focus); if (focus) resumeParticles(); else pauseParticles(); }
    @Override protected void onStop() { pauseParticles(); if (licenses != null) { licenses.dismiss(); licenses = null; } super.onStop(); }
}
