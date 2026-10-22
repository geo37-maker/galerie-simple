package com.galerieorganisee.pro;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
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
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Bundle;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.StrikethroughSpan;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.MediaController;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.VideoView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

public class MainActivity extends Activity {

    private static final String T_NOTE = "note";
    private static final String T_PHOTO = "photo";
    private static final String T_VIDEO = "video";
    private static final String T_DRAW = "draw";

    private static final int REQ_PHOTO = 101;
    private static final int REQ_VIDEO = 102;
    private static final int REQ_COVER = 103;
    private static final int REQ_EXPORT = 104;
    private static final int REQ_IMPORT = 105;

    private static final int MODE_NORMAL = 0;
    private static final int MODE_FAV = 1;
    private static final int MODE_SEARCH = 2;

    private static final String DEFAULT_ICON = "\uD83D\uDCC1";
    private static final String[] ICONS = {
            "\uD83D\uDCC1", "\u2B50", "\uD83D\uDCDA", "\uD83D\uDCF7", "\u2764\uFE0F",
            "\uD83C\uDFE0", "\uD83D\uDCBC", "\uD83C\uDFB5", "\u2708\uFE0F", "\uD83D\uDCA1",
            "\uD83C\uDF93", "\uD83C\uDF3F"
    };
    private static final String[] COLOR_NAMES = {
            "Aucune", "Rouge", "Orange", "Jaune", "Vert", "Bleu", "Violet", "Rose", "Gris"
    };
    private static final int[] COLOR_VALUES = {
            0, 0xFFE53935, 0xFFFB8C00, 0xFFFDD835, 0xFF43A047,
            0xFF1E88E5, 0xFF8E24AA, 0xFFD81B60, 0xFF757575
    };

    // ---------- النماذج ----------

    private static class Folder {
        String id, name, parent, icon = DEFAULT_ICON, cover = "";
        boolean done, fav;
        int color;
        Folder(String id, String name, String parent) {
            this.id = id;
            this.name = name;
            this.parent = parent;
        }
    }

    // عنصر داخل مجلد المستوى الأخير: ملاحظة أو صورة أو فيديو أو رسم
    private static class Item {
        String id, folder, type, data;
        boolean fav;
        Item(String id, String folder, String type, String data) {
            this.id = id;
            this.folder = folder;
            this.type = type;
            this.data = data;
        }
    }

    private static class Row {
        Folder folder;
        Item item;
        CharSequence title;
        String sub = "";
    }

    // ---------- الحالة ----------

    private final ArrayList<Folder> all = new ArrayList<>();
    private final ArrayList<Item> items = new ArrayList<>();
    private final ArrayList<Row> rows = new ArrayList<>();

    private String currentId = "";
    private int maxLevels = 3;
    private int viewMode = MODE_NORMAL;
    private String query = "";
    private boolean contentMode = false;
    private String pendingFolderId = "";

    private boolean dark = false;
    private boolean showPhoto = true;
    private boolean showVideo = true;
    private boolean showDraw = true;
    private boolean showNote = true;

    private SharedPreferences prefs;
    private TextView pathView;
    private Button backBtn, addBtn, photoBtn, videoBtn, drawBtn;
    private LinearLayout mediaRow;
    private EditText input;
    private ListView list;
    private RowAdapter adapter;

    private final LruCache<String, Bitmap> thumbs = new LruCache<>(64);
    private final HashSet<String> loading = new HashSet<>();
    private final HashSet<String> failed = new HashSet<>();
    private final ExecutorService loader = Executors.newSingleThreadExecutor();

    // ---------- الإنشاء ----------

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        prefs = getSharedPreferences("galerie", MODE_PRIVATE);
        dark = prefs.getBoolean("dark", false);
        setTheme(dark ? android.R.style.Theme_Material_NoActionBar
                : android.R.style.Theme_Material_Light_NoActionBar);
        super.onCreate(savedInstanceState);

        maxLevels = prefs.getInt("max_levels", 3);
        showPhoto = prefs.getBoolean("show_photo", true);
        showVideo = prefs.getBoolean("show_video", true);
        showDraw = prefs.getBoolean("show_draw", true);
        showNote = prefs.getBoolean("show_note", true);
        load();

