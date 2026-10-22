package com.galerieorganisee.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Path;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.style.StrikethroughSpan;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.UUID;

public class MainActivity extends Activity {

    private static final String T_NOTE = "note";
    private static final String T_PHOTO = "photo";
    private static final String T_VIDEO = "video";
    private static final String T_DRAW = "draw";
    private static final int REQ_PHOTO = 101;
    private static final int REQ_VIDEO = 102;

    private static class Folder {
        String id, name, parent;
        boolean done;
        Folder(String id, String name, String parent) {
            this.id = id;
            this.name = name;
            this.parent = parent;
        }
    }

    // عنصر داخل مجلد المستوى الأخير: ملاحظة أو صورة أو فيديو أو رسم
    private static class Item {
        String id, folder, type, data;
        Item(String id, String folder, String type, String data) {
            this.id = id;
            this.folder = folder;
            this.type = type;
            this.data = data;
        }
    }

    private final ArrayList<Folder> all = new ArrayList<>();
    private final ArrayList<Item> items = new ArrayList<>();
    private final ArrayList<Folder> shown = new ArrayList<>();
    private final ArrayList<Item> shownItems = new ArrayList<>();
    private final ArrayList<CharSequence> labels = new ArrayList<>();

    private String currentId = "";
    private int maxLevels = 3;
    private boolean contentMode = false;

    private SharedPreferences prefs;
    private TextView pathView;
    private Button backBtn, addBtn, levelsBtn;
    private LinearLayout mediaRow;
    private EditText input;
    private ArrayAdapter<CharSequence> adapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        prefs = getSharedPreferences("galerie", MODE_PRIVATE);
        maxLevels = prefs.getInt("max_levels", 3);
        load();

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        pathView = new TextView(this);
        pathView.setTextSize(16);

        levelsBtn = new Button(this);
        backBtn = new Button(this);
        backBtn.setText("Retour");

        input = new EditText(this);
        input.setMaxLines(4);

        addBtn = new Button(this);

