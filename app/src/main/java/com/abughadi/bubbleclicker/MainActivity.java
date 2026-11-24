package com.abughadi.bubbleclicker;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

public class MainActivity extends Activity {

    static final String PREFS = "clicker";
    static final String KEY_REPEATS = "repeats";      // 0 = forever
    static final String KEY_ROUND_DELAY = "round_delay"; // ms between rounds
    static final String KEY_SPEED = "speed";          // percent, 100 = recorded timing
    static final String KEY_RADAR_MIN = "radar_min";  // minutes between automatic radar runs

    private TextView status;
    private EditText repeats, roundDelay, speed, radarMin;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        SharedPreferences p = getSharedPreferences(PREFS, MODE_PRIVATE);

        int pad = dp(20);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);

        TextView title = new TextView(this);
        title.setText("فقاعة النقر");
        title.setTextSize(26);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView help = new TextView(this);
        help.setTextSize(15);
        help.setPadding(0, dp(12), 0, dp(12));
        help.setText(
            "الرادار:\n" +
            "• افتح خريطة اللعبة، اضغط الفقاعة ← «الرادار». يفتح الرادار ويضغط Quick Execute ثم Claim All ويقفل نافذة الجوائز ويرجع.\n" +
            "• «الرادار تلقائي» يعيدها كل فترة تحددها تحت.\n" +
            "• إذا ضغط في مكان غلط: «ضبط مواقع الرادار» واضغط الأماكن بنفسك مرة وحدة.\n" +
            "• لا تحط الفقاعة فوق أيقونة الرادار أو الأزرار.\n" +
            "• يحتاج أندرويد 11 أو أحدث عشان يقرأ لون الأزرار.\n\n" +
            "مسجّل النقرات:\n" +
            "1) اضغط «تفعيل الفقاعة» وفعّل «فقاعة النقر» من إعدادات إمكانية الوصول.\n" +
            "2) افتح اللعبة، تظهر لك الفقاعة الزرقاء. اسحبها لأي مكان.\n" +
            "3) اضغط الفقاعة ← «تسجيل»، ثم انقر على أماكن اللعبة بالترتيب اللي تبيه.\n" +
            "4) اضغط «إنهاء التسجيل» ← ثم «تشغيل»، ويعيد نقراتك تلقائياً.\n" +
            "5) «إيقاف» يوقف التكرار فوراً.\n\n" +
            "تنبيه: الأتمتة مخالفة لشروط بعض الألعاب وقد تسبب حظر الحساب.");
        root.addView(help);

        radarMin = field(root, "الرادار التلقائي: كل كم دقيقة", p.getInt(KEY_RADAR_MIN, 30));
        repeats = field(root, "عدد مرات التكرار (0 = بلا نهاية)", p.getInt(KEY_REPEATS, 0));
        roundDelay = field(root, "انتظار بين كل جولة (بالثواني)", p.getInt(KEY_ROUND_DELAY, 2000) / 1000);
        speed = field(root, "السرعة % (100 = نفس توقيت تسجيلك، 200 = أسرع مرتين)", p.getInt(KEY_SPEED, 100));

        Button save = new Button(this);
        save.setText("حفظ الإعدادات");
        save.setOnClickListener(v -> {
            p.edit()
                .putInt(KEY_REPEATS, num(repeats, 0))
                .putInt(KEY_ROUND_DELAY, num(roundDelay, 2) * 1000)
                .putInt(KEY_SPEED, Math.max(10, num(speed, 100)))
                .putInt(KEY_RADAR_MIN, Math.max(1, num(radarMin, 30)))
                .apply();
            Toast.makeText(this, "تم الحفظ", Toast.LENGTH_SHORT).show();
        });
        root.addView(save);

        Button enable = new Button(this);
        enable.setText("تفعيل الفقاعة (إعدادات إمكانية الوصول)");
        enable.setOnClickListener(v ->
            startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        root.addView(enable);

        status = new TextView(this);
        status.setTextSize(16);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(16), 0, 0);
        root.addView(status);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);
    }

    @Override
    protected void onResume() {
        super.onResume();
        boolean on = ClickerService.instance != null;
        status.setText(on ? "✅ الفقاعة مفعّلة" : "❌ الفقاعة غير مفعّلة");
    }

    private EditText field(LinearLayout root, String label, int value) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setPadding(0, dp(8), 0, 0);
        root.addView(l);
        EditText e = new EditText(this);
        e.setInputType(InputType.TYPE_CLASS_NUMBER);
        e.setText(String.valueOf(value));
        root.addView(e);
        return e;
    }

    private int num(EditText e, int def) {
        try { return Integer.parseInt(e.getText().toString().trim()); }
        catch (Exception ex) { return def; }
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
