package net.kdt.pojavlaunch;

import android.app.AlertDialog;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.Spinner;
import android.widget.TextView;

import net.kdt.pojavlaunch.value.launcherprofiles.LauncherProfiles;
import net.kdt.pojavlaunch.value.launcherprofiles.MinecraftProfile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Enotik: раздел загрузки модов, ресурспаков и шейдеров с Modrinth (официальный открытый API).
 * Файлы кладутся в папку выбранного профиля: mods / resourcepacks / shaderpacks.
 * Обязательные зависимости (например, Fabric API) скачиваются автоматически.
 */
public class EnotikModsActivity extends BaseActivity {
    private static final String API = "https://api.modrinth.com/v2";
    private static final String UA = "dany01052010-creator/Enotik-Launcher/1.0";

    private static final String[] TYPE_LABEL = {"Моды", "Ресурспаки", "Шейдеры"};
    private static final String[] TYPE_ID = {"mod", "resourcepack", "shader"};
    private static final String[] TYPE_DIR = {"mods", "resourcepacks", "shaderpacks"};
    private static final String[] LOADERS = {"fabric", "forge", "neoforge", "quilt"};

    private Spinner typeSpinner, loaderSpinner;
    private EditText versionEdit, queryEdit;
    private TextView status;
    private ProgressBar progress;
    private ListView list;
    private final List<JSONObject> hits = new ArrayList<>();
    private ArrayAdapter<String> adapter;
    private File gameDir;