        mediaRow = new LinearLayout(this);
        mediaRow.setOrientation(LinearLayout.HORIZONTAL);
        Button photoBtn = new Button(this);
        photoBtn.setText("Photo");
        Button videoBtn = new Button(this);
        videoBtn.setText("Vidéo");
        Button drawBtn = new Button(this);
        drawBtn.setText("Dessin");
        mediaRow.addView(photoBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mediaRow.addView(videoBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mediaRow.addView(drawBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        ListView list = new ListView(this);
        adapter = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> {
            if (contentMode) {
                openItem(shownItems.get(position));
            } else {
                currentId = shown.get(position).id;
                refresh();
            }
        });

        list.setOnItemLongClickListener((parent, view, position, id) -> {
            if (contentMode) {
                deleteItemDialog(shownItems.get(position));
            } else {
                showMenu(shown.get(position));
            }
            return true;
        });

        levelsBtn.setOnClickListener(v -> showLevelsDialog());
        backBtn.setOnClickListener(v -> goUp());
        photoBtn.setOnClickListener(v -> pickMedia("image/*", REQ_PHOTO));
        videoBtn.setOnClickListener(v -> pickMedia("video/*", REQ_VIDEO));
        drawBtn.setOnClickListener(v -> drawDialog());

        addBtn.setOnClickListener(v -> {
            String text = input.getText().toString().trim();
            if (text.isEmpty()) return;
            if (contentMode) {
                items.add(new Item(UUID.randomUUID().toString(), currentId, T_NOTE, text));
            } else {
                all.add(new Folder(UUID.randomUUID().toString(),
                        text.replace("\n", " "), currentId));
            }
            save();
            input.setText("");
            refresh();
        });

        root.addView(pathView);
        root.addView(levelsBtn);
        root.addView(backBtn);
        root.addView(input);
        root.addView(addBtn);
        root.addView(mediaRow);
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

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    // ---------- عدد المستويات ----------

    private void showLevelsDialog() {
        String[] opts = new String[10];
        for (int i = 0; i < 10; i++) opts[i] = String.valueOf(i + 1);
        new AlertDialog.Builder(this)
                .setTitle("Niveaux maximum")
                .setItems(opts, (d, which) -> {
                    int chosen = which + 1;
                    int deep = deepest();
                    if (chosen < deep) {
                        toast("Impossible : il existe déjà un dossier au niveau " + deep);
                    } else {
                        maxLevels = chosen;
                        prefs.edit().putInt("max_levels", chosen).apply();
                        refresh();
                    }
                })
                .show();
    }

    private int deepest() {
        int m = 0;
        for (Folder f : all) m = Math.max(m, depth(f.id));
        return m;
    }

    // ---------- العناصر: ملاحظات / صور / فيديو / رسم ----------

    private void openItem(Item it) {
        if (T_NOTE.equals(it.type)) {
            editNote(it);
        } else if (T_PHOTO.equals(it.type)) {
            showBitmap(decodeUri(Uri.parse(it.data), 1600), it);
        } else if (T_DRAW.equals(it.type)) {
            showBitmap(BitmapFactory.decodeFile(new File(getFilesDir(), it.data).getPath()), it);
        } else if (T_VIDEO.equals(it.type)) {
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(Uri.parse(it.data), "video/*");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                startActivity(i);
            } catch (Exception e) {
                toast("Impossible d'ouvrir la vidéo");
                deleteItemDialog(it);
            }
        }
    }

    private void editNote(Item it) {
        EditText e = new EditText(this);
        e.setText(it.data);
        e.setMaxLines(6);
        new AlertDialog.Builder(this)
                .setTitle("Note")
                .setView(e)
                .setPositiveButton("OK", (d, w) -> {
                    String t = e.getText().toString().trim();
                    if (!t.isEmpty()) {
                        it.data = t;
                        save();
                        refresh();
                    }
                })
                .setNeutralButton("Supprimer", (d, w) -> removeItem(it))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void showBitmap(Bitmap b, Item it) {
        if (b == null) {
            toast("Fichier introuvable");
            deleteItemDialog(it);
            return;
        }
        ImageView iv = new ImageView(this);
        iv.setImageBitmap(b);
        iv.setAdjustViewBounds(true);
        new AlertDialog.Builder(this)
                .setView(iv)
                .setPositiveButton("Fermer", null)
                .setNeutralButton("Supprimer", (d, w) -> deleteItemDialog(it))
                .show();
    }

    private void deleteItemDialog(Item it) {
        new AlertDialog.Builder(this)
                .setTitle("Supprimer")
                .setMessage("Supprimer cet élément ?")
                .setPositiveButton("Supprimer", (d, w) -> removeItem(it))
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void removeItem(Item it) {
        if (T_DRAW.equals(it.type)) {
            new File(getFilesDir(), it.data).delete();
        }
        items.remove(it);
        save();
        refresh();
    }

    private void pickMedia(String mime, int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION
                | Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            toast("Aucune application pour choisir un fichier");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_PHOTO && requestCode != REQ_VIDEO) return;
        if (resultCode != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        try {
            getContentResolver().takePersistableUriPermission(uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION);
        } catch (Exception e) {
            // لا بأس
        }
        String type = (requestCode == REQ_PHOTO) ? T_PHOTO : T_VIDEO;
        items.add(new Item(UUID.randomUUID().toString(), currentId, type, uri.toString()));
        save();
        refresh();
    }

    // قراءة صورة بحجم مصغّر مع تصحيح الدوران
    private Bitmap decodeUri(Uri uri, int max) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (o.outWidth / sample > max || o.outHeight / sample > max) sample *= 2;

            BitmapFactory.Options o2 = new BitmapFactory.Options();
            o2.inSampleSize = sample;
            Bitmap bmp;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                bmp = BitmapFactory.decodeStream(in, null, o2);
            }
            if (bmp == null) return null;

            int rot = 0;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                ExifInterface ei = new ExifInterface(in);
                int ori = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION,
                        ExifInterface.ORIENTATION_NORMAL);
                if (ori == ExifInterface.ORIENTATION_ROTATE_90) rot = 90;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_180) rot = 180;
                else if (ori == ExifInterface.ORIENTATION_ROTATE_270) rot = 270;
            } catch (Exception ignored) {
                // بدون تدوير
            }
            if (rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.getWidth(), bmp.getHeight(), m, true);
            }
            return bmp;
        } catch (Exception e) {
            return null;
        }
    }

    // ---------- الرسم ----------

    private static class DrawView extends View {
        Bitmap bmp;
        Canvas cv;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();

        DrawView(Context c) {
            super(c);
            paint.setColor(Color.BLACK);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(6f);
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStrokeJoin(Paint.Join.ROUND);
        }

        @Override
        protected void onSizeChanged(int w, int h, int ow, int oh) {
            super.onSizeChanged(w, h, ow, oh);
            if (bmp == null && w > 0 && h > 0) {
                bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
                cv = new Canvas(bmp);
                cv.drawColor(Color.WHITE);
            }
        }

        @Override
        protected void onDraw(Canvas canvas) {
            if (bmp != null) canvas.drawBitmap(bmp, 0, 0, null);
            canvas.drawPath(path, paint);
        }

        @Override
        public boolean onTouchEvent(MotionEvent e) {
            float x = e.getX();
            float y = e.getY();
            int a = e.getAction();
            if (a == MotionEvent.ACTION_DOWN) {
                path.moveTo(x, y);
                path.lineTo(x + 0.1f, y + 0.1f);
            } else if (a == MotionEvent.ACTION_MOVE) {
                path.lineTo(x, y);
            } else if (a == MotionEvent.ACTION_UP) {
                path.lineTo(x, y);
                if (cv != null) cv.drawPath(path, paint);
                path.reset();
            }
            invalidate();
            return true;
        }

        void clear() {
            path.reset();
            if (cv != null) cv.drawColor(Color.WHITE);
            invalidate();
        }
    }

    private void drawDialog() {
        DrawView dv = new DrawView(this);
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.5);

        Button clearBtn = new Button(this);
        clearBtn.setText("Effacer");
        clearBtn.setOnClickListener(v -> dv.clear());

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(clearBtn);
        box.addView(dv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h));

        new AlertDialog.Builder(this)
                .setTitle("Dessin")
                .setView(box)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    if (dv.bmp == null) return;
                    String name = "draw_" + UUID.randomUUID() + ".png";
                    try (FileOutputStream fo = new FileOutputStream(new File(getFilesDir(), name))) {
                        dv.bmp.compress(Bitmap.CompressFormat.PNG, 100, fo);
                    } catch (Exception ex) {
                        toast("Échec de l'enregistrement");
                        return;
                    }
                    items.add(new Item(UUID.randomUUID().toString(), currentId, T_DRAW, name));
                    save();
                    refresh();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ---------- قائمة الضغط المطوّل على المجلد ----------

    private void showMenu(Folder f) {
        String[] opts = {
                f.done ? "Annuler « révisé »" : "Marquer comme révisé",
                "Renommer",
                "Supprimer"
        };
        new AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        f.done = !f.done;
                        save();
                        refresh();
                    } else if (which == 1) {
                        renameDialog(f);
                    } else {
                        deleteFolderDialog(f);
                    }
                })
                .show();
    }

    private void renameDialog(Folder f) {
        EditText e = new EditText(this);
        e.setText(f.name);
        e.setSingleLine(true);
        new AlertDialog.Builder(this)
                .setTitle("Renommer")
                .setView(e)
                .setPositiveButton("OK", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) {
                        f.name = n;
                        save();
                        refresh();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void deleteFolderDialog(Folder f) {
        new AlertDialog.Builder(this)
                .setTitle("Supprimer")
                .setMessage("Supprimer « " + f.name + " » et tout son contenu ?")
                .setPositiveButton("Supprimer", (d, w) -> {
                    deleteTree(f.id);
                    save();
                    refresh();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void deleteTree(String id) {
        ArrayList<String> kids = new ArrayList<>();
        for (Folder f : all) if (f.parent.equals(id)) kids.add(f.id);
        for (String k : kids) deleteTree(k);
        for (int i = items.size() - 1; i >= 0; i--) {
            Item it = items.get(i);
            if (it.folder.equals(id)) {
                if (T_DRAW.equals(it.type)) new File(getFilesDir(), it.data).delete();
                items.remove(i);
            }
        }
        Folder target = find(id);
        if (target != null) all.remove(target);
    }

    // ---------- التنقل ----------

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

    private int countItems(String folderId) {
        int n = 0;
        for (Item it : items) if (it.folder.equals(folderId)) n++;
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
        int d = depth(currentId);
        contentMode = !currentId.isEmpty() && d >= maxLevels;

        shown.clear();
        shownItems.clear();
        labels.clear();

        if (contentMode) {
            int pc = 0, vc = 0, dc = 0;
            for (Item it : items) {
                if (!it.folder.equals(currentId)) continue;
                shownItems.add(it);
                if (T_NOTE.equals(it.type)) {
                    labels.add(it.data);
                } else if (T_PHOTO.equals(it.type)) {
                    labels.add("Photo " + (++pc));
                } else if (T_VIDEO.equals(it.type)) {
                    labels.add("Vidéo " + (++vc));
                } else {
                    labels.add("Dessin " + (++dc));
                }
            }
        } else {
            for (Folder f : all) {
                if (!f.parent.equals(currentId)) continue;
                shown.add(f);
                int n = (depth(f.id) >= maxLevels) ? countItems(f.id) : countChildren(f.id);
                String text = n > 0 ? f.name + "  (" + n + ")" : f.name;
                SpannableString s = new SpannableString(text);
                if (f.done) {
                    s.setSpan(new StrikethroughSpan(), 0, text.length(),
                            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                }
                labels.add(s);
            }
        }
        adapter.notifyDataSetChanged();

        String header = pathText() + "\nNiveau " + d + " / " + maxLevels;
        if (contentMode) header += "\nContenu de ce dossier";
        pathView.setText(header);

        input.setHint(contentMode ? "Nouvelle note" : "Nom du dossier");
        addBtn.setText(contentMode ? "Ajouter la note" : "Créer le dossier");

        levelsBtn.setText("Niveaux maximum : " + maxLevels);
        levelsBtn.setVisibility(currentId.isEmpty() ? View.VISIBLE : View.GONE);
        backBtn.setVisibility(currentId.isEmpty() ? View.GONE : View.VISIBLE);
        mediaRow.setVisibility(contentMode ? View.VISIBLE : View.GONE);
    }

    // ---------- الحفظ ----------

    private void load() {
        try {
            JSONArray arr = new JSONArray(prefs.getString("tree", "[]"));
            for (int i = 0; i < arr.length(); i++) {
                JSONObject o = arr.getJSONObject(i);
                Folder f = new Folder(o.getString("id"), o.getString("name"), o.getString("parent"));
                f.done = o.optBoolean("done", false);
                all.add(f);
            }
        } catch (Exception e) {
            // بيانات تالفة: نبدأ فارغين
        }
        try {
            String raw = prefs.getString("items", null);
            if (raw != null) {
                JSONArray arr = new JSONArray(raw);
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    items.add(new Item(o.getString("id"), o.getString("folder"),
                            o.getString("type"), o.getString("data")));
                }
            } else {
                // الملاحظات القديمة من الخطوة السابقة
                JSONArray arr = new JSONArray(prefs.getString("notes", "[]"));
                for (int i = 0; i < arr.length(); i++) {
                    JSONObject o = arr.getJSONObject(i);
                    items.add(new Item(o.getString("id"), o.getString("folder"),
                            T_NOTE, o.getString("text")));
                }
            }
        } catch (Exception e) {
            // تجاهل
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
                o.put("done", f.done);
                arr.put(o);
            }
            JSONArray iarr = new JSONArray();
            for (Item it : items) {
                JSONObject o = new JSONObject();
                o.put("id", it.id);
                o.put("folder", it.folder);
                o.put("type", it.type);
                o.put("data", it.data);
                iarr.put(o);
            }
            prefs.edit()
                    .putString("tree", arr.toString())
                    .putString("items", iarr.toString())
                    .apply();
        } catch (Exception e) {
            // تجاهل
        }
    }
}
