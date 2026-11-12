package com.enotik.zamenmod;

import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.documentfile.provider.DocumentFile;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Enotik-zamen-mod: отдельное приложение для замены визуальных файлов (паков) с бэкапом и откатом.
 * Папки выбираются через системное окно Android (SAF), root не нужен.
 */
public class MainActivity extends Activity {

    private static final int REQ_GAME = 1;
    private static final int REQ_PACKS = 2;
    private static final int MATCH = ViewGroup.LayoutParams.MATCH_PARENT;
    private static final int WRAP = ViewGroup.LayoutParams.WRAP_CONTENT;

    private static final String HELP =
            "Команды:\n"
                    + "сервер 27 - выбрать сервер Grand Mobile\n"
                    + "паки - список паков\n"
                    + "применить 1 - применить пак (номер или имя)\n"
                    + "убрать 1 - убрать один пак\n"
                    + "откат - вернуть все оригинальные файлы\n"
                    + "статус - что сейчас применено\n"
                    + "Папки игры и паков выбираются в «Меню».";

    private SharedPreferences prefs;
    private final ExecutorService exec = Executors.newSingleThreadExecutor();
    private ScrollView mainScroll;
    private LinearLayout menuPanel;
    private LinearLayout packsBox;
    private TextView log;
    private TextView infoView;
    private EditText input;
    private EditText serverInput;
    private String sid = "";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("enotik", MODE_PRIVATE);
        sid = prefs.getString("sid", "");
        buildUi();
        say("Привет! Я Енотик. Нажмите «Меню» или напишите «помощь».");
        updateInfo();
        refreshPacks();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        exec.shutdown();
    }

    // ---------- интерфейс ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private TextView text(String s, int sp) {
        TextView tv = new TextView(this);
        tv.setText(s);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(sp);
        return tv;
    }

    private Button button(String label, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        return b;
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFF1F2933);
        root.setPadding(dp(12), dp(12), dp(12), dp(8));

        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        ImageView avatar = new ImageView(this);
        avatar.setImageResource(R.drawable.ic_enotik);
        header.addView(avatar, new LinearLayout.LayoutParams(dp(56), dp(56)));
        TextView title = text("Enotik-zamen-mod", 20);
        title.setPadding(dp(12), 0, 0, 0);
        header.addView(title);
        root.addView(header);

        infoView = text("", 13);
        infoView.setPadding(0, dp(6), 0, dp(6));
        root.addView(infoView);

        root.addView(button("Меню", v -> {
            boolean open = menuPanel.getVisibility() == View.VISIBLE;
            menuPanel.setVisibility(open ? View.GONE : View.VISIBLE);
            if (!open) mainScroll.post(() -> mainScroll.fullScroll(View.FOCUS_UP));
        }));

        LinearLayout menu = new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menuPanel = menu;
        menuPanel.setVisibility(View.GONE);

        menu.addView(text("Grand Mobile - номер сервера:", 14));
        serverInput = new EditText(this);
        serverInput.setInputType(InputType.TYPE_CLASS_NUMBER);
        serverInput.setTextColor(0xFFFFFFFF);
        serverInput.setHintTextColor(0xFF9AA5B1);
        serverInput.setHint("например 27");
        menu.addView(serverInput);
        menu.addView(button("Выбрать сервер", v -> selectServer(serverInput.getText().toString().trim())));
        menu.addView(button("Папка игры (для выбранного сервера)", v -> pickFolder(REQ_GAME)));
        menu.addView(button("Папка с паками", v -> pickFolder(REQ_PACKS)));

        TextView packsTitle = text("Паки (нажмите, чтобы применить или убрать):", 14);
        packsTitle.setPadding(0, dp(10), 0, 0);
        menu.addView(packsTitle);
        packsBox = new LinearLayout(this);
        packsBox.setOrientation(LinearLayout.VERTICAL);
        menu.addView(packsBox);
        menu.addView(button("Обновить список паков", v -> refreshPacks()));
        menu.addView(button("Откатить всё", v -> command("откат")));

        log = text("", 14);
        log.setTextIsSelectable(true);
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.addView(menu, new LinearLayout.LayoutParams(MATCH, WRAP));
        content.addView(log, new LinearLayout.LayoutParams(MATCH, WRAP));
        mainScroll = new ScrollView(this);
        mainScroll.addView(content);
        root.addView(mainScroll, new LinearLayout.LayoutParams(MATCH, 0, 1f));

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        input = new EditText(this);
        input.setSingleLine(true);
        input.setImeOptions(EditorInfo.IME_ACTION_SEND);
        input.setTextColor(0xFFFFFFFF);
        input.setHintTextColor(0xFF9AA5B1);
        input.setHint("Напишите команду...");
        input.setOnEditorActionListener((v, actionId, event) -> {
            send();
            return true;
        });
        row.addView(input, new LinearLayout.LayoutParams(0, WRAP, 1f));
        row.addView(button("Отправить", v -> send()));
        root.addView(row);

        setContentView(root);
    }

    private void send() {
        String t = input.getText().toString().trim();
        if (!t.isEmpty()) {
            input.setText("");
            command(t);
        }
    }

    private void say(final String s) {
        runOnUiThread(() -> {
            log.append(s + "\n\n");
            if (menuPanel != null && menuPanel.getVisibility() == View.VISIBLE) {
                if (!s.startsWith("> ")) Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
            } else {
                mainScroll.post(() -> mainScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    private void updateInfo() {
        final String s = "Сервер: " + (sid.isEmpty() ? "не выбран" : "Grand Mobile " + sid)
                + "\nПапка игры: " + (gameRoot() != null ? "выбрана" : "не выбрана")
                + " | Папка паков: " + (packsRoot() != null ? "выбрана" : "не выбрана");
        runOnUiThread(() -> infoView.setText(s));
    }

    // ---------- выбор папок ----------

    private void pickFolder(int req) {
        if (req == REQ_GAME && sid.isEmpty()) {
            say("Сначала выберите сервер (номер и кнопка «Выбрать сервер»).");
            return;
        }
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION
                | Intent.FLAG_GRANT_PREFIX_URI_PERMISSION);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            String start = req == REQ_GAME ? "primary:Android/media" : "primary:Download";
            i.putExtra(android.provider.DocumentsContract.EXTRA_INITIAL_URI,
                    android.provider.DocumentsContract.buildDocumentUri(
                            "com.android.externalstorage.documents", start));
        }
        if (req == REQ_GAME) {
            say("Подсказка: Android/data система закрыла от всех обычных приложений. "
                    + "Выбирайте папку игры в Android/media или в обычной памяти телефона.");
        }
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            say("Не удалось открыть выбор папки: " + e.getMessage());
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) {
            say("Папка не выбрана.");
            return;
        }
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        } catch (Exception e) {
            say("Не удалось сохранить доступ к папке: " + e.getMessage());
        }
        if (req == REQ_GAME) {
            prefs.edit().putString("game_" + sid, uri.toString()).apply();
            say("Папка игры для сервера " + sid + " сохранена.");
        } else if (req == REQ_PACKS) {
            prefs.edit().putString("packs", uri.toString()).apply();
            say("Папка с паками сохранена.");
        }
        updateInfo();
        refreshPacks();
    }

    private DocumentFile treeFromPref(String key) {
        String s = prefs.getString(key, null);
        if (s == null) return null;
        try {
            DocumentFile d = DocumentFile.fromTreeUri(this, Uri.parse(s));
            return (d != null && d.exists()) ? d : null;
        } catch (Exception e) {
            return null;
        }
    }

    private DocumentFile gameRoot() {
        return sid.isEmpty() ? null : treeFromPref("game_" + sid);
    }

    private DocumentFile packsRoot() {
        return treeFromPref("packs");
    }

    private void selectServer(String s) {
        if (s.isEmpty() || !s.matches("\\d+")) {
            say("Укажите номер сервера цифрами, например 27.");
            return;
        }
        sid = s;
        prefs.edit().putString("sid", sid).apply();
        say("Выбран Grand Mobile " + sid + (gameRoot() == null ? ". Теперь выберите папку игры в «Меню»." : "."));
        updateInfo();
        refreshPacks();
    }

    // ---------- состояние ----------

    private JSONObject loadState() throws Exception {
        String s = prefs.getString("state_" + sid, null);
        if (s != null) return new JSONObject(s);
        JSONObject o = new JSONObject();
        o.put("applied", new JSONArray());
        o.put("originals", new JSONObject());
        return o;
    }

    private void saveState(JSONObject o) {
        prefs.edit().putString("state_" + sid, o.toString()).apply();
    }

    private static boolean has(JSONArray a, String v) {
        for (int i = 0; i < a.length(); i++) {
            if (v.equals(a.optString(i))) return true;
        }
        return false;
    }

    private List<String> appliedList() {
        List<String> out = new ArrayList<>();
        if (sid.isEmpty()) return out;
        try {
            JSONArray a = loadState().getJSONArray("applied");
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (Exception ignored) {
        }
        return out;
    }

    // ---------- файлы ----------

    private List<DocumentFile> listPacks() {
        List<DocumentFile> out = new ArrayList<>();
        DocumentFile root = packsRoot();
        if (root == null) return out;
        for (DocumentFile f : root.listFiles()) {
            if (f.isDirectory() && f.getName() != null) out.add(f);
        }
        Collections.sort(out, (a, b) -> a.getName().compareToIgnoreCase(b.getName()));
        return out;
    }

    private DocumentFile findPack(String arg) {
        List<DocumentFile> packs = listPacks();
        if (arg.matches("\\d+")) {
            int n = Integer.parseInt(arg);
            if (n >= 1 && n <= packs.size()) return packs.get(n - 1);
        }
        for (DocumentFile p : packs) {
            if (p.getName().equalsIgnoreCase(arg)) return p;
        }
        for (DocumentFile p : packs) {
            if (p.getName().toLowerCase().contains(arg.toLowerCase())) return p;
        }
        return null;
    }

    private void collect(DocumentFile dir, String prefix, Map<String, DocumentFile> out) {
        for (DocumentFile f : dir.listFiles()) {
            String name = f.getName();
            if (name == null) continue;
            if (f.isDirectory()) {
                collect(f, prefix + name + "/", out);
            } else if (!(prefix.isEmpty() && name.equals("pack.json"))) {
                out.put(prefix + name, f);
            }
        }
    }

    private DocumentFile resolve(DocumentFile root, String rel, boolean create) throws IOException {
        String[] parts = rel.split("/");
        DocumentFile cur = root;
        for (int i = 0; i < parts.length; i++) {
            String p = parts[i];
            if (p.isEmpty() || p.equals(".") || p.equals("..")) {
                throw new IOException("Недопустимый путь: " + rel);
            }
            boolean last = i == parts.length - 1;
            DocumentFile next = cur.findFile(p);
            if (next == null) {
                if (!create) return null;
                next = last ? cur.createFile("application/octet-stream", p) : cur.createDirectory(p);
                if (next == null) throw new IOException("Не удалось создать: " + p);
            }
            cur = next;
        }
        return cur;
    }

    private File backupFile(String rel) {
        return new File(getFilesDir(), "backups/" + sid + "/" + rel);
    }

    private static void pump(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
    }

    private void docToFile(DocumentFile src, File dst) throws IOException {
        File parent = dst.getParentFile();
        if (parent != null) parent.mkdirs();
        try (InputStream in = getContentResolver().openInputStream(src.getUri());
             java.io.FileOutputStream out = new java.io.FileOutputStream(dst)) {
            if (in == null) throw new IOException("Не удалось прочитать " + src.getName());
            pump(in, out);
        }
    }

    private void fileToDoc(File src, DocumentFile dst) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(src);
             OutputStream out = getContentResolver().openOutputStream(dst.getUri(), "wt")) {
            if (out == null) throw new IOException("Не удалось записать " + dst.getName());
            pump(in, out);
        }
    }

    private void docToDoc(DocumentFile src, DocumentFile dst) throws IOException {
        try (InputStream in = getContentResolver().openInputStream(src.getUri());
             OutputStream out = getContentResolver().openOutputStream(dst.getUri(), "wt")) {
            if (in == null || out == null) throw new IOException("Не удалось скопировать " + src.getName());
            pump(in, out);
        }
    }

    private static void deleteRecursive(File f) {
        File[] kids = f.listFiles();
        if (kids != null) {
            for (File k : kids) deleteRecursive(k);
        }
        f.delete();
    }

    // ---------- операции ----------

    private String applyPack(DocumentFile pack) throws Exception {
        DocumentFile game = gameRoot();
        if (game == null) return "Выберите папку игры в «Меню».";
        String name = pack.getName();
        JSONObject st = loadState();
        JSONArray applied = st.getJSONArray("applied");
        JSONObject orig = st.getJSONObject("originals");
        if (has(applied, name)) return "Пак «" + name + "» уже применён.";
        Map<String, DocumentFile> files = new TreeMap<>();
        collect(pack, "", files);
        if (files.isEmpty()) return "Пак «" + name + "» пустой.";
        int n = 0;
        try {
            for (Map.Entry<String, DocumentFile> e : files.entrySet()) {
                String rel = e.getKey();
                if (!orig.has(rel)) {
                    DocumentFile existing = resolve(game, rel, false);
                    if (existing != null && existing.isFile()) {
                        docToFile(existing, backupFile(rel));
                        orig.put(rel, true);
                    } else {
                        orig.put(rel, false);
                    }
                    saveState(st);
                }
                DocumentFile dest = resolve(game, rel, true);
                docToDoc(e.getValue(), dest);
                n++;
            }
            applied.put(name);
            saveState(st);
        } catch (Exception ex) {
            saveState(st);
            return "Ошибка: " + ex.getMessage() + ". Напишите «откат», чтобы вернуть оригинальные файлы.";
        }
        return "Пак «" + name + "» применён (файлов: " + n + "). Перезапустите игру.";
    }

    private String revertAll() throws Exception {
        DocumentFile game = gameRoot();
        if (game == null) return "Выберите папку игры в «Меню».";
        JSONObject st = loadState();
        JSONObject orig = st.getJSONObject("originals");
        List<String> keys = new ArrayList<>();
        Iterator<String> it = orig.keys();
        while (it.hasNext()) keys.add(it.next());
        int n = 0;
        for (String rel : keys) {
            if (orig.getBoolean(rel)) {
                DocumentFile dest = resolve(game, rel, true);
                fileToDoc(backupFile(rel), dest);
            } else {
                DocumentFile target = resolve(game, rel, false);
                if (target != null) target.delete();
            }
            n++;
        }
        deleteRecursive(new File(getFilesDir(), "backups/" + sid));
        prefs.edit().remove("state_" + sid).apply();
        return "Откат выполнен, файлов возвращено: " + n + ".";
    }

    private String removePack(DocumentFile pack) throws Exception {
        String name = pack.getName();
        List<String> applied = appliedList();
        if (!applied.contains(name)) return "Пак «" + name + "» не применён.";
        applied.remove(name);
        String r = revertAll();
        if (!r.startsWith("Откат выполнен")) return r;
        for (String other : applied) {
            DocumentFile p = findPack(other);
            if (p != null) applyPack(p);
        }
        return "Пак «" + name + "» убран.";
    }

    private String packsText() {
        List<DocumentFile> packs = listPacks();
        if (packsRoot() == null) return "Выберите папку с паками в «Меню».";
        if (packs.isEmpty()) return "В папке паков нет папок. Каждый пак - отдельная папка с файлами.";
        List<String> applied = appliedList();
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < packs.size(); i++) {
            String n = packs.get(i).getName();
            sb.append(i + 1).append(". ").append(applied.contains(n) ? "[x] " : "[ ] ").append(n).append("\n");
        }
        return sb.toString().trim();
    }

    private String statusText() {
        List<String> applied = appliedList();
        return "Сервер: " + (sid.isEmpty() ? "не выбран" : "Grand Mobile " + sid)
                + "\nПапка игры: " + (gameRoot() != null ? "выбрана" : "не выбрана")
                + "\nПрименено: " + (applied.isEmpty() ? "ничего" : String.join(", ", applied));
    }

    // ---------- чат ----------

    private void command(final String text) {
        say("> " + text);
        exec.execute(() -> {
            String r;
            try {
                r = handle(text.trim());
            } catch (Exception e) {
                r = "Ошибка: " + e.getMessage();
            }
            if (!r.isEmpty()) say(r);
            updateInfo();
            refreshPacksNow();
        });
    }

    private String handle(String t) throws Exception {
        String low = t.toLowerCase();
        int sp = low.indexOf(' ');
        String word = sp < 0 ? low : low.substring(0, sp);
        String arg = sp < 0 ? "" : t.substring(sp + 1).trim();

        if (low.equals("помощь") || low.equals("help") || low.equals("?")) return HELP;
        if (low.equals("паки") || low.equals("список")) return packsText();
        if (low.equals("статус") || low.equals("status")) return statusText();
        if (word.equals("сервер") || word.equals("server")) {
            if (!arg.matches("\\d+")) return "Укажите номер: сервер 27";
            sid = arg;
            prefs.edit().putString("sid", sid).apply();
            return "Выбран Grand Mobile " + sid + (gameRoot() == null ? ". Выберите папку игры в «Меню»." : ".");
        }
        if (sid.isEmpty()) return "Сначала выберите сервер: сервер 27";
        if (low.equals("откат") || low.equals("откатить") || low.equals("верни") || low.equals("revert")) {
            return revertAll();
        }
        if (word.equals("применить") || word.equals("включи") || word.equals("поставь") || word.equals("apply")) {
            DocumentFile p = findPack(arg);
            return p == null ? "Пак не найден. Список: паки" : applyPack(p);
        }
        if (word.equals("убрать") || word.equals("убери") || word.equals("remove")) {
            DocumentFile p = findPack(arg);
            return p == null ? "Пак не найден. Список: паки" : removePack(p);
        }
        return "Я понимаю только команды из списка (это не ИИ). Напишите: помощь";
    }

    // ---------- список паков в меню ----------

    private void refreshPacks() {
        exec.execute(this::refreshPacksNow);
    }

    private void refreshPacksNow() {
        final List<String> names = new ArrayList<>();
        for (DocumentFile p : listPacks()) names.add(p.getName());
        final List<String> applied = appliedList();
        runOnUiThread(() -> {
            packsBox.removeAllViews();
            if (names.isEmpty()) {
                packsBox.addView(text("Паков нет. Выберите папку с паками.", 13));
                return;
            }
            for (final String n : names) {
                final boolean on = applied.contains(n);
                packsBox.addView(button((on ? "[x] " : "[ ] ") + n,
                        v -> command((on ? "убрать " : "применить ") + n)));
            }
        });
    }
}
