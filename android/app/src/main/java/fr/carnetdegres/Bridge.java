package fr.carnetdegres;

import android.content.Intent;
import android.os.Build;
import android.webkit.JavascriptInterface;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

/** Fonctions appelées depuis la page sous le nom window.Native. */
final class Bridge {
    private final MainActivity activity;
    private volatile long usedBytes = -1;
    private volatile int usedFiles = 0;
    private volatile long usedAt = 0;
    private volatile boolean measuring = false;

    Bridge(MainActivity a) {
        activity = a;
    }

    @JavascriptInterface
    public String version() {
        return "1";
    }

    /** tokens : tableau JSON de jetons ; renvoie le nombre déjà enregistrés. */
    /** Nombre de photos intégrées à l'APK (0 pour la version publique). */
    @JavascriptInterface
    public int embeddedPhotos() {
        return Pack.embedded(activity).size();
    }

    @JavascriptInterface
    public int countExisting(String tokens) {
        try {
            JSONArray a = new JSONArray(tokens);
            int n = 0;
            for (int i = 0; i < a.length(); i++) {
                if (Pack.available(activity, a.getString(i))) n++;
            }
            return n;
        } catch (Exception e) {
            return 0;
        }
    }

    @JavascriptInterface
    public String download(String tokens, String label) {
        try {
            JSONArray a = new JSONArray(tokens);
            List<String> list = new ArrayList<>(a.length());
            for (int i = 0; i < a.length(); i++) {
                String t = a.getString(i);
                if (Pack.fileForToken(activity, t) != null && !Pack.available(activity, t)) list.add(t);
            }
            if (list.isEmpty()) return "complet";
            DownloadService.add(list, label);
            activity.runOnUiThread(() -> {
                activity.askNotifications();
                Intent i = new Intent(activity, DownloadService.class);
                if (Build.VERSION.SDK_INT >= 26) activity.startForegroundService(i);
                else activity.startService(i);
            });
            return "ok";
        } catch (Exception e) {
            return "erreur";
        }
    }

    /** Enregistre un fichier texte là où l'utilisateur le choisit ; le résultat revient par window.onNativeSaved. */
    @JavascriptInterface
    public void saveFile(String name, String content) {
        activity.runOnUiThread(() -> activity.saveFile(name, content));
    }

    @JavascriptInterface
    public void stop() {
        DownloadService.requestStop();
    }

    @JavascriptInterface
    public String status() {
        try {
            JSONObject o = new JSONObject();
            o.put("running", DownloadService.running);
            o.put("label", DownloadService.label);
            o.put("total", DownloadService.total.get());
            o.put("done", DownloadService.done.get());
            o.put("failed", DownloadService.failed.get());
            o.put("bytes", DownloadService.bytes.get());
            o.put("finishedAt", DownloadService.finishedAt);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }

    /** Espace utilisé (mesuré en arrière-plan) et espace libre. */
    @JavascriptInterface
    public String storage() {
        File root = Pack.root(activity);
        long now = System.currentTimeMillis();
        // Parcourir des dizaines de milliers de tuiles est lent : une mesure toutes les 2 minutes au plus
        if (!measuring && now - usedAt > 120000) {
            measuring = true;
            new Thread(() -> {
                long[] m = Pack.measure(root);
                usedBytes = m[0];
                usedFiles = (int) m[1];
                usedAt = System.currentTimeMillis();
                measuring = false;
            }, "mesure-stockage").start();
        }
        try {
            JSONObject o = new JSONObject();
            File base = root.getParentFile() != null ? root.getParentFile() : root;
            o.put("path", root.getAbsolutePath());
            o.put("free", base.getUsableSpace());
            o.put("used", usedBytes);
            o.put("files", usedFiles);
            return o.toString();
        } catch (Exception e) {
            return "{}";
        }
    }
}
