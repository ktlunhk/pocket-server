package com.pocketserver;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetManager;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

public class Installer {

    public static File baseDir(Context c) {
        return new File(c.getFilesDir(), "server");
    }

    public static void install(Context c) throws IOException {
        SharedPreferences sp = c.getSharedPreferences("cfg", Context.MODE_PRIVATE);
        File base = baseDir(c);
        long stamp = 0;
        try {
            stamp = c.getPackageManager().getPackageInfo(c.getPackageName(), 0).lastUpdateTime;
        } catch (Exception e) {
            stamp = 1;
        }
        boolean need = sp.getLong("asset_stamp", 0) != stamp || !base.exists();
        if (need) {
            ServerService.log("Extracting server files...");
            copyAssets(c.getAssets(), "server", base);
            sp.edit().putLong("asset_stamp", stamp).commit();
        }
        makeExecutable(new File(base, "bin"));
    }

    private static void copyAssets(AssetManager am, String path, File dest) throws IOException {
        String[] list = am.list(path);
        if (list != null && list.length > 0) {
            dest.mkdirs();
            for (int i = 0; i < list.length; i++) {
                copyAssets(am, path + "/" + list[i], new File(dest, list[i]));
            }
        } else {
            File parent = dest.getParentFile();
            if (parent != null) {
                parent.mkdirs();
            }
            InputStream in = am.open(path);
            OutputStream out = new FileOutputStream(dest);
            byte[] buf = new byte[16384];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            out.close();
            in.close();
        }
    }

    private static void makeExecutable(File dir) {
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        for (int i = 0; i < files.length; i++) {
            if (files[i].isFile()) {
                files[i].setReadable(true, false);
                files[i].setExecutable(true, false);
            }
        }
    }

    public static void copyDefaultSite(Context c, File docRoot) {
        docRoot.mkdirs();
        File idx = new File(docRoot, "index.php");
        File idx2 = new File(docRoot, "index.html");
        if (idx.exists() || idx2.exists()) {
            return;
        }
        try {
            copyAssets(c.getAssets(), "www", docRoot);
        } catch (IOException e) {
            ServerService.log("Could not copy default site: " + e.getMessage());
        }
    }
}
