package com.abughadi.bubbleclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/**
 * Accessibility service that shows a floating bubble over any app,
 * records the user's taps, and replays them with dispatchGesture().
 */
public class ClickerService extends AccessibilityService {

    static ClickerService instance;

    /** One recorded tap: screen position + wait before it (ms). */
    static class Tap {
        final float x, y; final long delay;
        Tap(float x, float y, long delay) { this.x = x; this.y = y; this.delay = delay; }
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private LinearLayout menu;
    private View recordLayer;
    private TextView recordBar;

    private final List<Tap> taps = new ArrayList<>();
    private boolean recording, playing;
    private long lastTapTime;
    private int index, round;

    // ---------------- lifecycle ----------------

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        loadTaps();
        showBubble();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { stopPlaying(); }

    @Override
    public void onDestroy() {
        stopPlaying();
        removeSafe(menu); removeSafe(recordLayer); removeSafe(recordBar); removeSafe(bubble);
        instance = null;
        super.onDestroy();
    }

    // ---------------- overlay helpers ----------------

    private WindowManager.LayoutParams lp(int w, int h, boolean focusable) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(
            w, h,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            (focusable ? 0 : WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE)
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        p.gravity = Gravity.TOP | Gravity.START;
        return p;
    }

    private void removeSafe(View v) {
        if (v != null && v.isAttachedToWindow()) {
            try { wm.removeView(v); } catch (Exception ignored) { }
        }
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private GradientDrawable round(int color, int radius) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(radius);
        return g;
    }

    // ---------------- bubble ----------------

