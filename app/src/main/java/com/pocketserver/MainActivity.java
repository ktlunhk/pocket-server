
package com.pocketserver;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.text.InputType;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.AdapterView;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_FOLDER = 42;
    private static final int REQ_DB_FOLDER = 43;

    private EditText webPort;
    private EditText dbPort;
    private EditText httpsPort;
    private CheckBox httpsCheck;
    private TextView docRoot;
    private TextView dbDir;
    private TextView webStatus;
    private TextView dbStatus;
    private Button webStart, webStop, dbStart, dbStop;
    private TextView logView;
    private ScrollView scroll;
    private AlertDialog pickerDialog;
    private AlertDialog promptDialog;
    private AlertDialog accessDialog;
    private SharedPreferences sp;
    private final Handler handler = new Handler();

    private final Runnable ticker = new Runnable() {
        public void run() {
            refresh();
            handler.postDelayed(this, 1000);
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        sp = getSharedPreferences("cfg", MODE_PRIVATE);

        int pad = dp(12);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(pad, pad, pad, pad);

        webStatus = statusView();
        root.addView(webStatus);
        webPort = addPortField(root, "HTTP Port", sp.getInt("web_port", 8080));

        LinearLayout sslRow = new LinearLayout(this);
        sslRow.setOrientation(LinearLayout.HORIZONTAL);
        sslRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView sslLabel = new TextView(this);
        sslLabel.setText("HTTPS Port");
        sslLabel.setPadding(dp(4), 0, dp(12), 0);
        sslRow.addView(sslLabel, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        httpsPort = new EditText(this);
        httpsPort.setInputType(InputType.TYPE_CLASS_NUMBER);
        httpsPort.setSingleLine(true);
        httpsPort.setText(String.valueOf(sp.getInt("https_port", 8443)));
        httpsPort.setTextSize(14);
        httpsPort.setTextColor(textColor());
        httpsPort.setPadding(httpsPort.getPaddingLeft(), dp(4), httpsPort.getPaddingRight(), dp(4));
        sslRow.addView(httpsPort, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        httpsCheck = new CheckBox(this);
        httpsCheck.setText("Enable");
        httpsCheck.setTextSize(14);
        httpsCheck.setChecked(sp.getBoolean("https_on", false));
        sslRow.addView(httpsCheck, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(sslRow);
        Button[] wb = addButtonRow(root, ServerService.ACTION_START_WEB, ServerService.ACTION_STOP_WEB);
        webStart = wb[0];
        webStop = wb[1];

        dbStatus = statusView();
        root.addView(dbStatus);
        dbPort = addPortField(root, "DB Port", sp.getInt("db_port", 3306));
        Button[] db = addButtonRow(root, ServerService.ACTION_START_DB, ServerService.ACTION_STOP_DB);
        dbStart = db[0];
        dbStop = db[1];

        root.addView(label("Document root"));
        String savedRoot = sp.getString("doc_root", null);
        if (savedRoot == null || savedRoot.equals(new File(getFilesDir(), "www").getAbsolutePath())) {
            savedRoot = defaultRoot();
        }
        docRoot = new TextView(this);
        docRoot.setTextSize(14);
        docRoot.setTextColor(textColor());
        docRoot.setTextIsSelectable(true);
        docRoot.setPadding(0, dp(2), 0, dp(2));
        docRoot.setText(savedRoot);
        LinearLayout rootRow = new LinearLayout(this);
        rootRow.setOrientation(LinearLayout.HORIZONTAL);
        rootRow.setGravity(Gravity.CENTER_VERTICAL);
        rootRow.addView(docRoot, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button pick = smallButton("Choose folder");
        pick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showFolderPicker(REQ_FOLDER);
            }
        });
        rootRow.addView(pick, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(rootRow);

        root.addView(label("Database folder"));
        String savedDb = sp.getString("db_dir", null);
        if (savedDb == null) {
            savedDb = Installer.defaultDbDir(this).getAbsolutePath();
        }
        dbDir = new TextView(this);
        dbDir.setTextSize(14);
        dbDir.setTextColor(textColor());
        dbDir.setTextIsSelectable(true);
        dbDir.setPadding(0, dp(2), 0, dp(2));
        dbDir.setText(savedDb);
        LinearLayout dbRow = new LinearLayout(this);
        dbRow.setOrientation(LinearLayout.HORIZONTAL);
        dbRow.setGravity(Gravity.CENTER_VERTICAL);
        dbRow.addView(dbDir, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button dbPick = smallButton("Choose folder");
        dbPick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showFolderPicker(REQ_DB_FOLDER);
            }
        });
        dbRow.addView(dbPick, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button dbApp = smallButton("App storage");
        dbApp.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                applyFolder(REQ_DB_FOLDER, new File(getFilesDir(), "data").getAbsolutePath());
            }
        });
        dbRow.addView(dbApp, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(dbRow);

        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button save = smallButton("Save log");
        save.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                saveLog();
            }
        });
        row.addView(save, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button clear = smallButton("Clear log");
        clear.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                ServerService.clearLog();
                logView.setText("");
                Toast.makeText(MainActivity.this, "Log cleared", Toast.LENGTH_SHORT).show();
            }
        });
        row.addView(clear, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        root.addView(row);

        scroll = new ScrollView(this);
        logView = new TextView(this);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setTextSize(11);
        logView.setTextIsSelectable(true);
        scroll.addView(logView);
        root.addView(scroll, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);

        if (Build.VERSION.SDK_INT >= 30) {
            if (!hasAllFilesAccess()) {
                showAllFilesPrompt();
            }
        } else if (Build.VERSION.SDK_INT >= 23) {
            requestPermissions(new String[] {
                    Manifest.permission.READ_EXTERNAL_STORAGE,
                    Manifest.permission.WRITE_EXTERNAL_STORAGE }, 1);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        handler.post(ticker);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        dismissQuietly(pickerDialog);
        dismissQuietly(promptDialog);
        dismissQuietly(accessDialog);
    }

    private void dismissQuietly(AlertDialog d) {
        if (d != null && d.isShowing()) {
            d.dismiss();
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
        savePrefs();
    }

    private EditText addPortField(LinearLayout root, String labelText, int port) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        TextView tv = new TextView(this);
        tv.setText(labelText);
        tv.setPadding(dp(4), 0, dp(12), 0);
        row.addView(tv, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSingleLine(true);
        field.setText(String.valueOf(port));
        field.setTextSize(14);
        field.setTextColor(textColor());
        field.setPadding(field.getPaddingLeft(), dp(4), field.getPaddingRight(), dp(4));
        row.addView(field, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        root.addView(row);
        return field;
    }

    private Button[] addButtonRow(LinearLayout root, final String startAction, final String stopAction) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        Button start = new Button(this);
        start.setText("Start");
        start.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                savePrefs();
                sendAction(startAction, true);
            }
        });
        row.addView(start, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sendAction(stopAction, false);
            }
        });
        row.addView(stop, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        compact(start);
        compact(stop);
        root.addView(row);
        return new Button[] { start, stop };
    }

    private void compact(Button b) {
        b.setTextSize(11);
        b.setMinHeight(dp(44));
        b.setMinimumHeight(dp(44));
        b.setPadding(dp(8), dp(8), dp(8), dp(8));
    }

    private void setFieldEnabled(View v, boolean enabled) {
        v.setEnabled(enabled);
        v.setAlpha(enabled ? 1f : 0.5f);
    }

    private void savePrefs() {
        SharedPreferences.Editor e = sp.edit();
        e.putInt("web_port", parsePort(webPort, 8080));
        e.putInt("db_port", parsePort(dbPort, 3306));
        e.putBoolean("https_on", httpsCheck.isChecked());
        e.putInt("https_port", parsePort(httpsPort, 8443));
        e.putString("doc_root", docRoot.getText().toString().trim());
        e.putString("db_dir", dbDir.getText().toString().trim());
        e.commit();
    }

    private void sendAction(String action, boolean foreground) {
        Intent i = new Intent(this, ServerService.class);
        i.setAction(action);
        if (foreground && Build.VERSION.SDK_INT >= 26) {
            startForegroundService(i);
        } else {
            startService(i);
        }
    }

    private String defaultRoot() {
        return new File(Environment.getExternalStorageDirectory(), "PocketServer/www").getAbsolutePath();
    }

    private void applyFolder(int requestCode, String path) {
        if (requestCode == REQ_FOLDER) {
            docRoot.setText(path);
            sp.edit().putString("doc_root", path).commit();
            if (ServerService.webRunning) {
                Toast.makeText(this, "Restart the web server to use the new folder", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Document root set", Toast.LENGTH_SHORT).show();
            }
        } else {
            dbDir.setText(path);
            sp.edit().putString("db_dir", path).commit();
            if (ServerService.dbRunning) {
                Toast.makeText(this, "Restart the database to use the new folder", Toast.LENGTH_LONG).show();
            } else {
                Toast.makeText(this, "Database folder set", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showFolderPicker(final int requestCode) {
        TextView target = requestCode == REQ_FOLDER ? docRoot : dbDir;
        File start = new File(target.getText().toString().trim());
        while (start != null && !start.isDirectory()) {
            start = start.getParentFile();
        }
        if (start == null) {
            start = Environment.getExternalStorageDirectory();
        }
        final String[] cur = { start.getAbsolutePath() };
        final int blue = 0xFF1A73E8;

        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(12), dp(12), 0);

        final LinearLayout titleRow = new LinearLayout(this);
        titleRow.setPadding(0, dp(8), 0, dp(8));
        titleRow.setOrientation(LinearLayout.HORIZONTAL);
        titleRow.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(this);
        title.setText(requestCode == REQ_FOLDER ? "Choose folder - Document root" : "Choose folder - Database folder");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        titleRow.addView(title);
        content.addView(titleRow);

        LinearLayout pathRow = new LinearLayout(this);
        pathRow.setOrientation(LinearLayout.HORIZONTAL);
        pathRow.setGravity(Gravity.CENTER_VERTICAL);
        pathRow.setPadding(0, dp(8), 0, dp(4));
        IconView home = new IconView(this, IconView.HOME, blue);
        pathRow.addView(home, new LinearLayout.LayoutParams(dp(24), dp(24)));
        final TextView pathText = new TextView(this);
        pathText.setTextSize(14);
        pathText.setSingleLine(true);
        pathText.setEllipsize(TextUtils.TruncateAt.START);
        pathText.setPadding(dp(8), 0, dp(8), 0);
        pathRow.addView(pathText, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        IconView newIcon = new IconView(this, IconView.NEWFOLDER, blue);
        LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(dp(24), dp(24));
        nlp.rightMargin = dp(12);
        pathRow.addView(newIcon, nlp);
        final IconView up = new IconView(this, IconView.UP, blue);
        pathRow.addView(up, new LinearLayout.LayoutParams(dp(24), dp(24)));
        content.addView(pathRow);

        final ListView list = new ListView(this);
        list.setDivider(null);
        final TextView msg = new TextView(this);
        msg.setTextSize(14);
        msg.setVisibility(View.GONE);
        final Button ok = new Button(this);
        ok.setText("Select this folder");
        compact(ok);
        final ArrayList<File> items = new ArrayList<File>();
        final BaseAdapter adapter = new BaseAdapter() {
            public int getCount() {
                return items.size();
            }

            public Object getItem(int position) {
                return items.get(position);
            }

            public long getItemId(int position) {
                return position;
            }

            public View getView(int position, View convertView, ViewGroup parent) {
                LinearLayout row;
                if (convertView == null) {
                    row = new LinearLayout(MainActivity.this);
                    row.setOrientation(LinearLayout.HORIZONTAL);
                    row.setGravity(Gravity.CENTER_VERTICAL);
                    row.setPadding(dp(4), dp(6), dp(4), dp(6));
                    row.addView(new IconView(MainActivity.this, IconView.FOLDER, blue),
                            new LinearLayout.LayoutParams(dp(24), dp(24)));
                    TextView t = new TextView(MainActivity.this);
                    t.setTextSize(14);
                    t.setSingleLine(true);
                    t.setPadding(dp(8), 0, 0, 0);
                    row.addView(t);
                } else {
                    row = (LinearLayout) convertView;
                }
                ((TextView) row.getChildAt(1)).setText(items.get(position).getName());
                return row;
            }
        };
        list.setAdapter(adapter);

        final Runnable[] reload = new Runnable[1];
        reload[0] = new Runnable() {
            public void run() {
                pathText.setText(cur[0]);
                items.clear();
                File[] fs = new File(cur[0]).listFiles();
                ok.setEnabled(fs != null);
                if (fs == null) {
                    msg.setText("Can't read this folder");
                    msg.setVisibility(View.VISIBLE);
                } else {
                    msg.setVisibility(View.GONE);
                }
                if (fs != null) {
                    for (int i = 0; i < fs.length; i++) {
                        if (fs[i].isDirectory()) {
                            items.add(fs[i]);
                        }
                    }
                    Collections.sort(items, new Comparator<File>() {
                        public int compare(File a, File b) {
                            return a.getName().compareToIgnoreCase(b.getName());
                        }
                    });
                }
                if (fs != null && items.size() == 0) {
                    msg.setText("No subfolders");
                    msg.setVisibility(View.VISIBLE);
                }
                adapter.notifyDataSetChanged();
                list.setSelection(0);
            }
        };
        reload[0].run();

        list.setOnItemClickListener(new AdapterView.OnItemClickListener() {
            public void onItemClick(AdapterView<?> parent, View view, int position, long id) {
                cur[0] = items.get(position).getAbsolutePath();
                reload[0].run();
            }
        });
        home.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                cur[0] = Environment.getExternalStorageDirectory().getAbsolutePath();
                reload[0].run();
            }
        });
        newIcon.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                showNewFolderPrompt(new File(cur[0]), reload[0]);
            }
        });
        up.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                File par = new File(cur[0]).getParentFile();
                if (par != null && par.getAbsolutePath().startsWith("/storage")) {
                    cur[0] = par.getAbsolutePath();
                    reload[0].run();
                }
            }
        });

        int h = getResources().getDisplayMetrics().heightPixels * 30 / 100;
        FrameLayout frame = new FrameLayout(this);
        frame.addView(list, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT));
        frame.addView(msg, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, FrameLayout.LayoutParams.WRAP_CONTENT, Gravity.CENTER));
        content.addView(frame, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, h));

        final AlertDialog dialog = new AlertDialog.Builder(this).setView(content).create();
        pickerDialog = dialog;

        final float[] last = new float[2];
        titleRow.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent e) {
                int action = e.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    last[0] = e.getRawX();
                    last[1] = e.getRawY();
                } else if (action == MotionEvent.ACTION_MOVE) {
                    WindowManager.LayoutParams lp = dialog.getWindow().getAttributes();
                    android.util.DisplayMetrics dm = getResources().getDisplayMetrics();
                    View decor = dialog.getWindow().getDecorView();
                    int maxX = Math.max(0, (dm.widthPixels - decor.getWidth()) / 2);
                    int maxY = Math.max(0, (dm.heightPixels - decor.getHeight()) / 2);
                    lp.x = Math.max(-maxX, Math.min(maxX, lp.x + (int) (e.getRawX() - last[0])));
                    lp.y = Math.max(-maxY, Math.min(maxY, lp.y + (int) (e.getRawY() - last[1])));
                    last[0] = e.getRawX();
                    last[1] = e.getRawY();
                    dialog.getWindow().setAttributes(lp);
                }
                return true;
            }
        });

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(4), 0, dp(8));

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        compact(cancel);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dialog.dismiss();
            }
        });
        btnRow.addView(cancel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        ok.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                applyFolder(requestCode, cur[0]);
                dialog.dismiss();
            }
        });
        btnRow.addView(ok, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        content.addView(btnRow);

        dialog.setOnKeyListener(new DialogInterface.OnKeyListener() {
            public boolean onKey(DialogInterface d, int keyCode, KeyEvent event) {
                if (keyCode == KeyEvent.KEYCODE_BACK && event.getAction() == KeyEvent.ACTION_UP) {
                    File par = new File(cur[0]).getParentFile();
                    if (par != null && par.getAbsolutePath().startsWith("/storage")) {
                        up.performClick();
                        return true;
                    }
                }
                return false;
            }
        });

        dialog.show();
    }

    private void showNewFolderPrompt(final File parent, final Runnable onDone) {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(12), dp(12), dp(8));

        TextView title = new TextView(this);
        title.setText("New folder");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        final EditText name = new EditText(this);
        name.setSingleLine(true);
        name.setTextSize(14);
        name.setTextColor(textColor());
        name.setPadding(name.getPaddingLeft(), dp(8), name.getPaddingRight(), dp(8));
        content.addView(name);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        btnRow.setPadding(0, dp(4), 0, 0);
        final AlertDialog dlg = new AlertDialog.Builder(this).setView(content).create();
        promptDialog = dlg;

        Button cancel = new Button(this);
        cancel.setText("Cancel");
        compact(cancel);
        cancel.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        btnRow.addView(cancel, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button create = new Button(this);
        create.setText("Create");
        compact(create);
        create.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                String n = name.getText().toString().trim();
                if (n.length() == 0 || n.indexOf('/') >= 0) {
                    Toast.makeText(MainActivity.this, "Enter a valid folder name", Toast.LENGTH_SHORT).show();
                    return;
                }
                File f = new File(parent, n);
                if (f.exists() || !f.mkdirs()) {
                    Toast.makeText(MainActivity.this, "Could not create folder", Toast.LENGTH_SHORT).show();
                    return;
                }
                dlg.dismiss();
                onDone.run();
            }
        });
        btnRow.addView(create, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        content.addView(btnRow);
        dlg.show();
    }

    private static class IconView extends View {
        static final int FOLDER = 0;
        static final int HOME = 1;
        static final int UP = 2;
        static final int NEWFOLDER = 3;
        private final int type;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();

        IconView(Context c, int type, int color) {
            super(c);
            this.type = type;
            paint.setColor(color);
            paint.setStrokeJoin(Paint.Join.ROUND);
            paint.setStrokeCap(Paint.Cap.ROUND);
        }

        @Override
        protected void onDraw(Canvas canvas) {
            float w = getWidth();
            float h = getHeight();
            path.reset();
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(w * 0.09f);
            if (type == FOLDER || type == NEWFOLDER) {
                path.moveTo(w * 0.08f, h * 0.2f);
                path.lineTo(w * 0.4f, h * 0.2f);
                path.lineTo(w * 0.5f, h * 0.32f);
                path.lineTo(w * 0.92f, h * 0.32f);
                path.lineTo(w * 0.92f, h * 0.8f);
                path.lineTo(w * 0.08f, h * 0.8f);
                path.close();
                if (type == NEWFOLDER) {
                    path.moveTo(w * 0.5f, h * 0.42f);
                    path.lineTo(w * 0.5f, h * 0.7f);
                    path.moveTo(w * 0.36f, h * 0.56f);
                    path.lineTo(w * 0.64f, h * 0.56f);
                }
            } else if (type == HOME) {
                paint.setStyle(Paint.Style.FILL);
                path.moveTo(w * 0.5f, h * 0.1f);
                path.lineTo(w * 0.05f, h * 0.52f);
                path.lineTo(w * 0.2f, h * 0.52f);
                path.lineTo(w * 0.2f, h * 0.9f);
                path.lineTo(w * 0.42f, h * 0.9f);
                path.lineTo(w * 0.42f, h * 0.62f);
                path.lineTo(w * 0.58f, h * 0.62f);
                path.lineTo(w * 0.58f, h * 0.9f);
                path.lineTo(w * 0.8f, h * 0.9f);
                path.lineTo(w * 0.8f, h * 0.52f);
                path.lineTo(w * 0.95f, h * 0.52f);
                path.close();
            } else {
                paint.setStrokeWidth(w * 0.12f);
                path.moveTo(w * 0.25f, h * 0.85f);
                path.lineTo(w * 0.25f, h * 0.6f);
                path.quadTo(w * 0.25f, h * 0.4f, w * 0.5f, h * 0.4f);
                path.lineTo(w * 0.75f, h * 0.4f);
                path.moveTo(w * 0.5f, h * 0.15f);
                path.lineTo(w * 0.75f, h * 0.4f);
                path.lineTo(w * 0.5f, h * 0.65f);
            }
            canvas.drawPath(path, paint);
        }
    }

    private void saveLog() {
        String text = ServerService.getLog();
        if (text.length() == 0) {
            Toast.makeText(this, "Log is empty", Toast.LENGTH_SHORT).show();
            return;
        }
        String name = "pocketserver_log_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
        File dir = new File(Environment.getExternalStorageDirectory(), "PocketServer");
        File out = tryWrite(dir, name, text);
        if (out == null) {
            out = tryWrite(getExternalFilesDir(null), name, text);
        }
        if (out != null) {
            Toast.makeText(this, "Saved: " + out.getAbsolutePath(), Toast.LENGTH_LONG).show();
        } else {
            Toast.makeText(this, "Could not save log. Allow storage permission and try again.", Toast.LENGTH_LONG).show();
        }
    }

    private File tryWrite(File dir, String name, String text) {
        if (dir == null) {
            return null;
        }
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return null;
            }
            File f = new File(dir, name);
            FileWriter w = new FileWriter(f);
            w.write(text);
            w.close();
            return f;
        } catch (IOException e) {
            return null;
        }
    }

    private void refresh() {
        setStatus(webStatus, "Web server (HTTP + PHP)", ServerService.webRunning);
        setStatus(dbStatus, "Database (MariaDB)", ServerService.dbRunning);
        webStart.setEnabled(!ServerService.webRunning);
        webStop.setEnabled(ServerService.webRunning);
        dbStart.setEnabled(!ServerService.dbRunning);
        dbStop.setEnabled(ServerService.dbRunning);
        setFieldEnabled(webPort, !ServerService.webRunning);
        setFieldEnabled(httpsPort, !ServerService.webRunning);
        setFieldEnabled(httpsCheck, !ServerService.webRunning);
        setFieldEnabled(dbPort, !ServerService.dbRunning);
        String text = ServerService.getLog();
        if (!text.equals(logView.getText().toString())
                && logView.getSelectionStart() == logView.getSelectionEnd()) {
            View child = scroll.getChildAt(0);
            boolean atBottom = child == null
                    || child.getBottom() - (scroll.getScrollY() + scroll.getHeight()) <= dp(24);
            logView.setText(text);
            if (atBottom) {
                scroll.post(new Runnable() {
                    public void run() {
                        scroll.fullScroll(View.FOCUS_DOWN);
                    }
                });
            }
        }
    }

    private boolean hasAllFilesAccess() {
        try {
            Object r = Environment.class.getMethod("isExternalStorageManager").invoke(null);
            return Boolean.TRUE.equals(r);
        } catch (Exception e) {
            return true;
        }
    }

    private void showAllFilesPrompt() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(12), dp(12), dp(12), dp(8));

        TextView title = new TextView(this);
        title.setText("Allow file access");
        title.setTextSize(16);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        content.addView(title);

        TextView msgView = new TextView(this);
        msgView.setText("PocketServer needs \"All files access\" to read and write folders such as "
                + defaultRoot() + ". Tap Open settings and turn it on.");
        msgView.setTextSize(14);
        msgView.setPadding(0, dp(8), 0, dp(8));
        content.addView(msgView);

        LinearLayout btnRow = new LinearLayout(this);
        btnRow.setOrientation(LinearLayout.HORIZONTAL);
        final AlertDialog dlg = new AlertDialog.Builder(this).setView(content).create();
        accessDialog = dlg;

        Button later = new Button(this);
        later.setText("Later");
        compact(later);
        later.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
            }
        });
        btnRow.addView(later, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button open = new Button(this);
        open.setText("Open settings");
        compact(open);
        open.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                dlg.dismiss();
                try {
                    startActivity(new Intent("android.settings.MANAGE_APP_ALL_FILES_ACCESS_PERMISSION",
                            Uri.parse("package:" + getPackageName())));
                } catch (Exception e) {
                    try {
                        startActivity(new Intent("android.settings.MANAGE_ALL_FILES_ACCESS_PERMISSION"));
                    } catch (Exception e2) {
                        Toast.makeText(MainActivity.this, "Open Settings > Apps > PocketServer > Permissions", Toast.LENGTH_LONG).show();
                    }
                }
            }
        });
        btnRow.addView(open, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));
        content.addView(btnRow);
        dlg.show();
    }

    private int textColor() {
        int mode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        return mode == Configuration.UI_MODE_NIGHT_YES ? 0xFFEEEEEE : 0xFF000000;
    }

    private void setStatus(TextView tv, String name, boolean on) {
        tv.setText(name + ": " + (on ? "RUNNING" : "stopped"));
        tv.setTextColor(on ? 0xFF2E7D32 : 0xFF757575);
    }

    private int parsePort(EditText et, int def) {
        try {
            int p = Integer.parseInt(et.getText().toString().trim());
            if (p >= 1024 && p <= 65535) {
                return p;
            }
        } catch (NumberFormatException ex) {
            // fall through
        }
        return def;
    }

    private TextView statusView() {
        TextView t = new TextView(this);
        t.setTextSize(16);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(4), 0, 0);
        return t;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        compact(b);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
