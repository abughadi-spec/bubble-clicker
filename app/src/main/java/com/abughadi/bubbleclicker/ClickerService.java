package com.abughadi.bubbleclicker;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.annotation.SuppressLint;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Accessibility service that shows a floating bubble over the game.
 * - Game tasks (Radar) driven by GameBot on a worker thread.
 * - Simple tap recorder / replayer.
 */
public class ClickerService extends AccessibilityService {

    static ClickerService instance;

    static class Tap {
        final float x, y; final long delay;
        Tap(float x, float y, long delay) { this.x = x; this.y = y; this.delay = delay; }
    }

    private final Handler h = new Handler(Looper.getMainLooper());
    private WindowManager wm;
    private TextView bubble;
    private WindowManager.LayoutParams bubbleLp;
    private View menu;
    private View recordLayer;
    private TextView recordBar;
    private TextView statusBar;

    // tap recorder
    private final List<Tap> taps = new ArrayList<>();
    private boolean recording, playing;
    private long lastTapTime;
    private int index, round;

    // game bot
    private GameBot bot;
    private Thread botThread;

    // ======================================================================
    // lifecycle
    // ======================================================================

    @Override
    protected void onServiceConnected() {
        instance = this;
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        loadTaps();
        showBubble();
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent e) { }
    @Override public void onInterrupt() { stopAll(); }

    @Override
    public void onDestroy() {
        stopAll();
        removeSafe(menu); removeSafe(recordLayer); removeSafe(recordBar);
        removeSafe(statusBar); removeSafe(bubble);
        instance = null;
        super.onDestroy();
    }

    // ======================================================================
    // helpers used by GameBot (safe to call from worker thread)
    // ======================================================================

