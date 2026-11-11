package fr.carnetdegres;

import android.content.Context;
import android.content.res.AssetManager;
import android.net.Uri;
import android.webkit.WebResourceResponse;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;
import java.util.Map;
import java.util.regex.Pattern;

/** Stockage local des photos et du fond de carte, et service des fichiers à la WebView. */
final class Pack {
    static final String APP_HOST = "appassets.androidplatform.net";
    private static final Pattern NUM = Pattern.compile("\\d{1,9}");
    private static final Pattern LAYER = Pattern.compile("plan|ortho");

    private Pack() {}

    static File root(Context c) {
        File d = c.getExternalFilesDir(null);
        if (d == null) d = c.getFilesDir();
        return new File(d, "pack");
    }

    /** Jeton "p/<id>" pour une photo, "t/<couche>/<z>/<x>/<y>" pour une tuile. */
    static String tokenForUrl(Uri u) {
        String host = u.getHost();
        if (host == null) return null;
        String path = u.getPath() == null ? "" : u.getPath();
        if (host.equals("assets.boolder.com") && path.startsWith("/proxy/topos/")) {
            String id = path.substring("/proxy/topos/".length());
            return NUM.matcher(id).matches() ? "p/" + id : null;
        }
        if (host.equals("data.geopf.fr") && path.startsWith("/wmts")) {
            String layer = u.getQueryParameter("LAYER");
            String l = "GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2".equals(layer) ? "plan"
                    : "ORTHOIMAGERY.ORTHOPHOTOS".equals(layer) ? "ortho" : null;
            String z = u.getQueryParameter("TILEMATRIX");
            String y = u.getQueryParameter("TILEROW");
            String x = u.getQueryParameter("TILECOL");
            if (l == null || z == null || x == null || y == null) return null;
            return "t/" + l + "/" + z + "/" + x + "/" + y;
        }
        return null;
    }

    static String urlForToken(String t) {
        String[] p = t.split("/");
        if (p.length == 2 && p[0].equals("p") && NUM.matcher(p[1]).matches()) {
            return "https://assets.boolder.com/proxy/topos/" + p[1];
        }
        if (p.length == 5 && p[0].equals("t") && LAYER.matcher(p[1]).matches()
                && NUM.matcher(p[2]).matches() && NUM.matcher(p[3]).matches() && NUM.matcher(p[4]).matches()) {
            boolean ortho = p[1].equals("ortho");
            return "https://data.geopf.fr/wmts?SERVICE=WMTS&REQUEST=GetTile&VERSION=1.0.0&LAYER="
                    + (ortho ? "ORTHOIMAGERY.ORTHOPHOTOS" : "GEOGRAPHICALGRIDSYSTEMS.PLANIGNV2")
                    + "&STYLE=normal&TILEMATRIXSET=PM&FORMAT=" + (ortho ? "image/jpeg" : "image/png")
                    + "&TILEMATRIX=" + p[2] + "&TILEROW=" + p[4] + "&TILECOL=" + p[3];
        }
        return null;
    }

    static File fileForToken(Context c, String t) {
        if (t == null || urlForToken(t) == null) return null;
        String[] p = t.split("/");
        if (p[0].equals("p")) return new File(root(c), "photos/" + p[1] + ".jpg");
        String ext = p[1].equals("ortho") ? ".jpg" : ".png";
        return new File(root(c), "fond/" + p[1] + "/" + p[2] + "/" + p[3] + "/" + p[4] + ext);
    }

    private static Set<String> embedded;

    /** Photos intégrées à l'APK (dossier assets/photos), lues une seule fois. */
    static synchronized Set<String> embedded(Context c) {
        if (embedded == null) {
            embedded = new HashSet<>();
            try {
                String[] l = c.getAssets().list("photos");
                if (l != null) for (String n : l) embedded.add(n);
            } catch (Exception ignored) {
            }
        }
        return embedded;
    }

    private static String embeddedName(Context c, String t) {
        if (t == null || !t.startsWith("p/")) return null;
        String id = t.substring(2);
        Set<String> e = embedded(c);
        if (e.contains(id + ".avif")) return id + ".avif";
        return e.contains(id + ".jpg") ? id + ".jpg" : null;
    }

    /** Vrai si le fichier est déjà disponible sans réseau (intégré à l'APK ou téléchargé). */
    static boolean available(Context c, String t) {
        if (embeddedName(c, t) != null) return true;
        File f = fileForToken(c, t);
        return f != null && f.exists() && f.length() > 0;
    }

