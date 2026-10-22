package com.galerieorganisee.pro;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.UUID;

public class MainActivity extends Activity {
    // عدد المستويات الأقصى (سنجعله قابلًا للتغيير من التطبيق في خطوة لاحقة)
    private static final int MAX_LEVELS = 3;

    private static class Folder {
        String id, name, parent;
        Folder(String id, String name, String parent) {
            this.id = id;
            this.name = name;
            this.parent = parent;
        }
    }

    private final ArrayList<Folder> all = new ArrayList<>();
    private final ArrayList<Folder> shown = new ArrayList<>();
    private final ArrayList<String> labels = new ArrayList<>();
    private String currentId = "";

    private SharedPreferences prefs;
    private TextView pathView;
    private Button backBtn, addBtn;
    private EditText input;
    private ArrayAdapter<String> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("galerie", MODE_PRIVATE);
        load();

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        pathView = new TextView(this);
        pathView.setTextSize(16);

        backBtn = new Button(this);
        backBtn.setText("Retour");

        input = new EditText(this);
        input.setHint("Nom du dossier");
        input.setSingleLine(true);

        addBtn = new Button(this);
        addBtn.setText("Créer le dossier");

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> {
            currentId = shown.get(position).id;
            refresh();
        });

        backBtn.setOnClickListener(v -> goUp());

        addBtn.setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) return;
            all.add(new Folder(UUID.randomUUID().toString(), name, currentId));
            save();
            input.setText("");
            refresh();
        });

        root.addView(pathView);
        root.addView(backBtn);
        root.addView(input);
        root.addView(addBtn);
        root.addView(list);
        setContentView(root);
        refresh();
    }

    @Override
    public void onBackPressed() {
        if (currentId.isEmpty()) {
            super.onBackPressed();
        } else {
            goUp();
        }
    }

    private void goUp() {
        Folder f = find(currentId);
        currentId = (f == null) ? "" : f.parent;
        refresh();
    }

    private Folder find(String id) {
        for (Folder f : all) if (f.id.equals(id)) return f;
        return null;
    }

    private int depth(String id) {
        int d = 0;
        String c = id;
        while (!c.isEmpty()) {
            Folder f = find(c);
            if (f == null) break;
            d++;
            c = f.parent;
        }
        return d;
    }

    private int countChildren(String id) {
        int n = 0;
        for (Folder f : all) if (f.parent.equals(id)) n++;
        return n;
    }

    private String pathText() {
        StringBuilder sb = new StringBuilder();
        String c = currentId;
        while (!c.isEmpty()) {
            Folder f = find(c);
            if (f == null) break;
            sb.insert(0, " > " + f.name);
            c = f.parent;
        }
        return "Racine" + sb;
    }

    private void refresh() {
        shown.clear();
        labels.clear();
        for (Folder f : all) {
            if (f.parent.equals(currentId)) {
                shown.add(f);
                int n = countChildren(f.id);
                labels.add(n > 0 ? f.name + "  (" + n + ")" : f.name);
            }
        }
        adapter.notifyDataSetChanged();

        int d = depth(currentId);
        boolean canAdd = d < MAX_LEVELS;
        String text = pathText() + "\nNiveau " + d + " / " + MAX_LEVELS;
        if (!canAdd) text += "\nNiveau maximum atteint";
        pathView.setText(text);

        int vis = canAdd ? View.VISIBLE : View.GONE;
        input.setVisibility(vis);
        addBtn.setVisibility(vis);
        backBtn.setVisibility(currentId.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void load() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("tree", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                all.add(new Folder(o.getString("id"), o.getString("name"), o.getString("parent")));
            }
        } catch (Exception e) {
            // بيانات تالفة: نبدأ فارغين
        }
    }

    private void save() {
        try {
            JSONArray arr = new JSONArray();
            for (Folder f : all) {
                JSONObject o = new JSONObject();
                o.put("id", f.id);
                o.put("name", f.name);
                o.put("parent", f.parent);
                arr.put(o);
            }
            prefs.edit().putString("tree", arr.toString()).apply();
        } catch (Exception e) {
            // تجاهل
        }
    }
}