    SharedPreferences prefs() { return getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE); }

    int[] screenSize() {
        DisplayMetrics m = new DisplayMetrics();
        wm.getDefaultDisplay().getRealMetrics(m);
        return new int[]{m.widthPixels, m.heightPixels};
    }

    boolean canScreenshot() { return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R; }

    /** Tap and wait until the gesture finished. */
    void tapSync(float x, float y) throws InterruptedException {
        CountDownLatch done = new CountDownLatch(1);
        h.post(() -> {
            Path p = new Path();
            p.moveTo(x, y);
            GestureDescription g = new GestureDescription.Builder()
                .addStroke(new GestureDescription.StrokeDescription(p, 0, 60)).build();
            boolean ok = dispatchGesture(g, new GestureResultCallback() {
                @Override public void onCompleted(GestureDescription d) { done.countDown(); }
                @Override public void onCancelled(GestureDescription d) { done.countDown(); }
            }, null);
            if (!ok) done.countDown();
        });
        done.await(3, TimeUnit.SECONDS);
    }

    /** Screenshot of the whole screen (Android 11+), or null. Overlays are hidden first. */
    Bitmap screenshotSync() throws InterruptedException {
        if (!canScreenshot()) return null;
        CountDownLatch done = new CountDownLatch(1);
        AtomicReference<Bitmap> out = new AtomicReference<>();
        h.post(() -> takeScreenshot(Display.DEFAULT_DISPLAY, getMainExecutor(),
            new TakeScreenshotCallback() {
                @Override public void onSuccess(ScreenshotResult r) {
                    try {
                        Bitmap hw = Bitmap.wrapHardwareBuffer(r.getHardwareBuffer(), r.getColorSpace());
                        if (hw != null) {
                            out.set(hw.copy(Bitmap.Config.ARGB_8888, false));
                            hw.recycle();
                        }
                        r.getHardwareBuffer().close();
                    } catch (Exception ignored) { }
                    done.countDown();
                }
                @Override public void onFailure(int code) { done.countDown(); }
            }));
        done.await(4, TimeUnit.SECONDS);
        Thread.sleep(1100); // Android allows ~1 screenshot per second
        return out.get();
    }

    /** Small status line at the top of the screen. */
    void status(String text) {
        h.post(() -> {
            if (statusBar == null) {
                statusBar = new TextView(this);
                statusBar.setTextColor(Color.WHITE);
                statusBar.setTextSize(13);
                statusBar.setPadding(dp(12), dp(6), dp(12), dp(6));
                statusBar.setBackground(round(0xCC000000, dp(12)));
            }
            statusBar.setText(text);
            if (!statusBar.isAttachedToWindow()) {
                WindowManager.LayoutParams p = lp(WindowManager.LayoutParams.WRAP_CONTENT,
                    WindowManager.LayoutParams.WRAP_CONTENT, false);
                p.flags |= WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE;
                p.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
                p.y = dp(6);
                wm.addView(statusBar, p);
            }
        });
    }

    private void hideStatus() { h.post(() -> removeSafe(statusBar)); }

    // ======================================================================
    // game bot control
    // ======================================================================

    private boolean botRunning() { return botThread != null && botThread.isAlive(); }

    private void startRadar(boolean loop) {
        if (botRunning()) { toast("فيه مهمة شغالة، أوقفها أول"); return; }
        bot = new GameBot(this);
        final GameBot b = bot;
        botThread = new Thread(() -> {
            try {
                do {
                    b.runRadar();
                    if (!loop) break;
                    int minutes = Math.max(1, prefs().getInt(MainActivity.KEY_RADAR_MIN, 30));
                    long next = System.currentTimeMillis() + minutes * 60_000L;
                    while (!b.isStopped() && System.currentTimeMillis() < next) {
                        long left = (next - System.currentTimeMillis()) / 1000;
                        status("📡 الرادار الجاي بعد " + (left / 60) + ":" + String.format("%02d", left % 60));
                        Thread.sleep(1000);
                    }
                } while (!b.isStopped());
            } catch (InterruptedException ignored) {
            } finally {
                h.post(() -> { updateBubble(); hideStatus(); });
            }
        });
        botThread.start();
        updateBubble();
    }

    private void stopBot() {
        if (bot != null) bot.stop();
        if (botThread != null) botThread.interrupt();
        botThread = null;
        hideStatus();
        updateBubble();
    }

    private void stopAll() {
        stopBot();
        stopPlaying();
    }

    // ======================================================================
    // overlay helpers
    // ======================================================================

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

    // ======================================================================
    // bubble
    // ======================================================================

    @SuppressLint("ClickableViewAccessibility")
    private void showBubble() {
        bubble = new TextView(this);
        bubble.setTextColor(Color.WHITE);
        bubble.setTextSize(22);
        bubble.setGravity(Gravity.CENTER);
        int size = dp(56);
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
        h.post(() -> {
            if (bubble == null) return;
            boolean bot = botRunning();
            int color = bot ? 0xDDFB8C00 : playing ? 0xDD43A047 : recording ? 0xDDE53935 : 0xDD1E88E5;
            GradientDrawable g = round(color, dp(28));
            g.setStroke(dp(2), Color.WHITE);
            bubble.setBackground(g);
            bubble.setText(bot ? "📡" : playing ? "▶" : recording ? "⏺" : "●");
        });
    }

    private void onBubbleTap() {
        if (botRunning() || playing) { stopAll(); toast("تم الإيقاف"); return; }
        if (menu != null && menu.isAttachedToWindow()) { removeSafe(menu); return; }
        showMenu();
    }

    // ======================================================================
    // menu
    // ======================================================================

    private void showMenu() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(dp(8), dp(8), dp(8), dp(8));

        box.addView(header("مهام اللعبة"));
        int mins = prefs().getInt(MainActivity.KEY_RADAR_MIN, 30);
        box.addView(menuBtn("📡  الرادار (مرة وحدة)", v -> { removeSafe(menu); startRadar(false); }));
        box.addView(menuBtn("🔁  الرادار تلقائي كل " + mins + " د", v -> { removeSafe(menu); startRadar(true); }));
        box.addView(menuBtn("⚙  ضبط مواقع الرادار", v -> { removeSafe(menu); startCalibration(); }));

        box.addView(header("مسجّل النقرات (" + taps.size() + ")"));
        box.addView(menuBtn("⏺  تسجيل جديد", v -> { removeSafe(menu); startRecording(); }));
        box.addView(menuBtn("▶  تشغيل النقرات", v -> { removeSafe(menu); startPlaying(); }));
        box.addView(menuBtn("🗑  مسح النقرات", v -> {
            taps.clear(); saveTaps(); removeSafe(menu); toast("تم المسح");
        }));
        box.addView(menuBtn("✕  إغلاق", v -> removeSafe(menu)));

        ScrollView sv = new ScrollView(this);
        sv.setBackground(round(0xEE222222, dp(14)));
        sv.addView(box);
        menu = sv;

        int[] wh = screenSize();
        WindowManager.LayoutParams p = lp(WindowManager.LayoutParams.WRAP_CONTENT,
            Math.min(dp(470), (int) (wh[1] * 0.7f)), false);
        p.x = Math.max(0, Math.min(bubbleLp.x, wh[0] - dp(220)));
        p.y = Math.max(0, Math.min(bubbleLp.y + dp(64), wh[1] - p.height - dp(10)));
        wm.addView(menu, p);
    }

    private TextView header(String t) {
        TextView tv = new TextView(this);
        tv.setText(t);
        tv.setTextColor(0xFFFFCC80);
        tv.setTextSize(13);
        tv.setPadding(dp(6), dp(8), dp(6), dp(2));
        return tv;
    }

    private Button menuBtn(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextColor(Color.WHITE);
        b.setBackground(round(0xFF3A3A3A, dp(10)));
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(dp(200), dp(44));
        p.topMargin = dp(4);
        b.setLayoutParams(p);
        b.setOnClickListener(l);
        return b;
    }

    // ======================================================================
    // calibration: user taps the radar icon, the bottom button, the back button
    // ======================================================================

    private static final String[] CAL_SPOTS = {GameBot.RADAR_ICON, GameBot.MAIN_BUTTON, GameBot.BACK_BUTTON};
    private static final String[] CAL_PROMPTS = {
        "1/3: افتح خريطة اللعبة واضغط على أيقونة الرادار",
        "2/3: اضغط على الزر الكبير تحت (Quick Execute / Claim All)",
        "3/3: اضغط على زر الرجوع (السهم تحت يسار)"
    };
    private int calStep;

    private void startCalibration() {
        calStep = 0;
        showCalLayer();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showCalLayer() {
        recordLayer = new View(this);
        recordLayer.setBackgroundColor(0x2200AAFF);
        recordLayer.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) onCalTap(e.getRawX(), e.getRawY());
            return true;
        });
        wm.addView(recordLayer, lp(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT, false));

        recordBar = new TextView(this);
        recordBar.setText(CAL_PROMPTS[calStep] + "\n(اضغط هنا للإلغاء)");
        recordBar.setTextColor(Color.WHITE);
        recordBar.setTextSize(14);
        recordBar.setGravity(Gravity.CENTER);
        recordBar.setPadding(dp(16), dp(10), dp(16), dp(10));
        recordBar.setBackground(round(0xEE1565C0, dp(16)));
        recordBar.setOnClickListener(v -> { removeSafe(recordLayer); removeSafe(recordBar); toast("تم الإلغاء"); });
        WindowManager.LayoutParams bp = lp(WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT, false);
        bp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        bp.y = dp(40);
        wm.addView(recordBar, bp);
    }

    private void onCalTap(float x, float y) {
        new GameBot(this).saveSpot(CAL_SPOTS[calStep], x, y);
        removeSafe(recordLayer);
        removeSafe(recordBar);
        // pass the tap through to the game so the next screen opens
        new Thread(() -> {
            try {
                Thread.sleep(150);
                tapSync(x, y);
                Thread.sleep(2500);
            } catch (InterruptedException ignored) { }
            h.post(() -> {
                calStep++;
                if (calStep < CAL_SPOTS.length) showCalLayer();
                else toast("✅ تم حفظ مواقع الرادار");
            });
        }).start();
    }

    // ======================================================================
    // tap recorder
    // ======================================================================

    @SuppressLint("ClickableViewAccessibility")
    private void startRecording() {
        taps.clear();
        recording = true;
        lastTapTime = 0;

        recordLayer = new View(this);
        recordLayer.setBackgroundColor(0x22FF0000);
        recordLayer.setOnTouchListener((v, e) -> {
            if (e.getActionMasked() == MotionEvent.ACTION_UP) recordTap(e.getRawX(), e.getRawY());
            return true;
        });
        wm.addView(recordLayer, lp(WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT, false));

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
        toast("انقر على الأماكن بالترتيب");
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

    private final Runnable playRunnable = this::playStep;

    private void startPlaying() {
        if (taps.isEmpty()) { toast("ما فيه نقرات مسجلة، سجّل أول"); return; }
        if (botRunning()) { toast("فيه مهمة شغالة، أوقفها أول"); return; }
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
        h.removeCallbacks(playRunnable);
        updateBubble();
    }

    private void scheduleNext(long delay) {
        int speed = prefs().getInt(MainActivity.KEY_SPEED, 100);
        long d = Math.max(50, delay * 100 / Math.max(10, speed));
        h.postDelayed(playRunnable, d);
    }

    private void playStep() {
        if (!playing) return;
        Tap t = taps.get(index);
        tapAsync(t.x, t.y);
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
            scheduleNext(roundDelay + taps.get(0).delay);
        } else {
            scheduleNext(taps.get(index).delay);
        }
    }

    private void tapAsync(float x, float y) {
        Path path = new Path();
        path.moveTo(x, y);
        GestureDescription g = new GestureDescription.Builder()
            .addStroke(new GestureDescription.StrokeDescription(path, 0, 60)).build();
        dispatchGesture(g, null, null);
    }

    // ======================================================================
    // storage
    // ======================================================================

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

    void toast(String m) { h.post(() -> Toast.makeText(this, m, Toast.LENGTH_SHORT).show()); }
}
