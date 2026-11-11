package fr.carnetdegres;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;
import android.os.PowerManager;

import java.io.File;
import java.util.Collection;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/** Télécharge une file de photos et de tuiles, même écran éteint. */
public class DownloadService extends Service {
    private static final String CHANNEL = "telechargement";
    private static final int NOTIF = 1;
    private static final int WORKERS = 4;

    static final ConcurrentLinkedQueue<String> QUEUE = new ConcurrentLinkedQueue<>();
    static final AtomicInteger total = new AtomicInteger();
    static final AtomicInteger done = new AtomicInteger();
    static final AtomicInteger failed = new AtomicInteger();
    static final AtomicLong bytes = new AtomicLong();
    static volatile boolean running = false;
    static volatile boolean stopRequested = false;
    static volatile String label = "";
    static volatile long finishedAt = 0;

    static synchronized void add(Collection<String> tokens, String what) {
        if (!running) {
            total.set(0);
            done.set(0);
            failed.set(0);
            bytes.set(0);
        }
        QUEUE.addAll(tokens);
        total.addAndGet(tokens.size());
        label = what;
        stopRequested = false;
    }

    static void requestStop() {
        stopRequested = true;
        QUEUE.clear();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        Notification n = build("Préparation…", 0, 0);
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF, n);
        }
        synchronized (DownloadService.class) {
            if (!running) {
                running = true;
                new Thread(this::run, "telechargement").start();
            }
        }
        return START_NOT_STICKY;
    }

    private void run() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        PowerManager.WakeLock lock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "carnet:telechargement");
        lock.acquire(6 * 60 * 60 * 1000L);
        Thread[] workers = new Thread[WORKERS];
        for (int i = 0; i < WORKERS; i++) {
            workers[i] = new Thread(this::work);
            workers[i].start();
        }
        NotificationManager nm = getSystemService(NotificationManager.class);
        boolean alive = true;
        while (alive) {
            alive = false;
            for (Thread w : workers) {
                try {
                    w.join(2000);
                } catch (InterruptedException ignored) {
                }
                if (w.isAlive()) alive = true;
            }
            int d = done.get() + failed.get();
            nm.notify(NOTIF, build(d + " / " + total.get() + " fichiers", total.get(), d));
        }
        synchronized (DownloadService.class) {
            running = false;
            finishedAt = System.currentTimeMillis();
        }
        if (lock.isHeld()) lock.release();
        stopForeground(true);
        String end = stopRequested ? "Téléchargement arrêté"
                : failed.get() == 0 ? "Téléchargement terminé"
                : "Terminé avec " + failed.get() + " échecs : relance-le depuis l'application";
        nm.notify(NOTIF + 1, build(end, 0, 0));
        stopSelf();
    }

    private void work() {
        String t;
        while (!stopRequested && (t = QUEUE.poll()) != null) {
            File f = Pack.fileForToken(this, t);
            if (f == null) {
                failed.incrementAndGet();
                continue;
            }
            if (f.exists() && f.length() > 0) {
                done.incrementAndGet();
                continue;
            }
            long n = Pack.download(Pack.urlForToken(t), f);
            if (n < 0) {
                n = Pack.download(Pack.urlForToken(t), f);
            }
            if (n > 0) {
                bytes.addAndGet(n);
                done.incrementAndGet();
            } else {
                failed.incrementAndGet();
            }
        }
    }

    private Notification build(String text, int max, int progress) {
        NotificationManager nm = getSystemService(NotificationManager.class);
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            if (nm.getNotificationChannel(CHANNEL) == null) {
                nm.createNotificationChannel(new NotificationChannel(CHANNEL, "Téléchargements",
                        NotificationManager.IMPORTANCE_LOW));
            }
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        Intent open = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(this, 0, open, PendingIntent.FLAG_IMMUTABLE);
        b.setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(label.isEmpty() ? "Carnet de grès" : label)
                .setContentText(text)
                .setContentIntent(pi)
                .setOnlyAlertOnce(true);
        if (max > 0) b.setProgress(max, progress, false);
        return b.build();
    }
}
