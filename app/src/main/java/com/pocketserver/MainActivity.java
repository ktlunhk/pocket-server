
package com.pocketserver;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.Handler;
import android.provider.DocumentsContract;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final int REQ_FOLDER = 42;

    private EditText webPort;
    private EditText dbPort;
    private TextView docRoot;
    private TextView webStatus;
    private TextView dbStatus;
    private TextView logView;
    private ScrollView scroll;
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
        webPort = addServerRow(root, ServerService.ACTION_START_WEB, ServerService.ACTION_STOP_WEB,
                sp.getInt("web_port", 8080));

        dbStatus = statusView();
        root.addView(dbStatus);
        dbPort = addServerRow(root, ServerService.ACTION_START_DB, ServerService.ACTION_STOP_DB,
                sp.getInt("db_port", 3306));

        root.addView(label("Document root"));
        String savedRoot = sp.getString("doc_root", null);
        if (savedRoot == null || savedRoot.equals(new File(getFilesDir(), "www").getAbsolutePath())) {
            savedRoot = defaultRoot();
        }
        docRoot = new TextView(this);
        docRoot.setTextSize(14);
        docRoot.setTextIsSelectable(true);
        docRoot.setPadding(0, dp(4), 0, dp(4));
        docRoot.setText(savedRoot);
        LinearLayout rootRow = new LinearLayout(this);
        rootRow.setOrientation(LinearLayout.HORIZONTAL);
        rootRow.setGravity(Gravity.CENTER_VERTICAL);
        rootRow.addView(docRoot, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button pick = smallButton("Choose folder");
        pick.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);
                startActivityForResult(i, REQ_FOLDER);
            }
        });
        rootRow.addView(pick, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        root.addView(rootRow);

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

        if (Build.VERSION.SDK_INT >= 23) {
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
    protected void onPause() {
        super.onPause();
        handler.removeCallbacks(ticker);
    }

    private EditText addServerRow(LinearLayout root, final String startAction, final String stopAction, int port) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);

        EditText field = new EditText(this);
        field.setInputType(InputType.TYPE_CLASS_NUMBER);
        field.setSingleLine(true);
        field.setText(String.valueOf(port));
        row.addView(field, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1));

        Button start = new Button(this);
        start.setText("Start");
        start.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                savePrefs();
                sendAction(startAction, true);
            }
        });
        row.addView(start, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        Button stop = new Button(this);
        stop.setText("Stop");
        stop.setOnClickListener(new View.OnClickListener() {
            public void onClick(View v) {
                sendAction(stopAction, false);
            }
        });
        row.addView(stop, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));

        root.addView(row);
        return field;
    }

    private void savePrefs() {
        SharedPreferences.Editor e = sp.edit();
        e.putInt("web_port", parsePort(webPort, 8080));
        e.putInt("db_port", parsePort(dbPort, 3306));
        e.putString("doc_root", docRoot.getText().toString().trim());
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

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_FOLDER && resultCode == RESULT_OK && data != null) {
            String path = pathFromTreeUri(data.getData());
            if (path == null) {
                Toast.makeText(this, "Please pick a folder on internal storage or an SD card", Toast.LENGTH_LONG).show();
            } else {
                docRoot.setText(path);
                sp.edit().putString("doc_root", path).commit();
                if (ServerService.webRunning) {
                    Toast.makeText(this, "Restart the web server to use the new folder", Toast.LENGTH_LONG).show();
                }
            }
        }
    }

    private String pathFromTreeUri(Uri uri) {
        if (uri == null || !"com.android.externalstorage.documents".equals(uri.getAuthority())) {
            return null;
        }
        String id = DocumentsContract.getTreeDocumentId(uri);
        int colon = id.indexOf(':');
        if (colon < 0) {
            return null;
        }
        String vol = id.substring(0, colon);
        String rel = id.substring(colon + 1);
        String base;
        if (vol.equals("primary")) {
            base = Environment.getExternalStorageDirectory().getAbsolutePath();
        } else {
            base = "/storage/" + vol;
        }
        if (rel.length() > 0) {
            return base + "/" + rel;
        }
        return base;
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
        String text = ServerService.getLog();
        if (!text.equals(logView.getText().toString())) {
            logView.setText(text);
            scroll.post(new Runnable() {
                public void run() {
                    scroll.fullScroll(View.FOCUS_DOWN);
                }
            });
        }
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
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private TextView label(String s) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setPadding(0, dp(8), 0, 0);
        return t;
    }

    private Button smallButton(String text) {
        Button b = new Button(this);
        b.setText(text);
        b.setTextSize(12);
        return b;
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density);
    }
}