    @SuppressLint("ClickableViewAccessibility")
    private void showBubble() {
        bubble = new TextView(this);
        bubble.setText("●");
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(22);
        bubble.setGravity(Gravity.CENTER);
        int size = dp(56);
        GradientDrawable g = round(0xDD1E88E5, size / 2);
        g.setStroke(dp(2), Color.WHITE);
        bubble.setBackground(g);

        bubbleLp = lp(size, size, false);
        bubbleLp.x = dp(10);
        bubbleLp.y = dp(200);

        bubble.setOnTouchListener(new View.OnTouchListener() {
            float dx, dy; int sx, sy; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        sx = bubbleLp.x; sy = bubbleLp.y;
                        dx = e.getRawX(); dy = e.getRawY(); moved = false;
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        int nx = sx + (int) (e.getRawX() - dx);
                        int ny = sy + (int) (e.getRawY() - dy);
                        if (Math.abs(nx - sx) > dp(6) || Math.abs(ny - sy) > dp(6)) moved = true;
                        bubbleLp.x = nx; bubbleLp.y = ny;
                        wm.updateViewLayout(bubble, bubbleLp);
                        return true;
                    case MotionEvent.ACTION_UP:
                        if (!moved) onBubbleTap();
                        return true;
                }
                return false;
            }
        });
        wm.addView(bubble, bubbleLp);
        updateBubble();
    }

    private void updateBubble() {
        if (bubble == null) return;
        int color = playing ? 0xDD43A047 : recording ? 0xDDE53935 : 0xDD1E88E5;
        GradientDrawable g = round(color, dp(28));
        g.setStroke(dp(2), Color.WHITE);
        bubble.setBackground(g);
        bubble.setText(playing ? "▶" : recording ? "⏺" : "●");
    }

    private void onBubbleTap() {
        if (playing) { stopPlaying(); return; }      // tap while playing = stop
        if (menu != null && menu.isAttachedToWindow()) { removeSafe(menu); return; }
        showMenu();
    }

    // ---------------- menu ----------------

    private void showMenu() {
        menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(8), dp(8), dp(8), dp(8));
        menu.setBackground(round(0xEE222222, dp(14)));

        TextView info = new TextView(this);
        info.setTextColor(Color.LTGRAY);
        info.setText("النقرات المسجلة: " + taps.size());
        info.setPadding(dp(6), 0, dp(6), dp(6));
        menu.addView(info);

        menu.addView(menuBtn("⏺  تسجيل جديد", v -> { removeSafe(menu); startRecording(); }));
        menu.addView(menuBtn("▶  تشغيل", v -> { removeSafe(menu); startPlaying(); }));
        menu.addView(menuBtn("■  إيقاف", v -> { removeSafe(menu); stopPlaying(); }));
        menu.addView(menuBtn("🗑  مسح النقرات", v -> {
            taps.clear(); saveTaps(); removeSafe(menu); toast("تم المسح");
        }));
        menu.addView(menuBtn("✕  إخفاء القائمة", v -> removeSafe(menu)));

        WindowManager.LayoutParams p = lp(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, false);
        p.x = bubbleLp.x;
        p.y = bubbleLp.y + dp(64);
        wm.addView(menu, p);
    }

    private Button menuBtn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(round(0xFF3A3A3A, dp(10)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(190), dp(44));
        p.topMargin = dp(4);
        b.setLayoutParams(p);
        b.setOnClickListener(l);
        return b;
    }

    // ---------------- recording ----------------

    @SuppressLint("ClickableViewAccessibility")
    private void startRecording() {
        taps.clear();
        recording = true;
        lastTapTime = 0;

        // Full-screen transparent layer that catches taps.
        recordLayer = new View(this);
        recordLayer.setBackgroundColor(0x22FF0000);
        recordLayer.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) recordTap(e.getRawX(), e.getRawY());
            return true;
        });
        wm.addView(recordLayer, lp(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT, false));

        // Bar on top to finish recording.
        recordBar = new TextView(this);
        recordBar.setText("⏺ جاري التسجيل (0) — اضغط هنا للإنهاء");
        recordBar.setTextColor(Color.WHITE);
        recordBar.setTextSize(15);
        recordBar.setGravity(Gravity.CENTER);
        recordBar.setPadding(dp(16), dp(10), dp(16), dp(10));
        recordBar.setBackground(round(0xEEE53935, dp(20)));
        recordBar.setOnClickListener(v -> finishRecording());
        WindowManager.LayoutParams bp = lp(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, false);
        bp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        bp.y = dp(40);
        wm.addView(recordBar, bp);

        updateBubble();
        toast("انقر على أماكن اللعبة بالترتيب");
    }

    private void recordTap(float x, float y) {
        long now = SystemClock.uptimeMillis();
        long delay = lastTapTime == 0 ? 300 : now - lastTapTime;
        lastTapTime = now;
        taps.add(new Tap(x, y, delay));
        recordBar.setText("⏺ جاري التسجيل (" + taps.size() + ") — اضغط هنا للإنهاء");
        showDot(x, y);
    }

    private void showDot(float x, float y) {
        TextView dot = new TextView(this);
        dot.setText(String.valueOf(taps.size()));
        dot.setTextColor(Color.WHITE);
        dot.setGravity(Gravity.CENTER);
        int s = dp(28);
        dot.setBackground(round(0xCCE53935, s / 2));
        WindowManager.LayoutParams p = lp(s, s, false);
        p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
        p.x = (int) x - s / 2;
        p.y = (int) y - s / 2;
        wm.addView(dot, p);
        h.postDelayed(() -> removeSafe(dot), 600);
    }

    private void finishRecording() {
        recording = false;
        removeSafe(recordLayer);
        removeSafe(recordBar);
        saveTaps();
        updateBubble();
        toast("تم حفظ " + taps.size() + " نقرة");
    }

    // ---------------- playback ----------------

    private void startPlaying() {
        if (taps.isEmpty()) { toast("ما فيه نقرات مسجلة، سجّل أول"); return; }
        playing = true;
        index = 0;
        round = 0;
        updateBubble();
        toast("بدأ التشغيل — اضغط الفقاعة للإيقاف");
        scheduleNext(taps.get(0).delay);
    }

    private void stopPlaying() {
        if (!playing) return;
        playing = false;
        h.removeCallbacksAndMessages(null);
        updateBubble();
        toast("تم الإيقاف");
    }

    private void scheduleNext(long delay) {
        int speed = prefs().getInt(MainActivity.KEY_SPEED, 100);
        long d = Math.max(50, delay * 100 / Math.max(10, speed));
        h.postDelayed(this::playStep, d);
    }

    private void playStep() {
        if (!playing) return;
        Tap t = taps.get(index);
        tap(t.x, t.y);
        index++;
        if (index >= taps.size()) {
            index = 0;
            round++;
            int repeats = prefs().getInt(MainActivity.KEY_REPEATS, 0);
            if (repeats > 0 && round >= repeats) {
                h.postDelayed(this::stopPlaying, 200);
                return;
            }
            long roundDelay = prefs().getInt(MainActivity.KEY_ROUND_DELAY, 2000);
            h.postDelayed(() -> { if (playing) scheduleNext(taps.get(0).delay); }, roundDelay);
        } else {
            scheduleNext(taps.get(index).delay);
        }
    }

    private void tap(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 60))
            .build();
        dispatchGesture(g, null, null);
    }

    // ---------------- storage ----------------

    private SharedPreferences prefs() {
        return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
    }

    private void saveTaps() {
        StringBuilder sb = new StringBuilder();
        for (Tap t : taps) sb.append(t.x).append(',').append(t.y).append(',').append(t.delay).append(';');
        prefs().edit().putString("taps", sb.toString()).apply();
    }

    private void loadTaps() {
        taps.clear();
        String s = prefs().getString("taps", "");
        for (String part : s.split(";")) {
            String[] f = part.split(",");
            if (f.length == 3) {
                try {
                    taps.add(new Tap(Float.parseFloat(f[0]), Float.parseFloat(f[1]), Long.parseLong(f[2])));
                } catch (NumberFormatException ignored) { }
            }
        }
    }

    private void toast(String m) { Toast.makeText(this, m, Toast.LENGTH_SHORT).show(); }
}
