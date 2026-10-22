package com.galerieorganisee.pro;

import android.app.Activity;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.text.TextUtils;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import java.util.ArrayList;

public class MainActivity extends Activity {
    private final ArrayList<String> folders = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        SharedPreferences prefs = getSharedPreferences("galerie", MODE_PRIVATE);
        String saved = prefs.getString("folders", "");
        if (!saved.isEmpty()) {
            for (String s : saved.split("\n")) folders.add(s);
        }

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        EditText input = new EditText(this);
        input.setHint("Nom du dossier");
        input.setSingleLine(true);

        Button add = new Button(this);
        add.setText("Créer le dossier");

        ListView list = new ListView(this);
        ArrayAdapter<String> adapter =
                new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, folders);
        list.setAdapter(adapter);

        add.setOnClickListener(v -> {
            String name = input.getText().toString().trim();
            if (name.isEmpty()) return;
            folders.add(name);
            prefs.edit().putString("folders", TextUtils.join("\n", folders)).apply();
            adapter.notifyDataSetChanged();
            input.setText("");
        });

        root.addView(input);
        root.addView(add);
        root.addView(list);
        setContentView(root);
    }
}