    @Override
    public boolean setFullscreen() {
        return false;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        String guess = "";
        try {
            MinecraftProfile p = LauncherProfiles.getCurrentProfile();
            gameDir = Tools.getGameDirPath(p);
            Matcher m = Pattern.compile("(\\d+\\.\\d+(?:\\.\\d+)?)").matcher(String.valueOf(p.lastVersionId));
            String last = "";
            while (m.find()) last = m.group(1);
            // «fabric-loader-0.15.11-1.20.1» -> берём последнее число-версию
            guess = last;
        } catch (Throwable t) {
            gameDir = new File(Tools.DIR_GAME_NEW);
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.parseColor("#121212"));
        root.setPadding(dp(12), dp(12), dp(12), dp(12));

        TextView title = new TextView(this);
        title.setText("Скачать моды (Modrinth)");
        title.setTextSize(20f);
        title.setTextColor(Color.WHITE);
        root.addView(title);

        LinearLayout row1 = new LinearLayout(this);
        typeSpinner = new Spinner(this);
        typeSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, TYPE_LABEL));
        loaderSpinner = new Spinner(this);
        loaderSpinner.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, LOADERS));
        row1.addView(typeSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row1.addView(loaderSpinner, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        root.addView(row1);

        versionEdit = new EditText(this);
        versionEdit.setHint("Версия игры, например 1.20.1 (пусто = любая)");
        versionEdit.setText(guess);
        versionEdit.setSingleLine(true);
        versionEdit.setTextColor(Color.WHITE);
        versionEdit.setHintTextColor(Color.GRAY);
        root.addView(versionEdit);

        LinearLayout row2 = new LinearLayout(this);
        queryEdit = new EditText(this);
        queryEdit.setHint("Поиск (например sodium)");
        queryEdit.setSingleLine(true);
        queryEdit.setInputType(InputType.TYPE_CLASS_TEXT);
        queryEdit.setTextColor(Color.WHITE);
        queryEdit.setHintTextColor(Color.GRAY);
        Button go = new Button(this);
        go.setText("Найти");
        go.setOnClickListener(v -> search());
        row2.addView(queryEdit, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        row2.addView(go);
        root.addView(row2);

        status = new TextView(this);
        status.setTextColor(Color.LTGRAY);
        status.setText("Папка: " + new File(gameDir, "mods").getAbsolutePath());
        root.addView(status);
        progress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        progress.setIndeterminate(true);
        progress.setVisibility(View.GONE);
        root.addView(progress);

        list = new ListView(this);
        adapter = new ArrayAdapter<String>(this, android.R.layout.simple_list_item_2, android.R.id.text1, new ArrayList<String>()) {
            @Override
            public View getView(int pos, View cv, ViewGroup parent) {
                View v = super.getView(pos, cv, parent);
                TextView t1 = v.findViewById(android.R.id.text1);
                TextView t2 = v.findViewById(android.R.id.text2);
                t1.setTextColor(Color.WHITE);
                t2.setTextColor(Color.LTGRAY);
                try {
                    JSONObject h = hits.get(pos);
                    t1.setText(h.optString("title") + "  ·  " + h.optString("author"));
                    t2.setText("⬇ " + h.optLong("downloads") + "  —  " + h.optString("description"));
                } catch (Throwable ignored) {
                }
                return v;
            }
        };
        list.setAdapter(adapter);
        list.setOnItemClickListener((AdapterView<?> p, View v, int pos, long id) -> confirmInstall(hits.get(pos)));
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        setContentView(root);
        search();
    }

    private void busy(boolean on, String text) {
        runOnUiThread(() -> {
            progress.setVisibility(on ? View.VISIBLE : View.GONE);
            if (text != null) status.setText(text);
        });
    }

    private String http(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(15000);
        c.setReadTimeout(20000);
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        try (InputStream in = c.getInputStream()) {
            java.io.ByteArrayOutputStream bo = new java.io.ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            return bo.toString("UTF-8");
        }
    }

    private static String enc(String s) throws Exception {
        return URLEncoder.encode(s, "UTF-8");
    }

    private void search() {
        final int type = typeSpinner.getSelectedItemPosition();
        final String loader = LOADERS[loaderSpinner.getSelectedItemPosition()];
        final String ver = versionEdit.getText().toString().trim();
        final String q = queryEdit.getText().toString().trim();
        busy(true, "Ищу…");
        new Thread(() -> {
            try {
                JSONArray facets = new JSONArray();
                facets.put(new JSONArray().put("project_type:" + TYPE_ID[type]));
                if (!ver.isEmpty()) facets.put(new JSONArray().put("versions:" + ver));
                if (type == 0) facets.put(new JSONArray().put("categories:" + loader));
                String url = API + "/search?limit=40&index=" + (q.isEmpty() ? "downloads" : "relevance")
                        + "&query=" + enc(q) + "&facets=" + enc(facets.toString());
                JSONArray arr = new JSONObject(http(url)).getJSONArray("hits");
                final List<JSONObject> res = new ArrayList<>();
                for (int i = 0; i < arr.length(); i++) res.add(arr.getJSONObject(i));
                runOnUiThread(() -> {
                    hits.clear();
                    hits.addAll(res);
                    adapter.clear();
                    for (JSONObject o : res) adapter.add(o.optString("title"));
                    adapter.notifyDataSetChanged();
                });
                busy(false, "Найдено: " + res.size() + ". Нажми на мод, чтобы скачать.");
            } catch (Throwable t) {
                busy(false, "Ошибка поиска: " + t.getMessage());
            }
        }).start();
    }

    private void confirmInstall(JSONObject hit) {
        final int type = typeSpinner.getSelectedItemPosition();
        new AlertDialog.Builder(this)
                .setTitle(hit.optString("title"))
                .setMessage(hit.optString("description") + "\n\nСкачать в папку " + TYPE_DIR[type] + "?")
                .setPositiveButton("Скачать", (d, w) -> install(hit.optString("project_id"), type))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void install(final String projectId, final int type) {
        final String loader = LOADERS[loaderSpinner.getSelectedItemPosition()];
        final String ver = versionEdit.getText().toString().trim();
        busy(true, "Скачиваю…");
        new Thread(() -> {
            try {
                List<String> done = new ArrayList<>();
                installProject(projectId, type, loader, ver, new HashSet<String>(), done, 0);
                busy(false, done.isEmpty() ? "Нет подходящей версии для " + (ver.isEmpty() ? "любой версии" : ver)
                        : "Готово: " + android.text.TextUtils.join(", ", done));
            } catch (Throwable t) {
                busy(false, "Ошибка загрузки: " + t.getMessage());
            }
        }).start();
    }

    private void installProject(String id, int type, String loader, String ver, Set<String> seen,
                                List<String> done, int depth) throws Exception {
        if (!seen.add(id) || depth > 3) return;
        String url = API + "/project/" + id + "/version";
        List<String> q = new ArrayList<>();
        if (!ver.isEmpty()) q.add("game_versions=" + enc(new JSONArray().put(ver).toString()));
        if (type == 0) q.add("loaders=" + enc(new JSONArray().put(loader).toString()));
        if (!q.isEmpty()) url += "?" + android.text.TextUtils.join("&", q);
        JSONArray versions = new JSONArray(http(url));
        if (versions.length() == 0) return;
        JSONObject v = versions.getJSONObject(0);
        JSONArray files = v.getJSONArray("files");
        JSONObject f = files.getJSONObject(0);
        for (int i = 0; i < files.length(); i++) {
            if (files.getJSONObject(i).optBoolean("primary")) f = files.getJSONObject(i);
        }
        File dir = new File(gameDir, TYPE_DIR[type]);
        if (!dir.exists() && !dir.mkdirs()) throw new Exception("не удалось создать папку " + dir);
        File out = new File(dir, new File(f.getString("filename")).getName());
        busy(true, "Скачиваю " + out.getName() + "…");
        HttpURLConnection c = (HttpURLConnection) new URL(f.getString("url")).openConnection();
        c.setRequestProperty("User-Agent", UA);
        c.setConnectTimeout(15000);
        c.setReadTimeout(30000);
        if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
        File tmp = new File(dir, out.getName() + ".part");
        try (InputStream in = c.getInputStream(); OutputStream os = new FileOutputStream(tmp)) {
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) os.write(buf, 0, n);
        }
        if (out.exists()) out.delete();
        if (!tmp.renameTo(out)) throw new Exception("не удалось сохранить " + out.getName());
        done.add(out.getName());

        JSONArray deps = v.optJSONArray("dependencies");
        if (deps != null) {
            for (int i = 0; i < deps.length(); i++) {
                JSONObject d = deps.getJSONObject(i);
                String pid = d.optString("project_id", "");
                if ("required".equals(d.optString("dependency_type")) && !pid.isEmpty() && !pid.equals("null")) {
                    installProject(pid, type, loader, ver, seen, done, depth + 1);
                }
            }
        }
    }
}