    static String mime(String name) {
        String n = name.toLowerCase();
        if (n.endsWith(".html")) return "text/html";
        if (n.endsWith(".js")) return "application/javascript";
        if (n.endsWith(".css")) return "text/css";
        if (n.endsWith(".json") || n.endsWith(".webmanifest")) return "application/json";
        if (n.endsWith(".png")) return "image/png";
        if (n.endsWith(".avif")) return "image/avif";
        if (n.endsWith(".webp")) return "image/webp";
        if (n.endsWith(".svg")) return "image/svg+xml";
        return "image/jpeg";
    }

    private static Map<String, String> headers() {
        Map<String, String> h = new HashMap<>();
        h.put("Access-Control-Allow-Origin", "*");
        h.put("Cache-Control", "no-cache");
        return h;
    }

    static WebResourceResponse notFound() {
        return new WebResourceResponse("text/plain", "utf-8", 404, "Not Found", headers(),
                new ByteArrayInputStream(new byte[0]));
    }

    private static WebResourceResponse ok(String mime, InputStream in) {
        return new WebResourceResponse(mime, mime.startsWith("text/") ? "utf-8" : null, 200, "OK", headers(), in);
    }

    /** Appelé pour chaque requête de la WebView ; null = laisser passer sur le réseau. */
    static WebResourceResponse intercept(Context c, Uri u) {
        String host = u.getHost();
        if (APP_HOST.equals(host)) {
            String path = u.getPath() == null || u.getPath().equals("/") ? "/index.html" : u.getPath();
            if (path.contains("..")) return notFound();
            try {
                AssetManager am = c.getAssets();
                return ok(mime(path), am.open("www" + path));
            } catch (Exception e) {
                return notFound();
            }
        }
        String token = tokenForUrl(u);
        if (token == null) return null;
        String emb = embeddedName(c, token);
        if (emb != null) {
            try {
                return ok(mime(emb), c.getAssets().open("photos/" + emb));
            } catch (Exception ignored) {
            }
        }
        File f = fileForToken(c, token);
        if (f == null) return notFound();
        try {
            if (!(f.exists() && f.length() > 0)) {
                if (download(urlForToken(token), f) <= 0) return notFound();
            }
            return ok(mime(f.getName()), new FileInputStream(f));
        } catch (Exception e) {
            return notFound();
        }
    }

    /** Télécharge une image dans f. Renvoie le nombre d'octets, ou -1 en cas d'échec. */
    static long download(String url, File f) {
        if (url == null) return -1;
        HttpURLConnection con = null;
        File tmp = new File(f.getPath() + ".part" + Thread.currentThread().getId());
        try {
            File dir = f.getParentFile();
            if (dir != null && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) return -1;
            con = (HttpURLConnection) new URL(url).openConnection();
            con.setConnectTimeout(15000);
            con.setReadTimeout(30000);
            con.setRequestProperty("User-Agent", "CarnetDeGres/1.0 (Android; usage personnel)");
            if (con.getResponseCode() != 200) return -1;
            String type = con.getContentType();
            if (type == null || !type.startsWith("image/")) return -1;
            long n = 0;
            try (InputStream in = con.getInputStream(); OutputStream out = new FileOutputStream(tmp)) {
                byte[] buf = new byte[32768];
                int r;
                while ((r = in.read(buf)) > 0) {
                    out.write(buf, 0, r);
                    n += r;
                }
            }
            if (n == 0 || !tmp.renameTo(f)) {
                tmp.delete();
                return f.exists() && f.length() > 0 ? f.length() : -1;
            }
            return n;
        } catch (Exception e) {
            tmp.delete();
            return -1;
        } finally {
            if (con != null) con.disconnect();
        }
    }

    /** Liste les photos intégrées dès le démarrage, en arrière-plan : la lecture de l'APK peut prendre plusieurs secondes. */
    static void warmUp(Context c) {
        Context app = c.getApplicationContext();
        new Thread(() -> embedded(app), "photos-integrees").start();
    }

    /** Taille totale et nombre de fichiers, en un seul parcours. */
    static long[] measure(File f) {
        long[] r = new long[2];
        java.util.ArrayDeque<File> todo = new java.util.ArrayDeque<>();
        todo.push(f);
        while (!todo.isEmpty()) {
            File[] list = todo.pop().listFiles();
            if (list == null) continue;
            for (File x : list) {
                if (x.isDirectory()) todo.push(x);
                else {
                    r[0] += x.length();
                    r[1]++;
                }
            }
        }
        return r;
    }

    static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        long s = 0;
        File[] list = f.listFiles();
        if (list != null) for (File x : list) s += sizeOf(x);
        return s;
    }

    static int countFiles(File f) {
        if (f.isFile()) return 1;
        int n = 0;
        File[] list = f.listFiles();
        if (list != null) for (File x : list) n += countFiles(x);
        return n;
    }
}