        if (savedInstanceState != null) {
            currentId = savedInstanceState.getString("cur", "");
            if (!currentId.isEmpty() && find(currentId) == null) currentId = "";
        }

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(12), dp(12), dp(12), dp(12));
        root.setBackgroundColor(dark ? 0xFF121212 : Color.WHITE);

        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        Button searchBtn = new Button(this);
        searchBtn.setText("Recherche");
        Button favBtn = new Button(this);
        favBtn.setText("Favoris");
        Button menuBtn = new Button(this);
        menuBtn.setText("Menu");
        bar.addView(searchBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(favBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        bar.addView(menuBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        pathView = new TextView(this);
        pathView.setTextSize(16);
        pathView.setTextColor(fg());

        backBtn = new Button(this);
        backBtn.setText("Retour");

        input = new EditText(this);
        input.setMaxLines(4);
        input.setTextColor(fg());
        input.setHintTextColor(sub());

        addBtn = new Button(this);

        mediaRow = new LinearLayout(this);
        mediaRow.setOrientation(LinearLayout.HORIZONTAL);
        photoBtn = new Button(this);
        photoBtn.setText("Photo");
        videoBtn = new Button(this);
        videoBtn.setText("Vidéo");
        drawBtn = new Button(this);
        drawBtn.setText("Dessin");
        mediaRow.addView(photoBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mediaRow.addView(videoBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        mediaRow.addView(drawBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        list = new ListView(this);
        adapter = new RowAdapter();
        list.setAdapter(adapter);

        list.setOnItemClickListener((parent, view, position, id) -> {
            Row r = rows.get(position);
            if (r.folder != null) {
                currentId = r.folder.id;
                viewMode = MODE_NORMAL;
                refresh();
            } else {
                openItem(r.item);
            }
        });

        list.setOnItemLongClickListener((parent, view, position, id) -> {
            Row r = rows.get(position);
            if (r.folder != null) {
                showFolderMenu(r.folder);
            } else {
                showItemMenu(r.item);
            }
            return true;
        });

        searchBtn.setOnClickListener(v -> searchDialog());
        favBtn.setOnClickListener(v -> {
            viewMode = (viewMode == MODE_FAV) ? MODE_NORMAL : MODE_FAV;
            refresh();
        });
        menuBtn.setOnClickListener(v -> showMainMenu());
        backBtn.setOnClickListener(v -> handleBack());
        photoBtn.setOnClickListener(v -> pickMedia("image/*", REQ_PHOTO, true));
        videoBtn.setOnClickListener(v -> pickMedia("video/*", REQ_VIDEO, true));
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

        root.addView(bar);
        root.addView(pathView);
        root.addView(backBtn);
        root.addView(input);
        root.addView(addBtn);
        root.addView(mediaRow);
        root.addView(list, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f));
        setContentView(root);
        refresh();
    }

    @Override
    protected void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putString("cur", currentId);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        loader.shutdownNow();
    }

    @Override
    public void onBackPressed() {
        if (viewMode != MODE_NORMAL || !currentId.isEmpty()) {
            handleBack();
        } else {
            super.onBackPressed();
        }
    }

    private void handleBack() {
        if (viewMode != MODE_NORMAL) {
            viewMode = MODE_NORMAL;
            refresh();
        } else {
            goUp();
        }
    }

    // ---------- أدوات صغيرة ----------

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }

    private int fg() {
        return dark ? Color.WHITE : Color.BLACK;
    }

    private int sub() {
        return dark ? 0xFFBBBBBB : 0xFF666666;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_LONG).show();
    }

    private AlertDialog waitDialog(String msg) {
        AlertDialog d = new AlertDialog.Builder(this)
                .setMessage(msg)
                .setCancelable(false)
                .create();
        d.show();
        return d;
    }

    private void safeDismiss(AlertDialog d) {
        try {
            d.dismiss();
        } catch (Exception ignored) {
            // النافذة أُغلقت
        }
    }

    private static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] buf = new byte[65536];
        int n;
        while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
    }

    // ---------- المحوّل (القائمة) ----------

    private class RowAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return rows.size();
        }

        @Override
        public Object getItem(int position) {
            return rows.get(position);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return buildRow(rows.get(position));
        }
    }

    private View buildRow(Row r) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(4), dp(6), dp(4), dp(6));

        FrameLayout cell = new FrameLayout(this);
        ImageView iv = new ImageView(this);
        iv.setScaleType(ImageView.ScaleType.CENTER_CROP);
        TextView icon = new TextView(this);
        icon.setGravity(Gravity.CENTER);
        icon.setTextSize(26);
        icon.setTextColor(Color.BLACK);
        cell.addView(iv, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        cell.addView(icon, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        row.addView(cell, new LinearLayout.LayoutParams(dp(56), dp(56)));

        int plain = dark ? 0xFF424242 : 0xFFE0E0E0;
        boolean fav;
        if (r.folder != null) {
            Folder f = r.folder;
            fav = f.fav;
            cell.setBackgroundColor(f.color != 0 ? f.color : plain);
            Bitmap cover = f.cover.isEmpty() ? null : thumbFor(f.cover, T_PHOTO);
            if (cover != null) {
                iv.setImageBitmap(cover);
            } else {
                icon.setText(f.icon);
            }
        } else {
            Item it = r.item;
            fav = it.fav;
            cell.setBackgroundColor(plain);
            if (T_NOTE.equals(it.type)) {
                icon.setText("\uD83D\uDCDD");
            } else {
                Bitmap b = thumbFor(it.data, it.type);
                if (b != null) {
                    iv.setImageBitmap(b);
                } else if (T_VIDEO.equals(it.type)) {
                    icon.setText("\u25B6");
                } else if (T_PHOTO.equals(it.type)) {
                    icon.setText("\uD83D\uDDBC");
                } else {
                    icon.setText("\u270F");
                }
            }
        }

        LinearLayout texts = new LinearLayout(this);
        texts.setOrientation(LinearLayout.VERTICAL);
        TextView title = new TextView(this);
        title.setText(r.title);
        title.setTextSize(16);
        title.setTextColor(fg());
        title.setMaxLines(2);
        title.setEllipsize(TextUtils.TruncateAt.END);
        texts.addView(title);
        if (!r.sub.isEmpty()) {
            TextView subtitle = new TextView(this);
            subtitle.setText(r.sub);
            subtitle.setTextSize(12);
            subtitle.setTextColor(sub());
            subtitle.setMaxLines(1);
            subtitle.setEllipsize(TextUtils.TruncateAt.END);
            texts.addView(subtitle);
        }
        LinearLayout.LayoutParams tp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f);
        tp.leftMargin = dp(12);
        row.addView(texts, tp);

        if (fav) {
            TextView star = new TextView(this);
            star.setText("\u2605");
            star.setTextSize(22);
            star.setTextColor(0xFFFFB300);
            row.addView(star);
        }
        return row;
    }

    // ---------- المصغّرات (تُحمَّل في الخلفية) ----------

    private Bitmap thumbFor(String key, String type) {
        Bitmap b = thumbs.get(key);
        if (b != null) return b;
        if (failed.contains(key) || loading.contains(key)) return null;
        loading.add(key);
        loader.execute(() -> {
            Bitmap result = loadThumb(key, type);
            runOnUiThread(() -> {
                loading.remove(key);
                if (result != null) {
                    thumbs.put(key, result);
                    adapter.notifyDataSetChanged();
                } else {
                    failed.add(key);
                }
            });
        });
        return null;
    }

    private Bitmap loadThumb(String key, String type) {
        Uri uri = uriFor(key);
        if (T_VIDEO.equals(type)) {
            MediaMetadataRetriever mr = new MediaMetadataRetriever();
            try {
                mr.setDataSource(this, uri);
                Bitmap frame = mr.getFrameAtTime(0);
                if (frame == null) return null;
                return scaleDown(frame, 160);
            } catch (Exception e) {
                return null;
            } finally {
                try {
                    mr.release();
                } catch (Exception ignored) {
                    // لا شيء
                }
            }
        }
        return decodeUri(uri, 160);
    }

    private Bitmap scaleDown(Bitmap b, int max) {
        int w = b.getWidth();
        int h = b.getHeight();
        float s = Math.min(1f, (float) max / Math.max(w, h));
        if (s >= 1f) return b;
        return Bitmap.createScaledBitmap(b, Math.max(1, (int) (w * s)),
                Math.max(1, (int) (h * s)), true);
    }

    // ---------- الملفات الداخلية ----------

    private File mediaDir() {
        File d = new File(getFilesDir(), "media");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    // الرسومات القديمة كانت تُحفظ مباشرة في filesDir
    private File fileFor(String name) {
        File f = new File(mediaDir(), name);
        if (!f.exists()) {
            File legacy = new File(getFilesDir(), name);
            if (legacy.exists()) return legacy;
        }
        return f;
    }

    private Uri uriFor(String data) {
        if (data.startsWith("content:")) return Uri.parse(data);
        return Uri.fromFile(fileFor(data));
    }

    private void deleteMediaFile(String name) {
        if (name == null || name.isEmpty() || name.startsWith("content:")) return;
        thumbs.remove(name);
        File f = fileFor(name);
        if (f.exists()) f.delete();
    }

    private String copyToMedia(Uri uri, String ext) throws Exception {
        String name = UUID.randomUUID() + ext;
        try (InputStream in = getContentResolver().openInputStream(uri);
             FileOutputStream out = new FileOutputStream(new File(mediaDir(), name))) {
            copy(in, out);
        }
        return name;
    }

    // ---------- القائمة الرئيسية ----------

    private void showMainMenu() {
        String[] opts = {
                "Niveaux maximum",
                "Apparence et fonctions",
                "Exporter une sauvegarde",
                "Importer une sauvegarde"
        };
        new AlertDialog.Builder(this)
                .setTitle("Menu")
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        showLevelsDialog();
                    } else if (which == 1) {
                        showSettingsDialog();
                    } else if (which == 2) {
                        startExport();
                    } else {
                        confirmImport();
                    }
                })
                .show();
    }

    private void showLevelsDialog() {
        String[] opts = new String[10];
        for (int i = 0; i < 10; i++) opts[i] = String.valueOf(i + 1);
        new AlertDialog.Builder(this)
                .setTitle("Niveaux maximum (actuel : " + maxLevels + ")")
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

    private void showSettingsDialog() {
        String[] opts = {"Mode sombre", "Photos", "Vidéos", "Dessins", "Notes"};
        boolean[] checked = {dark, showPhoto, showVideo, showDraw, showNote};
        new AlertDialog.Builder(this)
                .setTitle("Apparence et fonctions")
                .setMultiChoiceItems(opts, checked, (d, which, isChecked) -> checked[which] = isChecked)
                .setPositiveButton("OK", (d, w) -> {
                    boolean darkChanged = checked[0] != dark;
                    dark = checked[0];
                    showPhoto = checked[1];
                    showVideo = checked[2];
                    showDraw = checked[3];
                    showNote = checked[4];
                    prefs.edit()
                            .putBoolean("dark", dark)
                            .putBoolean("show_photo", showPhoto)
                            .putBoolean("show_video", showVideo)
                            .putBoolean("show_draw", showDraw)
                            .putBoolean("show_note", showNote)
                            .apply();
                    if (darkChanged) {
                        recreate();
                    } else {
                        refresh();
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ---------- البحث ----------

    private void searchDialog() {
        EditText e = new EditText(this);
        e.setSingleLine(true);
        e.setHint("Rechercher…");
        new AlertDialog.Builder(this)
                .setTitle("Recherche")
                .setView(e)
                .setPositiveButton("Chercher", (d, w) -> {
                    String q = e.getText().toString().trim();
                    if (q.isEmpty()) return;
                    query = q;
                    viewMode = MODE_SEARCH;
                    refresh();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    // ---------- فتح العناصر ----------

    private void openItem(Item it) {
        if (T_NOTE.equals(it.type)) {
            editNote(it);
        } else if (T_PHOTO.equals(it.type) || T_DRAW.equals(it.type)) {
            showBitmap(decodeUri(uriFor(it.data), 1600), it);
        } else if (T_VIDEO.equals(it.type)) {
            if (it.data.startsWith("content:")) {
                Intent i = new Intent(Intent.ACTION_VIEW);
                i.setDataAndType(Uri.parse(it.data), "video/*");
                i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                try {
                    startActivity(i);
                } catch (Exception e) {
                    toast("Impossible d'ouvrir la vidéo");
                    deleteItemDialog(it);
                }
            } else {
                playVideo(it);
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

    private void playVideo(Item it) {
        File f = fileFor(it.data);
        if (!f.exists()) {
            toast("Fichier introuvable");
            deleteItemDialog(it);
            return;
        }
        VideoView vv = new VideoView(this);
        vv.setVideoPath(f.getPath());
        MediaController mc = new MediaController(this);
        mc.setAnchorView(vv);
        vv.setMediaController(mc);

        LinearLayout box = new LinearLayout(this);
        box.addView(vv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (int) (getResources().getDisplayMetrics().heightPixels * 0.5)));

        AlertDialog dlg = new AlertDialog.Builder(this)
                .setView(box)
                .setPositiveButton("Fermer", null)
                .setNeutralButton("Supprimer", (d, w) -> deleteItemDialog(it))
                .create();
        dlg.setOnDismissListener(d -> vv.stopPlayback());
        dlg.show();
        vv.start();
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

    // ---------- قائمة العنصر (ضغط مطوّل) ----------

    private void showItemMenu(Item it) {
        String[] opts = {
                it.fav ? "Retirer des favoris" : "Ajouter aux favoris",
                "Déplacer",
                "Supprimer"
        };
        new AlertDialog.Builder(this)
                .setItems(opts, (d, which) -> {
                    if (which == 0) {
                        it.fav = !it.fav;
                        save();
                        refresh();
                    } else if (which == 1) {
                        moveItemDialog(it);
                    } else {
                        deleteItemDialog(it);
                    }
                })
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
        if (!T_NOTE.equals(it.type)) deleteMediaFile(it.data);
        items.remove(it);
        save();
        refresh();
    }

    private void moveItemDialog(Item it) {
        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (Folder g : all) {
            if (g.id.equals(it.folder)) continue;
            if (depth(g.id) < maxLevels) continue;
            ids.add(g.id);
            names.add(pathOf(g.id));
        }
        if (ids.isEmpty()) {
            toast("Aucune destination possible");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Déplacer vers")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    it.folder = ids.get(which);
                    save();
                    refresh();
                })
                .show();
    }

    // ---------- قائمة المجلد (ضغط مطوّل) ----------

    private void showFolderMenu(Folder f) {
        ArrayList<String> names = new ArrayList<>();
        ArrayList<Runnable> acts = new ArrayList<>();

        names.add(f.fav ? "Retirer des favoris" : "Ajouter aux favoris");
        acts.add(() -> {
            f.fav = !f.fav;
            save();
            refresh();
        });
        names.add(f.done ? "Annuler « révisé »" : "Marquer comme révisé");
        acts.add(() -> {
            f.done = !f.done;
            save();
            refresh();
        });
        names.add("Renommer");
        acts.add(() -> renameDialog(f));
        names.add("Déplacer");
        acts.add(() -> moveFolderDialog(f));
        names.add("Couleur");
        acts.add(() -> colorDialog(f));
        names.add("Icône");
        acts.add(() -> iconDialog(f));
        names.add("Choisir une couverture");
        acts.add(() -> {
            pendingFolderId = f.id;
            pickMedia("image/*", REQ_COVER, false);
        });
        if (!f.cover.isEmpty()) {
            names.add("Retirer la couverture");
            acts.add(() -> {
                deleteMediaFile(f.cover);
                f.cover = "";
                save();
                refresh();
            });
        }
        names.add("Supprimer");
        acts.add(() -> deleteFolderDialog(f));

        new AlertDialog.Builder(this)
                .setTitle(f.name)
                .setItems(names.toArray(new String[0]), (d, which) -> acts.get(which).run())
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

    private void colorDialog(Folder f) {
        new AlertDialog.Builder(this)
                .setTitle("Couleur")
                .setItems(COLOR_NAMES, (d, which) -> {
                    f.color = COLOR_VALUES[which];
                    save();
                    refresh();
                })
                .show();
    }

    private void iconDialog(Folder f) {
        new AlertDialog.Builder(this)
                .setTitle("Icône")
                .setItems(ICONS, (d, which) -> {
                    f.icon = ICONS[which];
                    save();
                    refresh();
                })
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
                if (!T_NOTE.equals(it.type)) deleteMediaFile(it.data);
                items.remove(i);
            }
        }
        Folder target = find(id);
        if (target != null) {
            deleteMediaFile(target.cover);
            all.remove(target);
        }
    }

    // نقل مجلد: يجب أن تتسع المستويات، وإن كان يحوي عناصر فيجب أن يبقى مستواه كما هو
    private void moveFolderDialog(Folder f) {
        int h = height(f.id);
        boolean hasItems = subtreeHasItems(f.id);
        int oldDepth = depth(f.parent);
        ArrayList<String> candidates = new ArrayList<>();
        candidates.add("");
        for (Folder g : all) candidates.add(g.id);

        ArrayList<String> ids = new ArrayList<>();
        ArrayList<String> names = new ArrayList<>();
        for (String c : candidates) {
            if (c.equals(f.parent)) continue;
            if (isUnder(c, f.id)) continue;
            int dd = depth(c);
            if (dd + h > maxLevels) continue;
            if (hasItems && dd != oldDepth) continue;
            ids.add(c);
            names.add(pathOf(c));
        }
        if (ids.isEmpty()) {
            toast("Aucune destination possible");
            return;
        }
        new AlertDialog.Builder(this)
                .setTitle("Déplacer vers")
                .setItems(names.toArray(new String[0]), (d, which) -> {
                    f.parent = ids.get(which);
                    save();
                    refresh();
                })
                .show();
    }

    private int height(String id) {
        int best = 0;
        for (Folder f : all) if (f.parent.equals(id)) best = Math.max(best, height(f.id));
        return best + 1;
    }

    private boolean subtreeHasItems(String id) {
        for (Item it : items) if (it.folder.equals(id)) return true;
        for (Folder f : all) if (f.parent.equals(id) && subtreeHasItems(f.id)) return true;
        return false;
    }

    // هل candidateId هو ancestorId نفسه أو أحد أبنائه؟
    private boolean isUnder(String candidateId, String ancestorId) {
        String c = candidateId;
        int guard = 0;
        while (!c.isEmpty() && guard++ < 100) {
            if (c.equals(ancestorId)) return true;
            Folder x = find(c);
            if (x == null) break;
            c = x.parent;
        }
        return false;
    }

    // ---------- اختيار الوسائط ونسخها داخل التطبيق ----------

    private void pickMedia(String mime, int req, boolean multiple) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType(mime);
        if (multiple) i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            toast("Aucune application pour choisir un fichier");
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != RESULT_OK || data == null) return;

        if (requestCode == REQ_EXPORT) {
            Uri u = data.getData();
            if (u != null) doExport(u);
            return;
        }
        if (requestCode == REQ_IMPORT) {
            Uri u = data.getData();
            if (u != null) doImport(u);
            return;
        }
        if (requestCode == REQ_PHOTO || requestCode == REQ_VIDEO || requestCode == REQ_COVER) {
            ArrayList<Uri> uris = new ArrayList<>();
            ClipData cd = data.getClipData();
            if (cd != null) {
                for (int i = 0; i < cd.getItemCount(); i++) {
                    Uri u = cd.getItemAt(i).getUri();
                    if (u != null) uris.add(u);
                }
            } else if (data.getData() != null) {
                uris.add(data.getData());
            }
            if (!uris.isEmpty()) importMedia(uris, requestCode);
        }
    }

    private void importMedia(ArrayList<Uri> uris, int req) {
        String targetFolder = (req == REQ_COVER) ? pendingFolderId : currentId;
        AlertDialog wait = waitDialog("Copie en cours…");
        new Thread(() -> {
            ArrayList<String> names = new ArrayList<>();
            for (Uri u : uris) {
                try {
                    names.add(copyToMedia(u, req == REQ_VIDEO ? ".mp4" : ".jpg"));
                } catch (Exception e) {
                    // نتجاوز الملف الفاشل
                }
            }
            runOnUiThread(() -> {
                safeDismiss(wait);
                if (names.isEmpty()) {
                    toast("Échec de la copie");
                    return;
                }
                if (req == REQ_COVER) {
                    Folder f = find(targetFolder);
                    if (f != null) {
                        deleteMediaFile(f.cover);
                        f.cover = names.get(0);
                    }
                } else {
                    String type = (req == REQ_PHOTO) ? T_PHOTO : T_VIDEO;
                    for (String n : names) {
                        items.add(new Item(UUID.randomUUID().toString(), targetFolder, type, n));
                    }
                }
                save();
                refresh();
            });
        }).start();
    }

    // ---------- النسخ الاحتياطي ----------

    private void startExport() {
        Intent i = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("application/zip");
        String stamp = new SimpleDateFormat("yyyyMMdd-HHmm", Locale.US).format(new Date());
        i.putExtra(Intent.EXTRA_TITLE, "galerie-organisee-" + stamp + ".zip");
        try {
            startActivityForResult(i, REQ_EXPORT);
        } catch (Exception e) {
            toast("Impossible de créer le fichier");
        }
    }

    private void doExport(Uri uri) {
        AlertDialog wait = waitDialog("Sauvegarde en cours…");
        new Thread(() -> {
            String result;
            try (OutputStream os = getContentResolver().openOutputStream(uri);
                 ZipOutputStream zos = new ZipOutputStream(os)) {
                JSONObject root = new JSONObject();
                root.put("version", 1);
                root.put("max_levels", maxLevels);
                root.put("tree", treeJson());
                root.put("items", itemsJson());
                zos.putNextEntry(new ZipEntry("data.json"));
                zos.write(root.toString().getBytes("UTF-8"));
                zos.closeEntry();

                ArrayList<String> names = new ArrayList<>();
                for (Item it : items) if (!T_NOTE.equals(it.type)) names.add(it.data);
                for (Folder fo : all) if (!fo.cover.isEmpty()) names.add(fo.cover);

                HashSet<String> added = new HashSet<>();
                int skipped = 0;
                for (String n : names) {
                    if (n.startsWith("content:")) {
                        skipped++;
                        continue;
                    }
                    if (!added.add(n)) continue;
                    File file = fileFor(n);
                    if (!file.exists()) continue;
                    zos.putNextEntry(new ZipEntry("media/" + n));
                    try (FileInputStream in = new FileInputStream(file)) {
                        copy(in, zos);
                    }
                    zos.closeEntry();
                }
                result = skipped > 0
                        ? "Sauvegarde terminée (" + skipped + " ancien(s) fichier(s) non inclus)"
                        : "Sauvegarde terminée";
            } catch (Exception ex) {
                result = "Échec de la sauvegarde";
            }
            String msg = result;
            runOnUiThread(() -> {
                safeDismiss(wait);
                toast(msg);
            });
        }).start();
    }

    private void confirmImport() {
        new AlertDialog.Builder(this)
                .setTitle("Importer une sauvegarde")
                .setMessage("Toutes les données actuelles seront remplacées. Continuer ?")
                .setPositiveButton("Continuer", (d, w) -> {
                    Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
                    i.addCategory(Intent.CATEGORY_OPENABLE);
                    i.setType("*/*");
                    try {
                        startActivityForResult(i, REQ_IMPORT);
                    } catch (Exception e) {
                        toast("Aucune application pour choisir un fichier");
                    }
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    private void doImport(Uri uri) {
        AlertDialog wait = waitDialog("Importation en cours…");
        new Thread(() -> {
            String json = null;
            try (InputStream is = getContentResolver().openInputStream(uri);
                 ZipInputStream zis = new ZipInputStream(is)) {
                ZipEntry ze;
                while ((ze = zis.getNextEntry()) != null) {
                    if (ze.isDirectory()) continue;
                    String n = ze.getName();
                    if (n.equals("data.json")) {
                        ByteArrayOutputStream bo = new ByteArrayOutputStream();
                        copy(zis, bo);
                        json = bo.toString("UTF-8");
                    } else if (n.startsWith("media/")) {
                        String base = new File(n).getName();
                        if (!base.isEmpty()) {
                            try (FileOutputStream fo = new FileOutputStream(new File(mediaDir(), base))) {
                                copy(zis, fo);
                            }
                        }
                    }
                }
            } catch (Exception ex) {
                json = null;
            }

            String result;
            if (json == null) {
                result = "Fichier de sauvegarde invalide";
            } else {
                try {
                    JSONObject root = new JSONObject(json);
                    ArrayList<Folder> nf = parseTree(root.getJSONArray("tree"));
                    ArrayList<Item> ni = parseItems(root.getJSONArray("items"));
                    int ml = root.optInt("max_levels", 3);
                    runOnUiThread(() -> {
                        all.clear();
                        all.addAll(nf);
                        items.clear();
                        items.addAll(ni);
                        maxLevels = Math.max(1, Math.min(10, ml));
                        prefs.edit().putInt("max_levels", maxLevels).apply();
                        thumbs.evictAll();
                        failed.clear();
                        currentId = "";
                        viewMode = MODE_NORMAL;
                        save();
                        refresh();
                    });
                    result = "Sauvegarde importée";
                } catch (Exception ex) {
                    result = "Fichier de sauvegarde invalide";
                }
            }
            String msg = result;
            runOnUiThread(() -> {
                safeDismiss(wait);
                toast(msg);
            });
        }).start();
    }

    // ---------- الرسم ----------

    private static class DrawView extends View {
        static final int[] COLORS = {
                Color.BLACK, Color.RED, Color.BLUE, 0xFF2E7D32, 0xFFFB8C00, Color.WHITE
        };
        static final String[] NAMES = {"Noir", "Rouge", "Bleu", "Vert", "Orange", "Gomme"};
        static final float[] WIDTHS = {4f, 8f, 16f, 32f};

        Bitmap bmp;
        Canvas cv;
        final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        final Path path = new Path();
        int ci = 0;
        int wi = 1;

        DrawView(Context c) {
            super(c);
            paint.setColor(COLORS[ci]);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(WIDTHS[wi]);
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
            } else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL) {
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

        String nextColor() {
            ci = (ci + 1) % COLORS.length;
            paint.setColor(COLORS[ci]);
            return NAMES[ci];
        }

        int nextWidth() {
            wi = (wi + 1) % WIDTHS.length;
            paint.setStrokeWidth(WIDTHS[wi]);
            return (int) WIDTHS[wi];
        }
    }

    private void drawDialog() {
        DrawView dv = new DrawView(this);
        int h = (int) (getResources().getDisplayMetrics().heightPixels * 0.5);

        Button clearBtn = new Button(this);
        clearBtn.setText("Effacer");
        clearBtn.setOnClickListener(v -> dv.clear());
        Button colorBtn = new Button(this);
        colorBtn.setText("Couleur : Noir");
        colorBtn.setOnClickListener(v -> colorBtn.setText("Couleur : " + dv.nextColor()));
        Button widthBtn = new Button(this);
        widthBtn.setText("Épaisseur : 8");
        widthBtn.setOnClickListener(v -> widthBtn.setText("Épaisseur : " + dv.nextWidth()));

        LinearLayout tools = new LinearLayout(this);
        tools.setOrientation(LinearLayout.HORIZONTAL);
        tools.addView(clearBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tools.addView(colorBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        tools.addView(widthBtn, new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(tools);
        box.addView(dv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, h));

        new AlertDialog.Builder(this)
                .setTitle("Dessin")
                .setView(box)
                .setPositiveButton("Enregistrer", (d, w) -> {
                    if (dv.bmp == null) return;
                    String name = "draw_" + UUID.randomUUID() + ".png";
                    try (FileOutputStream fo = new FileOutputStream(new File(mediaDir(), name))) {
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

    // ---------- التنقل والعرض ----------

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

    private String pathOf(String id) {
        StringBuilder sb = new StringBuilder();
        String c = id;
        int guard = 0;
        while (!c.isEmpty() && guard++ < 100) {
            Folder f = find(c);
            if (f == null) break;
            sb.insert(0, " > " + f.name);
            c = f.parent;
        }
        return "Racine" + sb;
    }

    private boolean typeVisible(String type) {
        if (T_NOTE.equals(type)) return showNote;
        if (T_PHOTO.equals(type)) return showPhoto;
        if (T_VIDEO.equals(type)) return showVideo;
        return showDraw;
    }

    private Row folderRow(Folder f, boolean withPath) {
        Row r = new Row();
        r.folder = f;
        SpannableString s = new SpannableString(f.name);
        if (f.done) {
            s.setSpan(new StrikethroughSpan(), 0, f.name.length(),
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }
        r.title = s;
        if (withPath) {
            r.sub = "Dans : " + pathOf(f.parent);
        } else if (depth(f.id) >= maxLevels) {
            int n = countItems(f.id);
            r.sub = n == 0 ? "Vide" : n + (n > 1 ? " éléments" : " élément");
        } else {
            int n = countChildren(f.id);
            r.sub = n == 0 ? "Vide" : n + (n > 1 ? " sous-dossiers" : " sous-dossier");
        }
        return r;
    }

    private Row itemRow(Item it, boolean withPath) {
        Row r = new Row();
        r.item = it;
        if (T_NOTE.equals(it.type)) r.title = it.data;
        else if (T_PHOTO.equals(it.type)) r.title = "Photo";
        else if (T_VIDEO.equals(it.type)) r.title = "Vidéo";
        else r.title = "Dessin";
        if (withPath) r.sub = "Dans : " + pathOf(it.folder);
        return r;
    }

    private void refresh() {
        int d = depth(currentId);
        contentMode = viewMode == MODE_NORMAL && !currentId.isEmpty() && d >= maxLevels;
        rows.clear();

        if (viewMode == MODE_NORMAL) {
            if (contentMode) {
                int pc = 0, vc = 0, dc = 0;
                for (Item it : items) {
                    if (!it.folder.equals(currentId) || !typeVisible(it.type)) continue;
                    Row r = itemRow(it, false);
                    if (T_PHOTO.equals(it.type)) r.title = "Photo " + (++pc);
                    else if (T_VIDEO.equals(it.type)) r.title = "Vidéo " + (++vc);
                    else if (T_DRAW.equals(it.type)) r.title = "Dessin " + (++dc);
                    rows.add(r);
                }
            } else {
                for (Folder f : all) {
                    if (f.parent.equals(currentId)) rows.add(folderRow(f, false));
                }
            }
        } else if (viewMode == MODE_FAV) {
            for (Folder f : all) if (f.fav) rows.add(folderRow(f, true));
            for (Item it : items) {
                if (it.fav && typeVisible(it.type)) rows.add(itemRow(it, true));
            }
        } else {
            String q = query.toLowerCase();
            for (Folder f : all) {
                if (f.name.toLowerCase().contains(q)) rows.add(folderRow(f, true));
            }
            if (showNote) {
                for (Item it : items) {
                    if (T_NOTE.equals(it.type) && it.data.toLowerCase().contains(q)) {
                        rows.add(itemRow(it, true));
                    }
                }
            }
        }
        adapter.notifyDataSetChanged();

        String header;
        if (viewMode == MODE_FAV) {
            header = "Favoris";
        } else if (viewMode == MODE_SEARCH) {
            header = "Résultats pour « " + query + " »";
        } else {
            header = pathOf(currentId) + "\nNiveau " + d + " / " + maxLevels;
            if (contentMode) header += "\nContenu de ce dossier";
        }
        if (viewMode != MODE_NORMAL && rows.isEmpty()) header += "\nAucun résultat";
        pathView.setText(header);

        boolean normal = viewMode == MODE_NORMAL;
        boolean inputVisible = normal && (!contentMode || showNote);
        input.setVisibility(inputVisible ? View.VISIBLE : View.GONE);
        addBtn.setVisibility(inputVisible ? View.VISIBLE : View.GONE);
        input.setHint(contentMode ? "Nouvelle note" : "Nom du dossier");
        addBtn.setText(contentMode ? "Ajouter la note" : "Créer le dossier");

        mediaRow.setVisibility(normal && contentMode ? View.VISIBLE : View.GONE);
        photoBtn.setVisibility(showPhoto ? View.VISIBLE : View.GONE);
        videoBtn.setVisibility(showVideo ? View.VISIBLE : View.GONE);
        drawBtn.setVisibility(showDraw ? View.VISIBLE : View.GONE);

        backBtn.setVisibility((!normal || !currentId.isEmpty()) ? View.VISIBLE : View.GONE);
    }

    // ---------- الحفظ والتحميل ----------

    private JSONArray treeJson() throws Exception {
        JSONArray arr = new JSONArray();
        for (Folder f : all) {
            JSONObject o = new JSONObject();
            o.put("id", f.id);
            o.put("name", f.name);
            o.put("parent", f.parent);
            o.put("done", f.done);
            o.put("fav", f.fav);
            o.put("color", f.color);
            o.put("icon", f.icon);
            o.put("cover", f.cover);
            arr.put(o);
        }
        return arr;
    }

    private JSONArray itemsJson() throws Exception {
        JSONArray arr = new JSONArray();
        for (Item it : items) {
            JSONObject o = new JSONObject();
            o.put("id", it.id);
            o.put("folder", it.folder);
            o.put("type", it.type);
            o.put("data", it.data);
            o.put("fav", it.fav);
            arr.put(o);
        }
        return arr;
    }

    private ArrayList<Folder> parseTree(JSONArray arr) throws Exception {
        ArrayList<Folder> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Folder f = new Folder(o.getString("id"), o.getString("name"), o.getString("parent"));
            f.done = o.optBoolean("done", false);
            f.fav = o.optBoolean("fav", false);
            f.color = o.optInt("color", 0);
            f.icon = o.optString("icon", DEFAULT_ICON);
            f.cover = o.optString("cover", "");
            out.add(f);
        }
        return out;
    }

    private ArrayList<Item> parseItems(JSONArray arr) throws Exception {
        ArrayList<Item> out = new ArrayList<>();
        for (int i = 0; i < arr.length(); i++) {
            JSONObject o = arr.getJSONObject(i);
            Item it = new Item(o.getString("id"), o.getString("folder"),
                    o.getString("type"), o.getString("data"));
            it.fav = o.optBoolean("fav", false);
            out.add(it);
        }
        return out;
    }

    private void load() {
        try {
            all.addAll(parseTree(new JSONArray(prefs.getString("tree", "[]"))));
        } catch (Exception e) {
            // بيانات تالفة: نبدأ فارغين
        }
        try {
            String raw = prefs.getString("items", null);
            if (raw != null) {
                items.addAll(parseItems(new JSONArray(raw)));
            } else {
                // الملاحظات القديمة من خطوة سابقة
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
            prefs.edit()
                    .putString("tree", treeJson().toString())
                    .putString("items", itemsJson().toString())
                    .apply();
        } catch (Exception e) {
            // تجاهل
        }
    }
}
