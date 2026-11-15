package com.enotik.recorder;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.List;

/** Enotik Recorder: настройки и кнопка записи экрана. */
public class MainActivity extends Activity {
    private static final int REQ_CAPTURE = 10;
    private static final int REQ_PERM = 11;
    private static final int ACCENT = Color.parseColor("#E8832A");

    private SharedPreferences prefs;
    private Button startBtn;
    private TextView state;
    private final Handler ui = new Handler(Looper.getMainLooper());

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        prefs = getSharedPreferences("rec", MODE_PRIVATE);

        ScrollView sv = new ScrollView(this);
        sv.setBackgroundColor(Color.parseColor("#1C1815"));
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(18), dp(24), dp(18), dp(24));
        sv.addView(root, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));

        TextView title = new TextView(this);
        title.setText("Enotik Recorder");
        title.setTextSize(26f);
        title.setTextColor(Color.WHITE);
        root.addView(title);
        TextView sub = new TextView(this);
        sub.setText("Запись экрана через аппаратный кодек: игре почти не мешает");
        sub.setTextColor(Color.LTGRAY);
        sub.setPadding(0, dp(4), 0, dp(14));
        root.addView(sub);

        state = new TextView(this);
        state.setTextSize(16f);
        state.setTextColor(ACCENT);
        root.addView(state);

        startBtn = new Button(this);
        startBtn.setTextSize(18f);
        startBtn.setTextColor(Color.WHITE);
        startBtn.setAllCaps(false);
        startBtn.setOnClickListener(v -> toggle());
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(64));
        lp.setMargins(0, dp(8), 0, dp(14));
        root.addView(startBtn, lp);

        spinner(root, "Разрешение (короткая сторона)", "res2",
                new String[]{"360p (самое лёгкое)", "480p (рекомендуется)", "540p", "720p", "1080p", "Оригинал экрана"},
                new int[]{360, 480, 540, 720, 1080, 0}, 480);
        spinner(root, "Кадров в секунду", "fps2",
                new String[]{"24 (самое лёгкое)", "30 (рекомендуется)", "60"}, new int[]{24, 30, 60}, 30);
        spinner(root, "Качество видео", "quality2",
                new String[]{"Низкое (маленький файл)", "Среднее", "Высокое"}, new int[]{0, 1, 2}, 1);
        spinner(root, "Ориентация записи", "orient",
                new String[]{"Авто (как сейчас)", "Горизонтально (игры)", "Вертикально"}, new int[]{0, 1, 2}, 0);

        check(root, "Записывать микрофон", "mic", false);
        check(root, "Кодек H.265 (меньше файл, не на всех телефонах)", "hevc", false);

        Button last = new Button(this);
        last.setText("Открыть последнюю запись");
        last.setAllCaps(false);
        last.setOnClickListener(v -> openLast());
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52));
        lp2.setMargins(0, dp(16), 0, 0);
        root.addView(last, lp2);

        TextView tips = new TextView(this);
        tips.setTextColor(Color.GRAY);
        tips.setPadding(0, dp(18), 0, 0);
        tips.setText("Советы, чтобы FPS не падал:\n"
                + "• чем меньше разрешение, тем меньше нагрузка на графику: 360p/480p почти не влияют на FPS;\n"
                + "• 720p и выше, а также 60 к/с заметно нагружают игру;\n"
                + "• микрофон и H.265 тоже добавляют нагрузку, выключи, если FPS важнее;\n"
                + "• остановить запись можно в шторке уведомлений;\n"
                + "• файлы лежат в Фильмы → Enotik Recorder;\n"
                + "• звук игры (внутренний) пока не записывается, только микрофон.");
        root.addView(tips);

        setContentView(sv);
    }

    private void spinner(LinearLayout root, String label, final String key, String[] names, final int[] values, int def) {
        TextView t = new TextView(this);
        t.setText(label);
        t.setTextColor(Color.LTGRAY);
        t.setPadding(0, dp(10), 0, 0);
        root.addView(t);
        Spinner sp = new Spinner(this);
        sp.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, names));
        int cur = prefs.getInt(key, def);
        for (int i = 0; i < values.length; i++) if (values[i] == cur) sp.setSelection(i);
        sp.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> p, View v, int pos, long id) {
                prefs.edit().putInt(key, values[pos]).apply();
            }

            @Override
            public void onNothingSelected(AdapterView<?> p) {
            }
        });
        root.addView(sp);
    }

    private void check(LinearLayout root, String label, final String key, boolean def) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setTextColor(Color.WHITE);
        c.setChecked(prefs.getBoolean(key, def));
        c.setOnCheckedChangeListener((v, on) -> prefs.edit().putBoolean(key, on).apply());
        root.addView(c);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    @Override
    protected void onPause() {
        ui.removeCallbacksAndMessages(null);
        super.onPause();
    }

    private void refresh() {
        boolean rec = RecorderService.running;
        startBtn.setText(rec ? "■  Остановить запись" : "●  Начать запись");
        GradientDrawable g = new GradientDrawable();
        g.setCornerRadius(dp(14));
        g.setColor(rec ? Color.parseColor("#B71C1C") : ACCENT);
        startBtn.setBackground(g);
        state.setText(rec ? "Идёт запись…" : "Готов к записи");
        ui.removeCallbacksAndMessages(null);
        ui.postDelayed(this::refresh, 1000);
    }

    private void toggle() {
        if (RecorderService.running) {
            startService(new Intent(this, RecorderService.class).setAction(RecorderService.ACTION_STOP));
            ui.postDelayed(this::refresh, 600);
            return;
        }
        List<String> need = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.POST_NOTIFICATIONS);
        if (prefs.getBoolean("mic", false)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            need.add(Manifest.permission.RECORD_AUDIO);
        if (!need.isEmpty()) {
            requestPermissions(need.toArray(new String[0]), REQ_PERM);
        } else {
            askCapture();
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] results) {
        super.onRequestPermissionsResult(code, perms, results);
        if (code != REQ_PERM) return;
        // без микрофона записываем без звука, без уведомлений запись всё равно работает
        if (prefs.getBoolean("mic", false)
                && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            prefs.edit().putBoolean("mic", false).apply();
            Toast.makeText(this, "Микрофон не разрешён: пишу без звука", Toast.LENGTH_LONG).show();
        }
        askCapture();
    }

    private void askCapture() {
        MediaProjectionManager mpm = getSystemService(MediaProjectionManager.class);
        startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req != REQ_CAPTURE) return;
        if (res != RESULT_OK || data == null) {
            Toast.makeText(this, "Запись не разрешена", Toast.LENGTH_SHORT).show();
            return;
        }
        Intent s = new Intent(this, RecorderService.class)
                .setAction(RecorderService.ACTION_START)
                .putExtra("code", res)
                .putExtra("data", data);
        startForegroundService(s);
        moveTaskToBack(true); // сразу уходим, чтобы можно было открыть игру
    }

    private void openLast() {
        String u = prefs.getString("last", null);
        if (u == null) {
            Toast.makeText(this, "Записей пока нет", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent i = new Intent(Intent.ACTION_VIEW).setDataAndType(Uri.parse(u), "video/mp4")
                    .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(this, "Не нашёл приложение для видео. Файл: Фильмы/Enotik Recorder", Toast.LENGTH_LONG).show();
        }
    }
}
