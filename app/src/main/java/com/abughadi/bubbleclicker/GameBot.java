package com.abughadi.bubbleclicker;

import android.content.SharedPreferences;
import android.graphics.Bitmap;

/**
 * Game automation that runs on a background thread.
 *
 * Positions are stored "relative" so they survive different screen sizes:
 *   x = fraction of screen width
 *   b = distance from the BOTTOM of the screen, in screen widths
 * (Last War anchors its bottom/side UI this way, so this matches across aspect ratios.)
 * Defaults come from the user's reference screenshots; the user can re-calibrate in the bubble.
 */
class GameBot {

    // ---- named spots: {x, bottomOffset} ----
    static final String RADAR_ICON = "radar_icon";
    static final String MAIN_BUTTON = "main_button";   // Quick Execute / Claim All
    static final String BACK_BUTTON = "back_button";

    static float[] defaultSpot(String name) {
        switch (name) {
            case RADAR_ICON:  return new float[]{0.066f, 0.519f};
            case MAIN_BUTTON: return new float[]{0.500f, 0.091f};
            case BACK_BUTTON: return new float[]{0.101f, 0.091f};
        }
        return new float[]{0.5f, 0.5f};
    }

    enum ButtonState { EXECUTE_BLUE, CLAIM_GREEN, DIMMED_POPUP, OTHER }

    private final ClickerService s;
    private volatile boolean stopped;

    GameBot(ClickerService s) { this.s = s; }

    void stop() { stopped = true; }
    boolean isStopped() { return stopped; }

    // ---------------- spots ----------------

    private SharedPreferences prefs() { return s.prefs(); }

    float[] spot(String name) {
        float[] d = defaultSpot(name);
        return new float[]{
            prefs().getFloat(name + "_x", d[0]),
            prefs().getFloat(name + "_b", d[1])
        };
    }

    void saveSpot(String name, float px, float py) {
        int[] wh = s.screenSize();
        prefs().edit()
            .putFloat(name + "_x", px / wh[0])
            .putFloat(name + "_b", (wh[1] - py) / wh[0])
            .apply();
    }

    float[] toPixels(float relX, float bottom) {
        int[] wh = s.screenSize();
        return new float[]{relX * wh[0], wh[1] - bottom * wh[0]};
    }

    private void tapSpot(String name) throws InterruptedException {
        float[] r = spot(name);
        float[] p = toPixels(r[0], r[1]);
        s.tapSync(p[0], p[1]);
    }

    /** Tap somewhere harmless near the top-middle of the screen (closes reward popups). */
    private void tapEmpty() throws InterruptedException {
        int[] wh = s.screenSize();
        s.tapSync(wh[0] * 0.5f, wh[1] * 0.18f);
    }

    private void sleep(long ms) throws InterruptedException {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (stopped) throw new InterruptedException();
            Thread.sleep(100);
        }
    }

    // ---------------- vision ----------------

    /** Looks at the big bottom button and decides what colour it is. */
    ButtonState readMainButton() throws InterruptedException {
        Bitmap bmp = s.screenshotSync();
        if (bmp == null) return ButtonState.OTHER;
        try {
            int w = bmp.getWidth(), h = bmp.getHeight();
            float[] c = spot(MAIN_BUTTON);
            float[] p = toPixels(c[0], c[1]);
            int halfW = (int) (w * 0.11f);
            int halfH = (int) (w * 0.035f);
            int x0 = clamp((int) p[0] - halfW, 0, w - 1), x1 = clamp((int) p[0] + halfW, 0, w - 1);
            int y0 = clamp((int) p[1] - halfH, 0, h - 1), y1 = clamp((int) p[1] + halfH, 0, h - 1);

            int blue = 0, green = 0, dark = 0, total = 0;
            for (int y = y0; y <= y1; y += 3) {
                for (int x = x0; x <= x1; x += 3) {
                    int px = bmp.getPixel(x, y);
                    int r = (px >> 16) & 0xFF, g = (px >> 8) & 0xFF, b = px & 0xFF;
                    total++;
                    if (b > 190 && g > 130 && r < 110 && b > r + 100) blue++;
                    else if (g > 170 && r < 150 && b < 140 && g > r + 60) green++;
                    else if (Math.max(r, Math.max(g, b)) < 95) dark++;
                }
            }
            if (total == 0) return ButtonState.OTHER;
            if (blue * 100 / total >= 25) return ButtonState.EXECUTE_BLUE;
            if (green * 100 / total >= 25) return ButtonState.CLAIM_GREEN;
            if (dark * 100 / total >= 60) return ButtonState.DIMMED_POPUP;
            return ButtonState.OTHER;
        } finally {
            bmp.recycle();
        }
    }

    private static int clamp(int v, int lo, int hi) { return Math.max(lo, Math.min(hi, v)); }

    // ---------------- tasks ----------------

    /**
     * Radar:
     *  open radar → while button is blue press Quick Execute, while green press Claim All
     *  and close the rewards popup → go back.
     */
    void runRadar() throws InterruptedException {
        s.status("📡 الرادار: فتح");
        tapSpot(RADAR_ICON);
        sleep(3500);

        int actions = 0;
        if (!s.canScreenshot()) {
            // Android 10 and older can't read the screen: press blindly a few times.
            for (int i = 0; i < 6 && !stopped; i++) {
                tapSpot(MAIN_BUTTON);
                sleep(3000);
                tapEmpty();
                sleep(2000);
                actions++;
            }
        }
        for (int i = 0; i < 25 && !stopped && s.canScreenshot(); i++) {
            ButtonState st = readMainButton();
            if (st == ButtonState.EXECUTE_BLUE) {
                s.status("📡 Quick Execute");
                tapSpot(MAIN_BUTTON);
                sleep(3500);
                actions++;
            } else if (st == ButtonState.CLAIM_GREEN) {
                s.status("📡 Claim All");
                tapSpot(MAIN_BUTTON);
                sleep(3000);
                tapEmpty();          // close CONGRATULATIONS popup
                sleep(2000);
                actions++;
            } else if (st == ButtonState.DIMMED_POPUP) {
                tapEmpty();
                sleep(2000);
            } else {
                break;               // nothing left (no stamina / no tasks)
            }
        }

        s.status("📡 الرادار خلص (" + actions + " ضغطة) — رجوع");
        tapSpot(BACK_BUTTON);
        sleep(2500);
    }
}
