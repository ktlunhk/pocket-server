package com.pocketserver;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Environment;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.Inet4Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class ServerService extends Service {

    public static final String ACTION_START_WEB = "com.pocketserver.START_WEB";
    public static final String ACTION_STOP_WEB = "com.pocketserver.STOP_WEB";
    public static final String ACTION_START_DB = "com.pocketserver.START_DB";
    public static final String ACTION_STOP_DB = "com.pocketserver.STOP_DB";
    private static final String CHANNEL = "servers";

    public static volatile boolean webRunning = false;
    public static volatile boolean dbRunning = false;
    private static final StringBuilder LOG = new StringBuilder();

    private static final int FCGI_PORT = 9100;

    private final Object setupLock = new Object();
    private volatile boolean dbExternal = false;

    private Process dbProc;
    private Process phpProc;
    private volatile boolean phpWanted = false;
    private Process webProc;
    private PowerManager.WakeLock wakeLock;

    public static synchronized void log(String s) {
        LOG.append(s).append("\n");
        if (LOG.length() > 30000) {
            LOG.delete(0, LOG.length() - 20000);
        }
    }

    public static synchronized void clearLog() {
        LOG.setLength(0);
    }

    public static synchronized String getLog() {
        return LOG.toString();
    }

    @Override
    public IBinder onBind(Intent i) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? null : intent.getAction();
        if (action == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        if (ACTION_START_WEB.equals(action)) {
            enterForeground();
            if (!webRunning) {
                webRunning = true;
                updateNotification();
                new Thread(new Runnable() {
                    public void run() {
                        startWeb();
                    }
                }).start();
            }
        } else if (ACTION_START_DB.equals(action)) {
            enterForeground();
            if (!dbRunning) {
                dbRunning = true;
                updateNotification();
                new Thread(new Runnable() {
                    public void run() {
                        startDb();
                    }
                }).start();
            }
        } else if (ACTION_STOP_WEB.equals(action)) {
            new Thread(new Runnable() {
                public void run() {
                    stopWeb();
                    finishIfIdle();
                }
            }).start();
        } else if (ACTION_STOP_DB.equals(action)) {
            new Thread(new Runnable() {
                public void run() {
                    stopDb();
                    finishIfIdle();
                }
            }).start();
        }
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        phpWanted = false;
        if (phpProc != null) {
            phpProc.destroy();
        }
        if (webProc != null) {
            webProc.destroy();
        }
        if (dbProc != null) {
            dbProc.destroy();
        }
        phpProc = null;
        webProc = null;
        dbProc = null;
        webRunning = false;
        dbRunning = false;
        if (wakeLock != null && wakeLock.isHeld()) {
            wakeLock.release();
        }
        super.onDestroy();
    }

    private void enterForeground() {
        startForeground(1, buildNotification());
        if (wakeLock == null) {
            PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "pocketserver:lock");
        }
        if (!wakeLock.isHeld()) {
            wakeLock.acquire();
        }
    }

    private void updateNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.notify(1, buildNotification());
    }

    private void finishIfIdle() {
        if (!webRunning && !dbRunning) {
            if (wakeLock != null && wakeLock.isHeld()) {
                wakeLock.release();
            }
            stopForeground(true);
            stopSelf();
        } else {
            updateNotification();
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            NotificationChannel ch = new NotificationChannel(CHANNEL, "Servers", NotificationManager.IMPORTANCE_LOW);
            nm.createNotificationChannel(ch);
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, 0);
        b.setContentTitle("Pocket Server");
        b.setContentText("Web: " + (webRunning ? "on" : "off") + "   Database: " + (dbRunning ? "on" : "off"));
        b.setSmallIcon(android.R.drawable.ic_dialog_info);
        b.setContentIntent(pi);
        return b.build();
    }

    private File prepareDocRoot(SharedPreferences sp) throws IOException {
        synchronized (setupLock) {
            Installer.install(this);
            File docRoot = new File(sp.getString("doc_root",
                    new File(Environment.getExternalStorageDirectory(), "PocketServer/www").getAbsolutePath()));
            Installer.copyDefaultSite(this, docRoot);
            if (!docRoot.isDirectory() || !docRoot.canRead()) {
                File fallback = new File(getFilesDir(), "www");
                log("Cannot use document root " + docRoot.getAbsolutePath() + " (allow storage permission). Using " + fallback.getAbsolutePath());
                docRoot = fallback;
                Installer.copyDefaultSite(this, docRoot);
            }
            log("Document root: " + docRoot.getAbsolutePath());
            return docRoot;
        }
    }

    private void prepareBase() throws IOException {
        synchronized (setupLock) {
            Installer.install(this);
        }
    }

    private void failWeb(String msg) {
        log(msg);
        phpWanted = false;
        Process ph = phpProc;
        phpProc = null;
        if (ph != null) {
            ph.destroy();
        }
        webRunning = false;
        finishIfIdle();
    }

    private void failDb(String msg) {
        log(msg);
        dbRunning = false;
        finishIfIdle();
    }

    private void startWeb() {
        try {
            SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
            int webPort = sp.getInt("web_port", 8080);
            int dbPort = sp.getInt("db_port", 3306);
            File docRoot = prepareDocRoot(sp);

            File base = Installer.baseDir(this);
            File bin = new File(base, "bin");
            File etc = new File(getFilesDir(), "etc");
            File tmp = new File(getCacheDir(), "tmp");
            File sock = new File(getFilesDir(), "mysql.sock");
            etc.mkdirs();
            tmp.mkdirs();

            File php = new File(bin, "php-cgi");
            File lighty = new File(bin, "lighttpd");
            File modDir = new File(base, "lib/lighttpd");

            if (!lighty.exists()) {
                failWeb("Missing binary: " + lighty.getAbsolutePath());
                return;
            }
            if (!portFree(webPort)) {
                failWeb("Port " + webPort + " is already in use. Change the web port and press Start again.");
                return;
            }

            boolean https = sp.getBoolean("https_on", false);
            int httpsPort = sp.getInt("https_port", 8443);
            File certFile = new File(etc, "ssl/cert.pem");
            File keyFile = new File(etc, "ssl/key.pem");
            if (https) {
                File modSsl = new File(modDir, "mod_openssl.so");
                if (!modSsl.exists()) {
                    log("HTTPS needs " + modSsl.getAbsolutePath() + ". HTTPS is turned off for this run.");
                    https = false;
                } else if (httpsPort == webPort || !portFree(httpsPort)) {
                    failWeb("HTTPS port " + httpsPort + " is already in use. Change it and press Start again.");
                    return;
                } else if (!ensureCertificate(sp, certFile, keyFile)) {
                    log("Could not create the HTTPS certificate. HTTPS is turned off for this run.");
                    https = false;
                }
            }

            writePhpIni(new File(etc, "php.ini"), sock, dbPort, base, tmp);
            writeLighttpdConf(new File(etc, "lighttpd.conf"), docRoot, webPort, FCGI_PORT, base,
                    https, httpsPort, certFile, keyFile);

            if (php.exists()) {
                selfTestPhp(php, base, tmp);
                if (!portFree(FCGI_PORT)) {
                    log("PHP port " + FCGI_PORT + " is already in use.");
                }
                startPhpWorker(php, base, tmp);
            } else {
                log("Missing binary: " + php.getAbsolutePath());
            }

            List<String> cmd = new ArrayList<String>();
            cmd.add(lighty.getAbsolutePath());
            cmd.add("-D");
            cmd.add("-m");
            cmd.add(modDir.getAbsolutePath());
            cmd.add("-f");
            cmd.add(new File(etc, "lighttpd.conf").getAbsolutePath());
            Process p = launch(cmd, "web", base, tmp);
            if (p == null) {
                failWeb("Could not start the web server");
                return;
            }
            webProc = p;
            log("Web server starting on port " + webPort);
            log("Open http://localhost:" + webPort + "/");
            if (https) {
                log("Open https://localhost:" + httpsPort + "/ (self-signed certificate, accept the browser warning)");
            }
            watchWeb(p);
            updateNotification();
        } catch (Exception e) {
            failWeb("Web error: " + e);
        }
    }

    private boolean ensureCertificate(SharedPreferences sp, File cert, File key) {
        try {
            List<String> ips = new ArrayList<String>();
            ips.add("127.0.0.1");
            try {
                Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
                while (nis != null && nis.hasMoreElements()) {
                    NetworkInterface ni = nis.nextElement();
                    Enumeration<InetAddress> addrs = ni.getInetAddresses();
                    while (addrs.hasMoreElements()) {
                        InetAddress a = addrs.nextElement();
                        if (a instanceof Inet4Address && !a.isLoopbackAddress()) {
                            ips.add(a.getHostAddress());
                        }
                    }
                }
            } catch (Exception e) {
                log("Could not list network addresses: " + e);
            }
            String stored = "," + sp.getString("ssl_ips", "") + ",";
            boolean covered = cert.exists() && key.exists();
            for (int i = 0; i < ips.size() && covered; i++) {
                if (!stored.contains("," + ips.get(i) + ",")) {
                    covered = false;
                }
            }
            if (covered) {
                return true;
            }
            StringBuilder joined = new StringBuilder();
            for (int i = 0; i < ips.size(); i++) {
                if (i > 0) {
                    joined.append(",");
                }
                joined.append(ips.get(i));
            }
            List<String> names = new ArrayList<String>();
            names.add("localhost");
            CertGen.generate(cert, key, names, ips);
            sp.edit().putString("ssl_ips", joined.toString()).commit();
            log("Created self-signed HTTPS certificate for localhost and " + joined);
            return true;
        } catch (Exception e) {
            log("Certificate error: " + e);
            return false;
        }
    }

    private File chooseDataDir(SharedPreferences sp) {
        File data = new File(sp.getString("db_dir", Installer.defaultDbDir(this).getAbsolutePath()));
        File docRoot = new File(sp.getString("doc_root",
                new File(Environment.getExternalStorageDirectory(), "PocketServer/www").getAbsolutePath()));
        if (isInside(data, docRoot)) {
            failDb("The database folder must not be inside the web document root (its files could be downloaded). Choose a different database folder.");
            return null;
        }
        if (isInside(docRoot, data)) {
            failDb("The database folder must not contain the web document root. Choose a different database folder.");
            return null;
        }
        if (!usableDir(data)) {
            File fallback = new File(getFilesDir(), "data");
            log("Cannot write to database folder " + data.getAbsolutePath() + " (allow storage permission). Using " + fallback.getAbsolutePath());
            fallback.mkdirs();
            data = fallback;
        }
        log("Database folder: " + data.getAbsolutePath());
        return data;
    }

    private boolean isInside(File child, File parent) {
        try {
            String c = child.getCanonicalPath();
            String p = parent.getCanonicalPath();
            return c.equals(p) || c.startsWith(p + "/");
        } catch (IOException e) {
            return false;
        }
    }

    private boolean usableDir(File dir) {
        try {
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File t = new File(dir, ".pocketserver_write_test");
            FileWriter w = new FileWriter(t);
            w.write("ok");
            w.close();
            return t.delete();
        } catch (IOException e) {
            return false;
        }
    }

    private void startDb() {
        try {
            SharedPreferences sp = getSharedPreferences("cfg", MODE_PRIVATE);
            int dbPort = sp.getInt("db_port", 3306);
            prepareBase();

            File base = Installer.baseDir(this);
            File bin = new File(base, "bin");
            File tmp = new File(getCacheDir(), "tmp");
            File sock = new File(getFilesDir(), "mysql.sock");
            tmp.mkdirs();
            File data = chooseDataDir(sp);
            if (data == null) {
                return;
            }
            dbExternal = !data.getAbsolutePath().startsWith(getFilesDir().getAbsolutePath());

            File mariadbd = new File(bin, "mariadbd");
            if (!mariadbd.exists()) {
                failDb("Missing binary: " + mariadbd.getAbsolutePath());
                return;
            }
            if (!portFree(dbPort)) {
                failDb("Port " + dbPort + " is already in use. Change the database port and press Start again.");
                return;
            }

            initDatabase(mariadbd, base, data, tmp);
            List<String> cmd = dbBaseArgs(mariadbd, base, data, tmp);
            cmd.add("--socket=" + sock.getAbsolutePath());
            cmd.add("--port=" + dbPort);
            cmd.add("--bind-address=0.0.0.0");
            Process p = launch(cmd, "mariadb", base, tmp);
            if (p == null) {
                failDb("Could not start MariaDB");
                return;
            }
            dbProc = p;
            log("MariaDB starting on port " + dbPort);
            watchDb(p);
            updateNotification();
        } catch (Exception e) {
            failDb("Database error: " + e);
        }
    }

    private void watchWeb(final Process p) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    p.waitFor();
                } catch (InterruptedException e) {
                    return;
                }
                if (webProc == p) {
                    log("Web server exited unexpectedly.");
                    stopWeb();
                    finishIfIdle();
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void watchDb(final Process p) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    p.waitFor();
                } catch (InterruptedException e) {
                    return;
                }
                if (dbProc == p) {
                    log("MariaDB exited unexpectedly.");
                    if (dbExternal) {
                        log("The database folder is on shared storage. If it keeps failing, press App storage under Database folder and start again.");
                    }
                    stopDb();
                    finishIfIdle();
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void selfTestPhp(File php, File base, File tmp) {
        log("PHP test: executable=" + php.canExecute() + " size=" + php.length());
        List<String> v = new ArrayList<String>();
        v.add(php.getAbsolutePath());
        v.add("-v");
        log("PHP -v: " + runOnce(v, base, tmp));
        List<String> m = new ArrayList<String>();
        m.add(php.getAbsolutePath());
        m.add("-m");
        log("PHP -m: " + runOnce(m, base, tmp));
    }

    private String runOnce(List<String> cmd, File base, File tmp) {
        StringBuilder sb = new StringBuilder();
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.environment().put("LD_LIBRARY_PATH", new File(base, "lib").getAbsolutePath());
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());
            pb.environment().put("TMPDIR", tmp.getAbsolutePath());
            pb.environment().put("PHPRC", new File(getFilesDir(), "etc").getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream()));
            String line;
            while ((line = r.readLine()) != null) {
                if (line.length() > 0 && !line.startsWith("[")) {
                    sb.append(line).append(" | ");
                } else if (line.startsWith("[PHP")) {
                    sb.append(line).append(" | ");
                }
            }
            p.waitFor();
            sb.append("exit=").append(p.exitValue());
        } catch (Exception e) {
            sb.append("FAILED: ").append(e.getMessage());
        }
        return sb.toString();
    }

    private boolean portFree(int port) {
        java.net.ServerSocket ss = null;
        try {
            ss = new java.net.ServerSocket();
            ss.setReuseAddress(true);
            ss.bind(new java.net.InetSocketAddress(port));
            return true;
        } catch (IOException e) {
            return false;
        } finally {
            if (ss != null) {
                try {
                    ss.close();
                } catch (IOException e2) {
                    // ignore
                }
            }
        }
    }

    private List<String> dbBaseArgs(File mariadbd, File base, File data, File tmp) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(mariadbd.getAbsolutePath());
        cmd.add("--no-defaults");
        cmd.add("--basedir=" + base.getAbsolutePath());
        cmd.add("--datadir=" + data.getAbsolutePath());
        cmd.add("--tmpdir=" + tmp.getAbsolutePath());
        File errDir = findErrmsgParent(new File(base, "share"));
        File msgDir = errDir != null ? errDir : new File(base, "share");
        cmd.add("--lc-messages-dir=" + msgDir.getAbsolutePath());
        File plugins = new File(base, "lib/mariadb/plugin");
        if (!plugins.exists()) {
            plugins = new File(base, "lib/mysql/plugin");
        }
        if (plugins.exists()) {
            cmd.add("--plugin-dir=" + plugins.getAbsolutePath());
        }
        if (!data.getAbsolutePath().startsWith(getFilesDir().getAbsolutePath())) {
            cmd.add("--loose-innodb-flush-method=fsync");
            cmd.add("--loose-innodb-use-native-aio=0");
        }
        return cmd;
    }

    private File findSql(File share, String newName, String oldName) {
        File f = findFile(share, newName);
        if (f == null) {
            f = findFile(share, oldName);
        }
        return f;
    }

    private void initDatabase(File mariadbd, File base, File data, File tmp) {
        if (new File(data, "mysql").exists()) {
            return;
        }
        File share = new File(base, "share");
        File sysTables = findSql(share, "mariadb_system_tables.sql", "mysql_system_tables.sql");
        File perfTables = findSql(share, "mariadb_performance_tables.sql", "mysql_performance_tables.sql");
        File sysData = findSql(share, "mariadb_system_tables_data.sql", "mysql_system_tables_data.sql");
        File help = findFile(share, "fill_help_tables.sql");
        File gis = findFile(share, "maria_add_gis_sp_bootstrap.sql");
        File sysSchema = findSql(share, "mariadb_sys_schema.sql", "mysql_sys_schema.sql");
        if (sysTables == null || sysData == null) {
            log("Cannot initialize database: system tables SQL not found under server/share");
            return;
        }
        log("Initializing database (first run), please wait...");
        try {
            List<String> cmd = dbBaseArgs(mariadbd, base, data, tmp);
            cmd.add("--bootstrap");
            cmd.add("--skip-networking");
            cmd.add("--log-warnings=0");
            cmd.add("--enforce-storage-engine=");
            cmd.add("--max_allowed_packet=8M");
            cmd.add("--net_buffer_length=16K");
            Process p = launch(cmd, "init", base, tmp);
            if (p == null) {
                return;
            }
            OutputStream out = p.getOutputStream();
            out.write("create database if not exists mysql;\nuse mysql;\nSET @auth_root_socket=NULL;\n".getBytes());
            pipeFile(sysTables, out);
            if (perfTables != null) {
                pipeFile(perfTables, out);
            }
            pipeFile(sysData, out);
            if (help != null) {
                pipeFile(help, out);
            }
            if (gis != null) {
                pipeFile(gis, out);
            }
            if (sysSchema != null) {
                pipeFile(sysSchema, out);
            }
            out.close();
            int code = p.waitFor();
            log("Database init finished, exit code " + code);
            if (code != 0) {
                log("Init failed. Delete the app data folder and try again.");
            }
        } catch (Exception e) {
            log("Database init failed: " + e);
        }
    }

    private void pipeFile(File f, OutputStream out) throws IOException {
        InputStream in = new FileInputStream(f);
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
        }
        in.close();
        out.write("\n".getBytes());
    }

    private File findFile(File dir, String name) {
        File[] list = dir.listFiles();
        if (list == null) {
            return null;
        }
        for (int i = 0; i < list.length; i++) {
            if (list[i].isDirectory()) {
                File r = findFile(list[i], name);
                if (r != null) {
                    return r;
                }
            } else if (list[i].getName().equals(name)) {
                return list[i];
            }
        }
        return null;
    }

    private File findErrmsgParent(File dir) {
        File[] list = dir.listFiles();
        if (list == null) {
            return null;
        }
        for (int i = 0; i < list.length; i++) {
            if (list[i].isDirectory()) {
                if (list[i].getName().equals("english") && new File(list[i], "errmsg.sys").exists()) {
                    return dir;
                }
                File r = findErrmsgParent(list[i]);
                if (r != null) {
                    return r;
                }
            }
        }
        return null;
    }

    private Process launch(List<String> cmd, String tag, File base, File tmp) {
        return launchWith(cmd, tag, base, tmp, null);
    }

    private void startPhpWorker(final File php, final File base, final File tmp) {
        phpWanted = true;
        Thread t = new Thread(new Runnable() {
            public void run() {
                while (phpWanted) {
                    List<String> cmd = new ArrayList<String>();
                    cmd.add(php.getAbsolutePath());
                    cmd.add("-b");
                    cmd.add("127.0.0.1:" + FCGI_PORT);
                    java.util.HashMap<String, String> env = new java.util.HashMap<String, String>();
                    env.put("PHP_FCGI_MAX_REQUESTS", "0");
                    env.put("PHP_FCGI_CHILDREN", "4");
                    env.put("SSL_CERT_FILE", new File(base, "ssl/cacert.pem").getAbsolutePath());
                    Process me = launchWith(cmd, "php", base, tmp, env);
                    if (me == null) {
                        return;
                    }
                    phpProc = me;
                    if (!phpWanted) {
                        me.destroy();
                        return;
                    }
                    log("PHP worker started on 127.0.0.1:" + FCGI_PORT);
                    try {
                        me.waitFor();
                    } catch (InterruptedException e) {
                        return;
                    }
                    if (!phpWanted) {
                        return;
                    }
                    log("PHP worker exited, restarting...");
                    try {
                        Thread.sleep(1500);
                    } catch (InterruptedException e2) {
                        return;
                    }
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private Process launchWith(List<String> cmd, String tag, File base, File tmp, java.util.Map<String, String> extra) {
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            if (extra != null) {
                pb.environment().putAll(extra);
            }
            pb.environment().put("LD_LIBRARY_PATH", new File(base, "lib").getAbsolutePath());
            pb.environment().put("HOME", getFilesDir().getAbsolutePath());
            pb.environment().put("TMPDIR", tmp.getAbsolutePath());
            pb.environment().put("PHPRC", new File(getFilesDir(), "etc").getAbsolutePath());
            pb.redirectErrorStream(true);
            Process p = pb.start();
            pump(p.getInputStream(), tag);
            return p;
        } catch (IOException e) {
            log("Cannot start " + tag + ": " + e.getMessage());
            return null;
        }
    }

    private void pump(final InputStream in, final String tag) {
        Thread t = new Thread(new Runnable() {
            public void run() {
                try {
                    BufferedReader r = new BufferedReader(new InputStreamReader(in));
                    String line;
                    while ((line = r.readLine()) != null) {
                        log("[" + tag + "] " + line);
                    }
                } catch (IOException e) {
                    // stream closed
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }

    private void stopWeb() {
        phpWanted = false;
        Process w = webProc;
        webProc = null;
        Process ph = phpProc;
        phpProc = null;
        if (w != null) {
            w.destroy();
        }
        if (ph != null) {
            ph.destroy();
        }
        if (webRunning) {
            log("Web server stopped.");
        }
        webRunning = false;
    }

    private void stopDb() {
        Process d = dbProc;
        dbProc = null;
        if (d != null) {
            tryGracefulDbShutdown();
            d.destroy();
        }
        if (dbRunning) {
            log("MariaDB stopped.");
        }
        dbRunning = false;
    }

    private void tryGracefulDbShutdown() {
        try {
            File base = Installer.baseDir(this);
            File admin = new File(base, "bin/mariadb-admin");
            if (!admin.exists()) {
                return;
            }
            List<String> cmd = new ArrayList<String>();
            cmd.add(admin.getAbsolutePath());
            cmd.add("--no-defaults");
            cmd.add("--socket=" + new File(getFilesDir(), "mysql.sock").getAbsolutePath());
            cmd.add("-uroot");
            cmd.add("shutdown");
            Process p = launch(cmd, "shutdown", base, getCacheDir());
            if (p != null) {
                p.waitFor();
            }
        } catch (Exception e) {
            log("Graceful shutdown failed: " + e.getMessage());
        }
    }

    private void writePhpIni(File f, File sock, int dbPort, File base, File tmp) throws IOException {
        StringBuilder s = new StringBuilder();
        s.append("opcache.enable = 0\n");
        s.append("opcache.enable_cli = 0\n");
        s.append("opcache.lockfile_path = " + tmp.getAbsolutePath() + "\n");
        s.append("sys_temp_dir = " + tmp.getAbsolutePath() + "\n");
        s.append("upload_tmp_dir = " + tmp.getAbsolutePath() + "\n");
        s.append("session.save_path = " + tmp.getAbsolutePath() + "\n");
        s.append("cgi.force_redirect = 0\n");
        s.append("cgi.fix_pathinfo = 1\n");
        s.append("display_errors = Off\n");
        s.append("log_errors = On\n");
        s.append("error_reporting = E_ALL & ~E_DEPRECATED & ~E_STRICT\n");
        s.append("date.timezone = UTC\n");
        s.append("memory_limit = 256M\n");
        s.append("upload_max_filesize = 64M\n");
        s.append("post_max_size = 64M\n");
        s.append("max_execution_time = 120\n");
        s.append("max_input_time = 60\n");
        s.append("expose_php = Off\n");
        s.append("short_open_tag = Off\n");
        String ca = new File(base, "ssl/cacert.pem").getAbsolutePath();
        s.append("curl.cainfo = \"" + ca + "\"\n");
        s.append("openssl.cafile = \"" + ca + "\"\n");
        s.append("extension_dir = \"" + new File(base, "lib/php").getAbsolutePath() + "\"\n");
        s.append(";extension = mysqli\n");
        s.append(";extension = pdo_mysql\n");
        s.append("mysqli.default_host = 127.0.0.1\n");
        s.append("mysqli.default_port = " + dbPort + "\n");
        s.append("mysqli.default_socket = " + sock.getAbsolutePath() + "\n");
        s.append("pdo_mysql.default_socket = " + sock.getAbsolutePath() + "\n");
        writeFile(f, s.toString());
    }

    private void writeLighttpdConf(File f, File docRoot, int port, int fcgiPort, File base,
            boolean https, int httpsPort, File cert, File key) throws IOException {
        StringBuilder s = new StringBuilder();
        s.append("server.modules = ( " + (https ? "\"mod_openssl\", " : "") + "\"mod_fastcgi\", \"mod_dirlisting\" )\n");
        s.append("server.document-root = \"" + docRoot.getAbsolutePath() + "\"\n");
        s.append("server.port = " + port + "\n");
        s.append("index-file.names = ( \"index.php\", \"index.html\" )\n");
        s.append("dir-listing.activate = \"enable\"\n");
        s.append("mimetype.assign = (\n");
        s.append("  \".html\" => \"text/html\",\n");
        s.append("  \".css\" => \"text/css\",\n");
        s.append("  \".js\" => \"application/javascript\",\n");
        s.append("  \".json\" => \"application/json\",\n");
        s.append("  \".png\" => \"image/png\",\n");
        s.append("  \".jpg\" => \"image/jpeg\",\n");
        s.append("  \".gif\" => \"image/gif\",\n");
        s.append("  \".txt\" => \"text/plain\",\n");
        s.append("  \".svg\" => \"image/svg+xml\",\n");
        s.append("  \".ico\" => \"image/x-icon\",\n");
        s.append("  \".webp\" => \"image/webp\",\n");
        s.append("  \".xml\" => \"application/xml\",\n");
        s.append("  \".woff\" => \"font/woff\",\n");
        s.append("  \".woff2\" => \"font/woff2\",\n");
        s.append("  \".ttf\" => \"font/ttf\",\n");
        s.append("  \".mp4\" => \"video/mp4\",\n");
        s.append("  \".webm\" => \"video/webm\",\n");
        s.append("  \".mp3\" => \"audio/mpeg\",\n");
        s.append("  \".pdf\" => \"application/pdf\",\n");
        s.append("  \".zip\" => \"application/zip\",\n");
        s.append("  \".map\" => \"application/json\",\n");
        s.append("  \"\" => \"application/octet-stream\"\n");
        s.append(")\n");
        s.append("fastcgi.server = ( \".php\" => ( ( \"host\" => \"127.0.0.1\", \"port\" => " + fcgiPort + " ) ) )\n");
        if (https) {
            s.append("$SERVER[\"socket\"] == \":" + httpsPort + "\" {\n");
            s.append("  ssl.engine = \"enable\"\n");
            s.append("  ssl.pemfile = \"" + cert.getAbsolutePath() + "\"\n");
            s.append("  ssl.privkey = \"" + key.getAbsolutePath() + "\"\n");
            s.append("}\n");
        }
        writeFile(f, s.toString());
    }

    private void writeFile(File f, String text) throws IOException {
        FileWriter w = new FileWriter(f);
        w.write(text);
        w.close();
    }
}
